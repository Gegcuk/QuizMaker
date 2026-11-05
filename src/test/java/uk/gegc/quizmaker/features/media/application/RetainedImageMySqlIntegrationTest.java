package uk.gegc.quizmaker.features.media.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.application.impl.ArticleServiceImpl;
import uk.gegc.quizmaker.features.article.infra.mapping.ArticleMapper;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.model.ArticleBlockType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleContentType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.media.application.impl.*;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.*;
import uk.gegc.quizmaker.features.media.domain.repository.*;
import uk.gegc.quizmaker.features.media.infra.mapping.MediaAssetMapper;
import uk.gegc.quizmaker.features.question.api.dto.*;
import uk.gegc.quizmaker.features.question.application.QuestionService;
import uk.gegc.quizmaker.features.question.application.impl.QuestionServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.TrueFalseHandler;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionMediaResolver;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.*;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import jakarta.persistence.EntityManagerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real authoring, permissions, persistence and transactions; only external storage is replaced. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({ArticleServiceImpl.class, ArticleMapper.class, RetainedImageServiceImpl.class, MediaAssetServiceImpl.class, MediaAssetMapper.class, QuestionServiceImpl.class,
        QuestionMediaResolver.class, QuestionHandlerFactory.class, TrueFalseHandler.class, AppPermissionEvaluator.class,
        RetainedImageMySqlIntegrationTest.Config.class})
