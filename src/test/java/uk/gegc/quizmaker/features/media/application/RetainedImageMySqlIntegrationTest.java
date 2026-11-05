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
        @Bean BindingFailure bindingFailure(JdbcTemplate sql, jakarta.persistence.EntityManager em) { return new BindingFailure(sql, em); }
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


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void deletionRechecksFinalizedImageUnderLock(boolean osiv) throws Exception {
        tx.executeWithoutResult(s -> assets.findById(assetId).orElseThrow().setStatus(MediaAssetStatus.UPLOADING));
        org.mockito.Mockito.when(unusedLegacyStorage.headObject(org.mockito.ArgumentMatchers.any(
                software.amazon.awssdk.services.s3.model.HeadObjectRequest.class))).thenReturn(
                software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder()
                        .contentLength(1L).contentType("image/png").eTag("A").build());
        Gate gate = new Gate(); failure.deletionGate = gate;
        var worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "image-removal-race"));
        try {
            var removal = worker.submit(() -> {
                if (osiv) withRequestPersistenceContext(() -> { library.delete(assetId, username); return null; });
                else library.delete(assetId, username);
                return null;
            });
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            library.finalizeUpload(assetId,
                    new uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest(640, 480, null), username);
            UUID question = questionService.createQuestion(username, request(assetId));
            var saved = acquire(question);
            gate.release.countDown(); removal.get(10, TimeUnit.SECONDS);
            var asset = assets.findById(assetId).orElseThrow();
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.READY);
            assertThat(asset.isLibraryRemoved()).isTrue();
            assertThat(library.getByIdForResolution(assetId)).isPresent();
            assertThat(acquire(question)).isEqualTo(saved);
            assertThat(questionService.getQuestion(question, SecurityContextHolder.getContext().getAuthentication())
                    .getAttachment().cdnUrl()).endsWith(saved.objectKey());
            assertThat(storage.content(saved.objectKey())).isEqualTo("A");
            org.mockito.Mockito.verify(unusedLegacyStorage, org.mockito.Mockito.never()).deleteObject(
                    org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
        } finally {
            gate.release.countDown(); failure.deletionGate = null;
            worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void deletionBeforeFinalizationCannotBecomeASavedImage() {
        tx.executeWithoutResult(s -> assets.findById(assetId).orElseThrow().setStatus(MediaAssetStatus.UPLOADING));
        library.delete(assetId, username);
        assertThat(assets.findById(assetId).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.DELETED);
        assertThatThrownBy(() -> library.finalizeUpload(assetId,
                new uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest(640, 480, null), username))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> questionService.createQuestion(username, request(assetId))).isInstanceOf(ValidationException.class);
        assertThat(questions.count()).isZero(); assertThat(bindings.count()).isZero();
        verifyNoInteractions(unusedLegacyStorage);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unchangedLegacyReferenceAdoptsRetainedVersionWithoutGrantingNewSelection(boolean inline) {
        UUID question = legacyQuestion(inline);
        assertThat(bindings.findAllByQuestionId(question)).isEmpty();
        assertThat(versions.findByAssetId(assetId)).isEmpty();
        library.delete(assetId, username);
        var version = versions.findByAssetId(assetId).orElseThrow();
        int providerCalls = storage.calls.get();
        storage.sources.put("source/" + assetId, "B"); storage.unavailable = true;
        var edit = update(inline ? null : assetId);
        if (inline) edit.setContent(legacyContent(true));
        edit.setQuestionText("Legacy draft edited after removal");
        var edited = questionService.updateQuestion(username, question, edit);
        assertThat(edited.getQuestionText()).isEqualTo(edit.getQuestionText());
        var binding = bindings.findAllByQuestionId(question);
        assertThat(binding).hasSize(1);
        assertThat(binding.get(0).getImageVersionId()).isEqualTo(version.getId());
        assertThat(storage.content(version.getObjectKey())).isEqualTo("A");
        assertThat(storage.calls.get()).isEqualTo(providerCalls);
        questionService.updateQuestion(username, question, edit);
        assertThat(bindings.findAllByQuestionId(question).get(0).getId()).isEqualTo(binding.get(0).getId());
        assertThatThrownBy(() -> questionService.createQuestion(username, request(assetId))).isInstanceOf(ValidationException.class);
        var clear = update(null); clear.setClearAttachment(true);
        questionService.updateQuestion(username, question, clear);
        assertThatThrownBy(() -> questionService.updateQuestion(username, question, edit)).isInstanceOf(ValidationException.class);
        assertThat(questions.findById(question).orElseThrow().getAttachmentAssetId()).isNull();
        assertThat(questions.findById(question).orElseThrow().getContent()).doesNotContain(assetId.toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cachedQuestionCannotReattachAnImageDetachedByAnotherTransaction(boolean legacy) throws Exception {
        UUID question = legacy ? legacyQuestion(false) : questionService.createQuestion(username, request(assetId));
        library.delete(assetId, username);
        var worker = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                assertThat(questions.findById(question).orElseThrow().getAttachmentAssetId()).isEqualTo(assetId);
                try {
                    worker.submit(() -> {
                        var clear = update(null); clear.setClearAttachment(true);
                        questionService.updateQuestion(username, question, clear);
                        assertThat(questions.findById(question).orElseThrow().getAttachmentAssetId()).isNull();
                    }).get(10, TimeUnit.SECONDS);
                } catch (Exception e) { throw new AssertionError(e); }
                var edit = update(assetId); edit.setQuestionText("Stale tab tries to restore A");
                questionService.updateQuestion(username, question, edit);
            })).isInstanceOf(ValidationException.class);
            var stored = questions.findById(question).orElseThrow();
            assertThat(stored.getAttachmentAssetId()).isNull();
            assertThat(stored.getQuestionText()).isNotEqualTo("Stale tab tries to restore A");
            assertThat(bindings.findAllByQuestionId(question)).hasSize(legacy ? 0 : 1);
            library.restore(assetId, username);
            questionService.updateQuestion(username, question, update(assetId));
            assertThat(acquire(question).assetId()).isEqualTo(assetId);
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void refreshingQuestionPreservesUnrelatedWritesInCallerTransaction() {
        UUID question = questionService.createQuestion(username, request(assetId));
        library.delete(assetId, username);
        UUID categoryId = tx.execute(s -> {
            questions.findById(question).orElseThrow();
            var category = categories.findAll().get(0);
            category.setDescription("Unrelated caller update");
            var edit = update(assetId); edit.setQuestionText("Question updated in caller transaction");
            questionService.updateQuestion(username, question, edit);
            return category.getId();
        });
        assertThat(categories.findById(categoryId).orElseThrow().getDescription()).isEqualTo("Unrelated caller update");
        assertThat(questions.findById(question).orElseThrow().getQuestionText()).isEqualTo("Question updated in caller transaction");
        assertThat(acquire(question).assetId()).isEqualTo(assetId);
    }

    @Test void legacyBindingAdoptionRollsBackWithTheQuestionAndCanBeRetried() {
        UUID question = legacyQuestion(false);
        library.delete(assetId, username);
        UUID version = versions.findByAssetId(assetId).orElseThrow().getId();
        var edit = update(assetId); edit.setQuestionText("Legacy edit with new binding");
        failure.armed = true;
        assertThatThrownBy(() -> questionService.updateQuestion(username, question, edit))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected after binding flush");
        assertThat(failure.reached).isTrue();
        assertThat(bindings.findAllByQuestionId(question)).isEmpty();
        assertThat(questions.findById(question).orElseThrow().getQuestionText()).isEqualTo("Legacy draft");
        assertThat(versions.findByAssetId(assetId).orElseThrow().getId()).isEqualTo(version);
        failure.armed = false;
        questionService.updateQuestion(username, question, edit);
        assertThat(acquire(question).versionId()).isEqualTo(version);
        assertThat(questions.findById(question).orElseThrow().getQuestionText()).isEqualTo(edit.getQuestionText());
    }

    @Test void cachedQuestionDeletedByAnotherTransactionCannotBeEdited() throws Exception {
        UUID question = questionService.createQuestion(username, request(assetId));
        library.delete(assetId, username);
        var worker = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                questions.findById(question).orElseThrow();
                try {
                    worker.submit(() -> questionService.deleteQuestion(username, question)).get(10, TimeUnit.SECONDS);
                } catch (Exception e) { throw new AssertionError(e); }
                questionService.updateQuestion(username, question, update(assetId));
            })).isInstanceOf(ResourceNotFoundException.class);
            assertThat(questions.findById(question)).isEmpty();
            assertThat(bindings.findAllByQuestionId(question)).hasSize(1);
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }


    @Autowired uk.gegc.quizmaker.features.tag.domain.repository.TagRepository importTags;
    @Autowired QuestionHandlerFactory importHandlers;
    @Autowired uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository importPermissions;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void importingRemovedImageCannotCreateALegacyGrant(boolean foreignOwner) throws Exception {
        library.delete(assetId, username);
        if (foreignOwner) {
            username = "import-" + UUID.randomUUID().toString().substring(0, 12);
            tx.executeWithoutResult(s -> {
                var importer = new User(); importer.setUsername(username); importer.setEmail(username + "@example.invalid");
                importer.setHashedPassword("test-only"); importer.setActive(true); importer.setRoles(new HashSet<>());
                importer.setCreatedAt(LocalDateTime.of(2026, 10, 23, 12, 0)); importer.setPasswordChangedAt(importer.getCreatedAt());
                users.saveAndFlush(importer);
            });
            authenticate(username);
        }
        grantImportPermission();
        var result = importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null);
        assertThat(result.created()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(questions.count()).isZero();
        assertThat(quizzes.count()).isEqualTo(1);
        assertThat(bindings.count()).isZero();
        assertThat(result.errors().get(0).code()).isEqualTo(foreignOwner ? "ForbiddenException" : "ValidationException");
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(UpsertStrategy.class)
    void authorizedImportSurvivesLaterRemovalAndCanAcquireItsBinding(UpsertStrategy strategy) throws Exception {
        grantImportPermission();
        var result = importImages(List.of(assetId), false, strategy,
                strategy == UpsertStrategy.UPSERT_BY_ID ? quizId : null);
        assertThat(result.errors()).isEmpty();
        assertThat(result.created() + result.updated()).isEqualTo(1);
        UUID question = questions.findAll().get(0).getId();
        library.delete(assetId, username);
        questionService.updateQuestion(username, question, update(assetId));
        assertThat(storage.content(acquire(question).objectKey())).isEqualTo("A");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(UpsertStrategy.class)
    void everyImportStrategyRejectsRemovedImageSelections(UpsertStrategy strategy) throws Exception {
        grantImportPermission(); library.delete(assetId, username);
        var result = importImages(List.of(assetId), false, strategy,
                strategy == UpsertStrategy.UPSERT_BY_ID ? UUID.randomUUID() : null);
        assertThat(result.failed()).isEqualTo(1); assertThat(result.created()).isZero();
        assertThat(result.errors().get(0).code()).isEqualTo("ValidationException");
        assertThat(questions.count()).isZero(); assertThat(bindings.count()).isZero();
    }

    @Test void dryRunChecksImageRightsWithoutCreatingRowsOrCallingStorage() throws Exception {
        grantImportPermission();
        assertThat(importImages(List.of(assetId), true, UpsertStrategy.CREATE_ONLY, null).failed()).isZero();
        assertThat(questions.count()).isZero(); assertThat(versions.count()).isZero();
        assertThat(storage.calls.get()).isZero();
        library.delete(assetId, username);
        int calls = storage.calls.get();
        var denied = importImages(List.of(assetId), true, UpsertStrategy.CREATE_ONLY, null);
        assertThat(denied.failed()).isEqualTo(1); assertThat(denied.created()).isZero();
        assertThat(denied.errors().get(0).code()).isEqualTo("ValidationException");
        assertThat(questions.count()).isZero(); assertThat(bindings.count()).isZero();
        assertThat(storage.calls.get()).isEqualTo(calls);
    }

    @Test void unavailableImageInSkippedDuplicateDoesNotChangeSkipSemantics() throws Exception {
        grantImportPermission(); library.delete(assetId, username);
        var result = importImages(List.of(assetId), false, UpsertStrategy.SKIP_ON_DUPLICATE, quizId);
        assertThat(result.skipped()).isEqualTo(1); assertThat(result.errors()).isEmpty();
        assertThat(questions.count()).isZero(); assertThat(bindings.count()).isZero();
    }

    @Test void availableForeignImageRequiresTheExistingMediaAdministratorPermission() throws Exception {
        grantImportPermission();
        tx.executeWithoutResult(s -> assets.findById(assetId).orElseThrow().setCreatedBy("other-owner"));
        var denied = importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null);
        assertThat(denied.failed()).isEqualTo(1);
        assertThat(denied.errors().get(0).code()).isEqualTo("ForbiddenException");
        assertThat(questions.count()).isZero();
        tx.executeWithoutResult(s -> {
            var permission = importPermissions.findByPermissionName("MEDIA_ADMIN").orElseGet(() -> {
                var added = new uk.gegc.quizmaker.features.user.domain.model.Permission();
                added.setPermissionName("MEDIA_ADMIN"); added.setResource("media"); added.setAction("admin");
                return importPermissions.saveAndFlush(added);
            });
            var role = new uk.gegc.quizmaker.features.user.domain.model.Role();
            role.setRoleName("import-admin-" + UUID.randomUUID().toString().substring(0, 12)); role.setPermissions(Set.of(permission));
            users.findByUsername(username).orElseThrow().getRoles().add(roles.saveAndFlush(role));
        });
        assertThat(importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null).created()).isEqualTo(1);
        library.delete(assetId, username);
        assertThat(importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null).failed()).isEqualTo(1);
    }

    @Test void inlineImageSelectionsAreCheckedAndPartialImportKeepsOnlyTheValidQuiz() throws Exception {
        grantImportPermission(); library.delete(assetId, username);
        UUID available = tx.execute(s -> insertAsset(username));
        var denied = importDto(List.of(assetId), null, "Invalid inline image", true);
        var allowed = importDto(List.of(available), null, "Valid inline image", true);
        var result = importBatch(List.of(denied, allowed), QuizImportOptions.defaults(10));
        assertThat(result.failed()).isEqualTo(1); assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors().get(0).index()).isZero();
        assertThat(questions.findAll()).hasSize(1).allSatisfy(question -> {
            assertThat(question.getContent()).contains(available.toString()).doesNotContain(assetId.toString());
        });
        assertThat(quizzes.count()).isEqualTo(2); assertThat(bindings.count()).isZero();
    }

    @Test void importFailureAfterActualQuestionWritesRollsBackTheWholeQuiz() throws Exception {
        grantImportPermission(); failure.failImport = true;
        try {
            var result = importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null);
            assertThat(result.failed()).isEqualTo(1); assertThat(result.created()).isZero();
            assertThat(failure.importFailureReached).isTrue();
            assertThat(questions.count()).isZero(); assertThat(quizzes.count()).isEqualTo(1);
            assertThat(bindings.count()).isZero();
        } finally { failure.failImport = false; }
        assertThat(importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null).created()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void importReadsCurrentMediaStateEvenWithAnOsivCachedEntity(boolean changeOwner) throws Exception {
        grantImportPermission(); images.prepare(Set.of(assetId), username);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = withRequestPersistenceContext(() -> {
                library.getByIdForValidation(assetId, username);
                worker.submit(() -> {
                    if (changeOwner) tx.executeWithoutResult(s -> assets.findById(assetId).orElseThrow().setCreatedBy("other-owner"));
                    else library.delete(assetId, username);
                }).get(10, TimeUnit.SECONDS);
                return importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null);
            });
            assertThat(result.failed()).isEqualTo(1); assertThat(questions.count()).isZero();
            assertThat(result.errors().get(0).code()).isEqualTo(changeOwner ? "ForbiddenException" : "ValidationException");
            assertThat(bindings.count()).isZero();
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void importHoldsAssetLocksUntilItsQuestionWriteCommits() throws Exception {
        grantImportPermission(); images.prepare(Set.of(assetId), username);
        Gate gate = new Gate(); failure.importGate = gate;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var imported = workers.submit(() -> {
                authenticate(username);
                try { return importImages(List.of(assetId), false, UpsertStrategy.CREATE_ONLY, null); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var removed = workers.submit(() -> library.delete(assetId, username));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean blocked = false;
            while (System.nanoTime() < until) {
                blocked = admin.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = 'media_assets'
                        """, Integer.class, schema) > 0;
                if (blocked) break;
                Thread.sleep(10);
            }
            assertThat(blocked).as("Removal must actually wait on the importing transaction's image lock").isTrue();
            gate.release.countDown();
            assertThat(imported.get(10, TimeUnit.SECONDS).created()).isEqualTo(1);
            removed.get(10, TimeUnit.SECONDS);
            UUID question = questions.findAll().get(0).getId();
            assertThat(assets.findById(assetId).orElseThrow().isLibraryRemoved()).isTrue();
            questionService.updateQuestion(username, question, update(assetId));
            assertThat(storage.content(acquire(question).objectKey())).isEqualTo("A");
        } finally {
            gate.release.countDown(); failure.importGate = null;
            workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void importImageSelectionQueriesAreBatchedForFiveAndTenDistinctAssets() {
        grantImportPermission();
        var ids = tx.execute(s -> java.util.stream.IntStream.range(0, 10).mapToObj(i -> insertAsset(username)).toList());
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        tx.executeWithoutResult(s -> images.validateNewSelections(ids.subList(0, 5), username));
        long five = stats.getPrepareStatementCount();
        stats.clear();
        tx.executeWithoutResult(s -> images.validateNewSelections(ids, username));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(five).isLessThanOrEqualTo(3);
        assertThat(storage.calls.get()).isZero();
    }

    private void grantImportPermission() {
        tx.executeWithoutResult(s -> users.findByUsername(username).orElseThrow().getRoles()
                .add(roles.findByRoleNameWithPermissions("ROLE_QUIZ_CREATOR").orElseThrow()));
        assertThat(permissions.hasPermission(uk.gegc.quizmaker.features.user.domain.model.PermissionName.QUIZ_CREATE)).isTrue();
        assertThat(permissions.hasPermission(uk.gegc.quizmaker.features.user.domain.model.PermissionName.MEDIA_ADMIN)).isFalse();
    }

    private uk.gegc.quizmaker.features.quiz.application.imports.QuizImportService importer() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var policy = new uk.gegc.quizmaker.shared.security.AccessPolicy(permissions, null);
        return new uk.gegc.quizmaker.features.quiz.application.imports.impl.QuizImportServiceImpl(
                new uk.gegc.quizmaker.features.quiz.application.imports.ImportParserFactory(
                        new uk.gegc.quizmaker.features.quiz.application.imports.impl.JsonImportParser(mapper), null),
                new uk.gegc.quizmaker.features.quiz.application.validation.QuizImportValidationServiceImpl(importHandlers, users, policy),
                new uk.gegc.quizmaker.features.quiz.application.imports.impl.ReferenceResolutionServiceImpl(categories, importTags),
                new uk.gegc.quizmaker.features.quiz.infra.mapping.QuizImportAssembler(),
                new uk.gegc.quizmaker.features.question.infra.mapping.QuestionImportMapper(mapper),
                new uk.gegc.quizmaker.features.quiz.application.imports.ContentHashUtil(mapper),
                quizzes, questions, categories, importTags,
                new uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties(), users, policy, manager,
                new uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator(),
                new uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper(), images);
    }

    private uk.gegc.quizmaker.features.quiz.api.dto.imports.ImportSummaryDto importImages(
            List<UUID> ids, boolean dryRun, UpsertStrategy strategy, UUID id) throws Exception {
        return importBatch(List.of(importDto(ids, id, "Imported image quiz", false)),
                new QuizImportOptions(strategy, dryRun, false, false, 10));
    }

    private uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto importDto(
            List<UUID> ids, UUID id, String title, boolean inline) {
        var imageQuestions = ids.stream().map(image -> {
            var content = legacyContent(false);
            if (inline) content.putArray("illustrations").addObject().putObject("media").put("assetId", image.toString());
            return new uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto(
                    null, QuestionType.TRUE_FALSE, Difficulty.EASY, "Imported question", content,
                    null, null, null, inline ? null : new uk.gegc.quizmaker.shared.dto.MediaRefDto(image, null, null, null, null, null, null));
        }).toList();
        return new uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto(
                1, id, title, "New imported copy", Visibility.PRIVATE,
                Difficulty.EASY, 5, List.of(), categories.findAll().get(0).getName(), null, imageQuestions, null, null);
    }

    private uk.gegc.quizmaker.features.quiz.api.dto.imports.ImportSummaryDto importBatch(
            List<uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto> input, QuizImportOptions options) throws Exception {
        return importer().importQuizzes(new java.io.ByteArrayInputStream(new ObjectMapper().findAndRegisterModules()
                        .writeValueAsBytes(input)), ExportFormat.JSON_EDITABLE, options,
                SecurityContextHolder.getContext().getAuthentication());
    }

    private UUID legacyQuestion(boolean inline) {
        return tx.execute(s -> {
            var question = new Question(); question.setType(QuestionType.TRUE_FALSE); question.setDifficulty(Difficulty.EASY);
            question.setQuestionText("Legacy draft"); question.setContent(legacyContent(inline).toString());
            if (!inline) question.setAttachmentAssetId(assetId);
            question.setQuizId(List.of(quizzes.findById(quizId).orElseThrow()));
            return questions.saveAndFlush(question).getId();
        });
    }

    private com.fasterxml.jackson.databind.node.ObjectNode legacyContent(boolean inline) {
        var content = new ObjectMapper().createObjectNode().put("answer", true);
        if (inline) content.putObject("media").put("assetId", assetId.toString());
        return content;
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
        volatile Gate deletionGate;
        @org.aspectj.lang.annotation.Around("execution(* uk.gegc.quizmaker.features.media.domain.repository.MediaAssetRepository.findByIdAndStatusNot(..))")
        public Object afterDeleteRead(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            Gate gate = deletionGate;
            if (gate != null && Thread.currentThread().getName().equals("image-removal-race")) {
                assertThat(((java.util.Optional<MediaAsset>) result).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.UPLOADING);
                gate.entered.countDown(); await(gate.release);
            }
            return result;
        }
        int expectedBindings = 1;
        final AtomicBoolean reached = new AtomicBoolean();
        final jakarta.persistence.EntityManager em;
        volatile Gate importGate;
        volatile boolean failImport;
        final AtomicBoolean importFailureReached = new AtomicBoolean();
        BindingFailure(JdbcTemplate sql, jakarta.persistence.EntityManager em) { this.sql = sql; this.em = em; }
        @org.aspectj.lang.annotation.Around("execution(* uk.gegc.quizmaker.features.media.application.RetainedImageService.validateNewSelections(..))")
        public Object afterImportSelectionCheck(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            Gate gate = importGate;
            if (gate != null) { gate.entered.countDown(); await(gate.release); }
            return result;
        }
        @org.aspectj.lang.annotation.Around("execution(* org.springframework.data.repository.CrudRepository.save(..)) && target(uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository)")
        public Object afterImportWrite(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            if (failImport) {
                em.flush();
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM questions", Integer.class)).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quizzes", Integer.class)).isEqualTo(2);
                importFailureReached.set(true);
                throw new IllegalStateException("injected after imported question flush");
            }
            return result;
        }
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