class RetainedImageMySqlIntegrationTest {
    static final String schema = "retained_images_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        properties.add("spring.datasource.url", () -> base + schema + options);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
    }
    @AfterAll static void dropOwnedDatabase() {
        if (admin != null) admin.execute("DROP DATABASE " + schema);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock imageClock() { return Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry imageMetrics() { return new SimpleMeterRegistry(); }
        @Bean MediaStorageProperties imageProperties() { return new MediaStorageProperties(); }
        @Bean FakeStorage imageStorage() { return new FakeStorage(); }
        @Bean BindingFailure bindingFailure(JdbcTemplate sql) { return new BindingFailure(sql); }
    }
    @MockitoBean S3Client unusedLegacyStorage;
    @MockitoBean S3Presigner unusedPresigner;
    @Autowired RetainedImageService images;
    @Autowired MediaAssetService library;
    @Autowired QuestionService questionService;
    @Autowired ArticleService articles;
    @Autowired SavedImageVersionRepository versions;
    @Autowired SavedImageBindingRepository bindings;
    @Autowired MediaAssetRepository assets;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired uk.gegc.quizmaker.features.user.domain.repository.RoleRepository roles;
    @Autowired QuizRepository quizzes;
    @Autowired CategoryRepository categories;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate sql;
    @Autowired FakeStorage storage;
    @Autowired MediaStorageProperties properties;
    @Autowired AppPermissionEvaluator permissions;
    @Autowired Clock clock;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired BindingFailure failure;
    TransactionTemplate tx;
    String username;
    UUID quizId;
    UUID assetId;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        storage.reset();
        failure.armed = false; failure.reached.set(false); failure.expectedBindings = 1;
        username = "img-" + UUID.randomUUID().toString().substring(0, 12);
        tx.executeWithoutResult(s -> {
            User owner = new User(); owner.setUsername(username); owner.setEmail(username + "@example.invalid");
            owner.setHashedPassword("test-only"); owner.setActive(true); owner.setRoles(new HashSet<>());
            owner.setCreatedAt(LocalDateTime.of(2026, 10, 23, 12, 0)); owner.setPasswordChangedAt(owner.getCreatedAt());
            users.saveAndFlush(owner);
            Category category = new Category(); category.setName(username); category.setDescription("Image tests");
            categories.saveAndFlush(category);
            Quiz quiz = new Quiz(); quiz.setTitle(username); quiz.setDescription("Retained image quiz");
            quiz.setCreator(owner); quiz.setCategory(category); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false); quizzes.saveAndFlush(quiz); quizId = quiz.getId();
            assetId = insertAsset(username);
        });
        properties.setDeleteRemote(true); // Ordinary image removal must still never delete bytes.
        authenticate(username);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        storage.gate = null;
        // This class owns its isolated schema; every assertion precedes fixture cleanup.
        tx.executeWithoutResult(s -> {
            sql.update("DELETE FROM articles");
            sql.update("DELETE FROM saved_image_bindings");
            sql.update("DELETE FROM saved_image_versions");
            sql.update("DELETE FROM quiz_questions");
            sql.update("DELETE FROM question_tags");
            sql.update("DELETE FROM questions");
            sql.update("DELETE FROM quizzes");
            sql.update("DELETE FROM categories");
            sql.update("DELETE FROM media_assets");
            sql.update("DELETE FROM user_roles");
            sql.update("DELETE FROM users");
        });
    }

    @Test void savedDraftKeepsExactGenerationAcrossRemovalAndMetadataReplacement() {
        UUID question = questionService.createQuestion(username, request(assetId));
        var before = acquire(question);
        assertThat(storage.content(before.objectKey())).isEqualTo("A");
        storage.sources.put("source/" + assetId, "B");
        tx.executeWithoutResult(s -> {
            var asset = assets.findById(assetId).orElseThrow();
            asset.setWidth(999); asset.setOriginalFilename("renamed.png");
        });
        library.delete(assetId, username);
        library.delete(assetId, username);
        assertThat(library.search(null, null, 0, 20, null, null, username).getContent()).isEmpty();
        assertThatThrownBy(() -> library.getById(assetId, username)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> library.finalizeUpload(assetId, null, username)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(acquire(question)).isEqualTo(before);
        var dto = questionService.getQuestion(question, SecurityContextHolder.getContext().getAuthentication());
        assertThat(dto.getAttachment().cdnUrl()).endsWith(before.objectKey());
        assertThat(dto.getAttachment().width()).isEqualTo(640);
        assertThat(storage.content(before.objectKey())).isEqualTo("A");
        var update = update(assetId);
        update.setQuestionText("Updated draft with the same saved image");
        questionService.updateQuestion(username, question, update);
        assertThat(acquire(question)).isEqualTo(before);
        long count = questions.count();
        assertThatThrownBy(() -> questionService.createQuestion(username, request(assetId))).isInstanceOf(ValidationException.class);
        assertThat(questions.count()).isEqualTo(count);
        assertThat(storage.copies.get()).isEqualTo(1);
        verifyNoInteractions(unusedLegacyStorage);
    }

    @Test void removalOfADraftReferenceDoesNotAuthorizeReaddingTheRemovedAsset() {
        UUID question = questionService.createQuestion(username, request(assetId));
        library.delete(assetId, username);
        var clear = update(null); clear.setClearAttachment(true);
        questionService.updateQuestion(username, question, clear);
        assertThatThrownBy(() -> questionService.updateQuestion(username, question, update(assetId)))
                .isInstanceOf(ValidationException.class);
        assertThat(questions.findById(question).orElseThrow().getAttachmentAssetId()).isNull();
        assertThat(bindings.findAllByQuestionId(question)).hasSize(1); // Evidence survives; it is not a reuse grant.
    }

    @Test void restoreRechecksCurrentRightsAndOriginalObjectBeforeAllowingSelection() {
        UUID question = questionService.createQuestion(username, request(assetId));
        var original = acquire(question);
        library.delete(assetId, username);
        assertThatThrownBy(() -> library.restore(assetId, "other")).isInstanceOf(ForbiddenException.class);
        storage.unavailable = true;
        assertThatThrownBy(() -> library.restore(assetId, username)).isInstanceOf(SavedImageException.class);
        assertThat(assets.findById(assetId).orElseThrow().isLibraryRemoved()).isTrue();
        storage.unavailable = false;
        storage.sources.put("source/" + assetId, "B");
        tx.executeWithoutResult(s -> assets.findById(assetId).orElseThrow().setWidth(999));
        var restored = library.restore(assetId, username);
        assertThat(restored.status()).isEqualTo(MediaAssetStatus.READY);
        assertThat(restored.key()).isEqualTo(original.objectKey());
        assertThat(restored.width()).isEqualTo(640);
        assertThat(library.search(null, null, 0, 10, null, null, username).getContent()).containsExactly(restored);
        assertThat(library.search(null, restored.key(), 0, 10, null, null, username).getContent()).containsExactly(restored);
        library.restore(assetId, username);
        UUID second = questionService.createQuestion(username, request(assetId));
        assertThat(acquire(second).versionId()).isEqualTo(original.versionId());
        assertThat(storage.copies.get()).isEqualTo(1);
    }

    @Test void providerWaitHasNoTransactionAndRevokedOwnershipPreventsBinding() throws Exception {
        storage.gate = new Gate();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> withRequestPersistenceContext(() -> questionService.createQuestion(username, request(assetId))));
            assertThat(storage.gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(s -> assets.findById(assetId).orElseThrow().setCreatedBy("other"));
            storage.gate.release.countDown();
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ValidationException.class);
            assertThat(questions.count()).isZero();
            assertThat(bindings.count()).isZero();
            assertThat(versions.findByAssetId(assetId).orElseThrow().isReady()).isFalse();
        } finally { storage.gate.release.countDown(); pool.shutdownNow(); }
    }

    @Test void copyResponseLossReconcilesTheSameDurableVersionAfterServiceRestart() {
        storage.loseResponse = true;
        assertThatThrownBy(() -> questionService.createQuestion(username, request(assetId))).isInstanceOf(SavedImageException.class);
        UUID preparedId = versions.findByAssetId(assetId).orElseThrow().getId();
        assertThat(questions.count()).isZero(); assertThat(bindings.count()).isZero();
        var restarted = new RetainedImageServiceImpl(assets, versions, bindings, storage, permissions, clock, manager, properties, metrics, emf);
        restarted.prepare(Set.of(assetId), username);
        UUID question = questionService.createQuestion(username, request(assetId));
        assertThat(acquire(question).versionId()).isEqualTo(preparedId);
        assertThat(storage.copies.get()).isEqualTo(1);
    }

    @Test void databaseRollbackReachesActualBindingWritesThenLeavesNoDraftOrBinding() {
        failure.armed = true;
        assertThatThrownBy(() -> questionService.createQuestion(username, request(assetId)))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected after binding flush");
        assertThat(failure.reached).isTrue();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM saved_image_bindings", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM questions", Integer.class)).isZero();
        // Internal prepared storage may survive, but is never a partially saved public draft.
        failure.armed = false;
        UUID success = questionService.createQuestion(username, request(assetId));
        assertThat(acquire(success).versionId()).isNotNull();
    }

    @Test void publicationConsumerAndRemovalBothSucceedWithoutChangingTheBinding() throws Exception {
        UUID question = questionService.createQuestion(username, request(assetId));
        var expected = acquire(question);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var read = pool.submit(() -> { await(start); return acquire(question); });
            var remove = pool.submit(() -> { await(start); library.delete(assetId, username); return true; });
            start.countDown();
            assertThat(read.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(remove.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(acquire(question)).isEqualTo(expected);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void removedImageCannotWinANewBindingAfterPreparation() {
        images.prepare(Set.of(assetId), username);
        library.delete(assetId, username);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> images.bind(UUID.randomUUID(), Set.of(assetId), Set.of(), username)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(bindings.count()).isZero();
    }

    @Test void wrongQuizOwnerIsDeniedBeforeStorageAndPublicationRequiresCurrentAuthority() {
        assertThatThrownBy(() -> questionService.createQuestion("missing-user", request(assetId)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(storage.calls.get()).isZero();
        UUID q = questionService.createQuestion(username, request(assetId));
        assertThatThrownBy(() -> images.acquireForPublication(q, Set.of(assetId), () -> { throw new ForbiddenException("denied"); }))
                .isInstanceOf(ForbiddenException.class);
        assertThat(acquire(q).bindingId()).isNotNull();
    }

    @Test void multiQuestionProjectionHasBoundedSqlThroughSerialization() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID id = i == 0 ? assetId : tx.execute(s -> insertAsset(username));
            questionService.createQuestion(username, request(id));
            library.delete(id, username);
        }
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var page = questionService.listQuestions(quizId, org.springframework.data.domain.PageRequest.of(0, 20),
                SecurityContextHolder.getContext().getAuthentication());
        var response = new ObjectMapper().findAndRegisterModules().valueToTree(page.getContent());
        assertThat(response.size()).isEqualTo(5);
        response.forEach(q -> assertThat(q.path("attachment").path("cdnUrl").asText()).contains("retained-images/"));
        long five = statistics.getPrepareStatementCount();
        for (int i = 0; i < 5; i++) questionService.createQuestion(username, request(tx.execute(s -> insertAsset(username))));
        statistics.clear();
        var tenPage = questionService.listQuestions(quizId, org.springframework.data.domain.PageRequest.of(0, 20),
                SecurityContextHolder.getContext().getAuthentication());
        assertThat(new ObjectMapper().findAndRegisterModules().valueToTree(tenPage.getContent()).size()).isEqualTo(10);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(five);
        assertThat(five).isLessThanOrEqualTo(12);
    }

    @Test void existingArticleStillReadsWhileNewHeroAndInlineSelectionAreDenied() {
        var original = articles.createArticle(username, articleRequest("existing", assetId, assetId));
        library.delete(assetId, username);
        var read = articles.getArticle(original.id(), false);
        assertThat(read.heroImage().rendition().url()).contains("retained-images/");
        articles.updateArticle(username, original.id(), articleRequest("existing", assetId, assetId));
        assertThatThrownBy(() -> articles.createArticle(username, articleRequest("new-hero", assetId, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> articles.createArticles(username, List.of(articleRequest("new-inline", null, assetId))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM articles", Integer.class)).isEqualTo(1);
        articles.updateArticle(username, original.id(), articleRequest("existing", null, null));
        assertThatThrownBy(() -> articles.updateArticles(username, List.of(new ArticleBulkUpdateItem(original.id(),
                articleRequest("existing", assetId, null)))))
                .isInstanceOf(ResourceNotFoundException.class);
        library.restore(assetId, username);
        articles.createArticle(username, articleRequest("restored", assetId, assetId));
    }

    private ArticleUpsertRequest articleRequest(String slug, UUID hero, UUID inline) {
        return new ArticleUpsertRequest(slug, "Saved image article", "Description", "Excerpt", null,
                hero == null ? null : new ArticleImageDto(hero, "Hero", null), List.of(),
                new ArticleAuthorDto("Author", "Teacher"), "5 min", Instant.parse("2026-10-23T12:00:00Z"),
                ArticleStatus.PUBLISHED, null, null, false, ArticleContentType.BLOG, null, null,
                List.of(), List.of(), List.of(), inline == null ? List.of() : List.of(
                        new ArticleBlockDto(ArticleBlockType.IMAGE, null, inline, "Inline", null, "center")),
                List.of(), List.of(), List.of());
    }

    @Test void inlineImageIsBoundAndClientDeliveryFieldsDoNotChooseTheStoredObject() {
        var request = request(null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.getContent()).putObject("media")
                .put("assetId", assetId.toString()).put("cdnUrl", "https://untrusted.example.invalid/substitute");
        UUID question = questionService.createQuestion(username, request);
        assertThat(bindings.findAllByQuestionId(question)).hasSize(1);
        assertThat(questions.findById(question).orElseThrow().getContent()).doesNotContain("untrusted", "cdnUrl");
        library.delete(assetId, username);
        var dto = questionService.getQuestion(question, SecurityContextHolder.getContext().getAuthentication());
        assertThat(dto.getContent().path("media").path("cdnUrl").asText()).contains("retained-images/");
        assertThatThrownBy(() -> questionService.createQuestion(username, request)).isInstanceOf(ValidationException.class);
    }

    @Test void authenticatedOtherAuthorCannotModifyOrReadPrivateQuestionOrUseOwnerImage() {
        String other = "other-" + username;
        tx.executeWithoutResult(s -> {
            User actor = new User(); actor.setUsername(other); actor.setEmail(other + "@example.invalid");
            actor.setHashedPassword("test-only"); actor.setActive(true); actor.setRoles(new HashSet<>());
            actor.setCreatedAt(LocalDateTime.of(2026, 10, 23, 12, 0)); actor.setPasswordChangedAt(actor.getCreatedAt());
            users.saveAndFlush(actor);
        });
        assertThatThrownBy(() -> questionService.createQuestion(other, request(assetId))).isInstanceOf(ForbiddenException.class);
        assertThat(storage.calls.get()).isZero();
        UUID question = questionService.createQuestion(username, request(assetId));
        authenticate(other);
        assertThatThrownBy(() -> questionService.getQuestion(question, SecurityContextHolder.getContext().getAuthentication()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> questionService.updateQuestion(other, question, update(assetId))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> library.restore(assetId, other)).isInstanceOf(ForbiddenException.class);
        assertThat(bindings.findAllByQuestionId(question)).hasSize(1);
        authenticate(username);
        assertThat(acquire(question).versionId()).isNotNull();
    }

    @Test void failedQuestionUpdateRollsBackNewBindingAndPreservesOriginalEvidence() {
        UUID question = questionService.createQuestion(username, request(assetId));
        var original = acquire(question);
        UUID replacement = tx.execute(s -> insertAsset(username));
        failure.expectedBindings = 2; failure.armed = true;
        assertThatThrownBy(() -> questionService.updateQuestion(username, question, update(replacement)))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected after binding flush");
        assertThat(failure.reached).isTrue();
        assertThat(questions.findById(question).orElseThrow().getAttachmentAssetId()).isEqualTo(assetId);
        assertThat(bindings.findAllByQuestionId(question)).hasSize(1);
        assertThat(acquire(question)).isEqualTo(original);
        failure.armed = false;
        questionService.updateQuestion(username, question, update(replacement));
        assertThat(bindings.findAllByQuestionId(question)).hasSize(2);
    }

    @Test void libraryProjectionLoadsExactMetadataInBoundedBatches() {
        for (int i = 0; i < 5; i++) images.prepare(Set.of(i == 0 ? assetId : tx.execute(s -> insertAsset(username))), username);
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var five = library.search(null, null, 0, 20, null, null, username);
        assertThat(five.getContent()).hasSize(5).allSatisfy(image -> assertThat(image.key()).startsWith("retained-images/"));
        long count = statistics.getPrepareStatementCount();
        for (int i = 0; i < 5; i++) {
            UUID image = tx.execute(s -> insertAsset(username));
            images.prepare(Set.of(image), username);
        }
        statistics.clear();
        assertThat(library.search(null, null, 0, 20, null, null, username).getContent()).hasSize(10);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(count);
        assertThat(count).isLessThanOrEqualTo(5);
    }

    @Test void changedSourceCannotSilentlyReplaceAPendingGeneration() throws Exception {
        storage.gate = new Gate();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> questionService.createQuestion(username, request(assetId)));
            assertThat(storage.gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            UUID prepared = versions.findByAssetId(assetId).orElseThrow().getId();
            storage.sources.put("source/" + assetId, "B");
            storage.gate.release.countDown();
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(SavedImageException.class);
            assertThat(versions.findById(prepared).orElseThrow().isReady()).isFalse();
            assertThat(bindings.count()).isZero(); assertThat(questions.count()).isZero();
            assertThat(storage.copies.get()).isZero();
            storage.sources.put("source/" + assetId, "A");
            storage.gate = null;
            UUID question = questionService.createQuestion(username, request(assetId));
            assertThat(acquire(question).versionId()).isEqualTo(prepared);
            assertThat(storage.content(acquire(question).objectKey())).isEqualTo("A");
        } finally {
            if (storage.gate != null) storage.gate.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void openEntityManagerInViewCannotKeepRevokedPermissionAcrossStorageWait() throws Exception {
        Long roleId = tx.execute(s -> {
            var role = roles.findAllWithPermissions().stream().filter(r -> r.getPermissions().stream()
                    .anyMatch(p -> p.getPermissionName().equals("QUIZ_MODERATE"))).findFirst().orElseThrow();
            users.findByUsername(username).orElseThrow().getRoles().add(role);
            User creator = new User(); creator.setUsername("other-" + username); creator.setEmail("other-" + username + "@example.invalid");
            creator.setHashedPassword("test-only"); creator.setActive(true); creator.setRoles(new HashSet<>());
            creator.setCreatedAt(LocalDateTime.of(2026, 10, 23, 12, 0)); creator.setPasswordChangedAt(creator.getCreatedAt());
            users.saveAndFlush(creator);
            var original = quizzes.findById(quizId).orElseThrow();
            Quiz foreign = new Quiz(); foreign.setTitle("Other author's quiz"); foreign.setDescription("Private draft");
            foreign.setCreator(creator); foreign.setCategory(original.getCategory()); foreign.setDifficulty(Difficulty.EASY);
            foreign.setStatus(QuizStatus.DRAFT); foreign.setVisibility(Visibility.PRIVATE); foreign.setEstimatedTime(5);
            foreign.setIsTimerEnabled(false); foreign.setIsRepetitionEnabled(false);
            quizId = quizzes.saveAndFlush(foreign).getId();
            return role.getRoleId();
        });
        storage.gate = new Gate();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> withRequestPersistenceContext(
                    () -> questionService.createQuestion(username, request(assetId))));
            assertThat(storage.gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(s -> users.findByUsername(username).orElseThrow().getRoles().clear());
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM user_roles", Integer.class)).isZero();
            storage.gate.release.countDown();
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ForbiddenException.class);
            assertThat(questions.count()).isZero(); assertThat(bindings.count()).isZero();
            tx.executeWithoutResult(s -> users.findByUsername(username).orElseThrow().getRoles().add(roles.findById(roleId).orElseThrow()));
            storage.gate = null;
            UUID question = questionService.createQuestion(username, request(assetId));
            assertThat(bindings.findAllByQuestionId(question)).hasSize(1);
            assertThat(questionService.getQuestion(question, SecurityContextHolder.getContext().getAuthentication()).getId()).isEqualTo(question);
        } finally {
            if (storage.gate != null) storage.gate.release.countDown();
            pool.shutdownNow();
        }
    }

    private <T> T withRequestPersistenceContext(Callable<T> action) throws Exception {
        // Spring's default OSIV holds this same persistence context across service transactions.
        var entityManager = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new org.springframework.orm.jpa.EntityManagerHolder(entityManager));
        authenticate(username);
        try { return action.call(); }
        finally {
            SecurityContextHolder.clearContext();
            TransactionSynchronizationManager.unbindResource(emf);
            entityManager.close();
        }
    }

    private RetainedImageService.ImageReference acquire(UUID question) {
        return images.acquireForPublication(question, Set.of(assetId), () -> {
            // Fake publication consumer uses a real current author/resource permission check.
            var q = questions.findById(question).orElseThrow();
            assertThat(q.getQuizId()).anySatisfy(quiz -> assertThat(quiz.getCreator().getUsername()).isEqualTo(username));
            assertThat(q.getAttachmentAssetId()).isEqualTo(assetId);
        }).get(assetId);
    }
    private UUID insertAsset(String owner) {
        var asset = new MediaAsset(); var id = UUID.randomUUID(); asset.setId(id);
        asset.setKey("source/" + id); asset.setType(MediaAssetType.IMAGE); asset.setStatus(MediaAssetStatus.READY);
        asset.setMimeType("image/png"); asset.setSizeBytes(1L); asset.setWidth(640); asset.setHeight(480); asset.setCreatedBy(owner);
        assets.saveAndFlush(asset); storage.sources.put(asset.getKey(), "A"); return id;
    }
    private CreateQuestionRequest request(UUID image) {
        var r = new CreateQuestionRequest(); r.setType(QuestionType.TRUE_FALSE); r.setDifficulty(Difficulty.EASY);
        r.setQuestionText("Is this the saved image?"); r.setContent(new ObjectMapper().createObjectNode().put("answer", true));
        r.setAttachmentAssetId(image); r.setQuizIds(List.of(quizId)); return r;
    }
    private UpdateQuestionRequest update(UUID image) {
        var r = new UpdateQuestionRequest(); r.setType(QuestionType.TRUE_FALSE);
        r.setContent(new ObjectMapper().createObjectNode().put("answer", true)); r.setAttachmentAssetId(image); return r;
    }
    private static void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null, List.of()));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static class Gate { final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); }
    @org.aspectj.lang.annotation.Aspect
    static class BindingFailure {
        final JdbcTemplate sql;
        volatile boolean armed;
        int expectedBindings = 1;
        final AtomicBoolean reached = new AtomicBoolean();
        BindingFailure(JdbcTemplate sql) { this.sql = sql; }
        @org.aspectj.lang.annotation.Around("execution(* uk.gegc.quizmaker.features.media.application.RetainedImageService.bind(..))")
        public Object afterRealWrites(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            if (armed) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM saved_image_bindings", Integer.class)).isEqualTo(expectedBindings);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM questions", Integer.class)).isEqualTo(1);
                reached.set(true);
                throw new IllegalStateException("injected after binding flush");
            }
            return result;
        }
    }
    static class FakeStorage implements SavedImageStorage {
        final Map<String, String> sources = new ConcurrentHashMap<>(), objects = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger(), copies = new AtomicInteger();
        volatile Gate gate; volatile boolean unavailable, loseResponse;
        void reset() { sources.clear(); objects.clear(); calls.set(0); copies.set(0); gate = null; unavailable = false; loseResponse = false; }
        private void boundary() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); calls.incrementAndGet();
            if (unavailable) throw new SavedImageException(SavedImageException.Reason.UNAVAILABLE);
        }
        public Source inspect(String key) {
            boundary(); String content = sources.get(key);
            if (content == null) throw new SavedImageException(SavedImageException.Reason.UNAVAILABLE);
            return new Source(content, content.length(), "image/png");
        }
        public void retain(SavedImageVersion v) {
            boundary(); Gate g = gate;
            if (g != null) { g.entered.countDown(); await(g.release); }
            if (!objects.containsKey(v.getObjectKey())) {
                String source = sources.get(v.getSourceKey());
                if (!Objects.equals(source, v.getSourceEtag())) throw new SavedImageException(SavedImageException.Reason.CONFLICT);
                if (objects.putIfAbsent(v.getObjectKey(), source) == null) copies.incrementAndGet();
            }
            if (loseResponse) { loseResponse = false; throw new SavedImageException(SavedImageException.Reason.UNAVAILABLE); }
            verify(v);
        }
        public void verify(SavedImageVersion v) {
            boundary();
            if (!Objects.equals(objects.get(v.getObjectKey()), v.getSourceEtag())) throw new SavedImageException(SavedImageException.Reason.UNAVAILABLE);
        }
        String content(String key) { return objects.get(key); }
    }
}
