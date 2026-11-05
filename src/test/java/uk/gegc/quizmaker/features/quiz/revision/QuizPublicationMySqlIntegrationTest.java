package uk.gegc.quizmaker.features.quiz.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import uk.gegc.quizmaker.features.attempt.application.FixedFormService;
import uk.gegc.quizmaker.features.attempt.application.impl.FixedFormServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFixedFormRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.media.application.*;
import uk.gegc.quizmaker.features.media.application.impl.*;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.*;
import uk.gegc.quizmaker.features.media.domain.repository.MediaAssetRepository;
import uk.gegc.quizmaker.features.media.infra.mapping.MediaAssetMapper;
import uk.gegc.quizmaker.features.question.api.dto.*;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionServiceImpl;
import uk.gegc.quizmaker.features.question.application.impl.QuestionSourceConfirmationServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionMediaResolver;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.impl.QuizRelationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionPublicationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;
import uk.gegc.quizmaker.features.quiz.infra.repository.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.AccessPolicy;
import uk.gegc.quizmaker.shared.security.NoOpMembershipResolver;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.Reason.*;

/** Real authoring, media retention, authorization, publication and MySQL; only object storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.connection.isolation=4", "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, QuizRelationServiceImpl.class, AccessPolicy.class, NoOpMembershipResolver.class,
        uk.gegc.quizmaker.features.quiz.application.generation.impl.QuizAssemblyServiceImpl.class,
        PublicationContent.class, RetainedImageServiceImpl.class, MediaAssetServiceImpl.class, MediaAssetMapper.class,
        QuestionServiceImpl.class, QuestionSourceConfirmationServiceImpl.class, QuestionMediaResolver.class, QuestionHandlerFactory.class, AppPermissionEvaluator.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        FixedFormServiceImpl.class, JdbcFixedFormRepository.class, QuizPublicationMySqlIntegrationTest.Config.class})
class QuizPublicationMySqlIntegrationTest {
    static final String SCHEMA = "publication_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    static boolean schemaCreated;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true;
        properties.add("spring.datasource.url", () -> base + SCHEMA + options);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
    }
    @AfterAll static void dropDatabase() { if (schemaCreated) admin.execute("DROP DATABASE " + SCHEMA); }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaStorageProperties properties() { return new MediaStorageProperties(); }
        @Bean Storage storage() { return new Storage(); }
        @Bean Faults faults(JdbcTemplate sql) { return new Faults(sql); }
        @Bean static BeanPostProcessor countSql() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof CountingSource) ? new CountingSource(ds) : bean;
                }
            };
        }
    }
    @MockitoBean S3Client unusedStorage;
    @MockitoBean S3Presigner unusedPresigner;
    @MockitoBean uk.gegc.quizmaker.features.quiz.application.QuizFreshnessService freshness;
    @Autowired uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService assembly;
    @Autowired QuizRevisionPublicationService service;
    @Autowired QuizRelationService relations;
    @Autowired QuestionSourceScopeRepository questionSources;
    @Autowired QuestionService authoring;
    @Autowired QuestionSourceConfirmationService confirmations;
    @Autowired RetainedImageService images;
    @Autowired MediaAssetService library;
    @Autowired FixedFormService forms;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired MediaAssetRepository assets;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PublicationContent content;
    @Autowired Clock clock;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired Storage storage;
    @Autowired Faults faults;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagers;
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(transactions); storage.reset(); faults.reset();
        username = "pub-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username, true);
        quizId = tx.execute(status -> {
            var category = new Category(); category.setName(username); categories.saveAndFlush(category);
            var quiz = new Quiz(); quiz.setCreator(users.findById(ownerId).orElseThrow()); quiz.setCategory(category);
            quiz.setTitle("Original quiz"); quiz.setDescription("Synthetic publication"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
        authenticate(username);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext(); faults.reset(); CountingSource.enabled = false;
        tx.executeWithoutResult(status -> {
            // Owned schema only, in FK order. All durability assertions run before cleanup.
            for (String table : List.of("attempt_form_item", "attempt_form", "quiz_publication_receipts", "quiz_publication_heads",
                    "quiz_revision_images", "quiz_publications", "quiz_revision_items", "question_option_revisions",
                    "question_revisions", "quiz_revisions", "saved_image_bindings", "saved_image_versions", "quiz_questions",
                    "question_tags", "question_source_confirmations", "questions", "quizzes", "categories", "media_assets", "user_roles", "users"))
                sql.update("DELETE FROM " + table);
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'pub-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'pub-%'");
        });
    }

    @Test void firstPublicationReplayChangedDraftAndHistoryKeepOneAuthoritativeBoundary() {
        UUID question = question(QuestionType.MCQ_SINGLE, null);
        assertThat(service.current(quizId)).isEmpty(); assertThat(service.history(quizId, 0, 10)).isEmpty();
        var command = command(0, question);
        var first = service.publish(command);
        assertThat(first.publication().version()).isEqualTo(1);
        var original = service.read(quizId, first.publication().revision().id());
        var form = form(original);
        assertThat(forms.createOrReplay(ownerId, form)).isEqualTo(form);
        // The real mutable writer changes the next draft without modifying the stored content/form.
        edit(question, "Corrected draft");
        var replay = restarted().publish(command);
        assertThat(replay.replayed()).isTrue(); assertThat(replay.publication()).isEqualTo(first.publication());
        assertThat(service.current(quizId)).contains(first.publication());
        var next = service.publish(command(1, question));
        assertThat(next.publication().version()).isEqualTo(2);
        assertThat(next.publication().revision().id()).isNotEqualTo(first.publication().revision().id());
        assertThat(service.read(quizId, original.revision().id())).isEqualTo(original);
        assertThat(forms.findByIds(ownerId, List.of(form.id()))).containsExactly(form);
        assertThat(service.read(quizId, next.publication().revision().id()).content().items().get(0).question().questionText())
                .isEqualTo("Corrected draft");
        assertThat(service.publish(command).publication()).isEqualTo(first.publication());
        assertThat(service.current(quizId)).contains(next.publication());
        assertThat(service.history(quizId, 0, 1).getTotalElements()).isEqualTo(2);
        assertThat(service.history(quizId, 1, 1).getContent()).hasSize(1);
        assertThat(quizzes.findById(quizId).orElseThrow().getStatus()).isEqualTo(QuizStatus.DRAFT);
    }

    @Test void identicalContentReusesIdentityAndRepublishingAnOldRevisionAdvancesTheCounter() {
        UUID question = question(QuestionType.TRUE_FALSE, null);
        var first = service.publish(command(0, question));
        assertThat(service.publish(command(0, question)).publication()).isEqualTo(first.publication());
        assertThat(count("quiz_revisions")).isEqualTo(1);
        edit(question, "Second draft"); var second = service.publish(command(1, question));
        edit(question, "Synthetic question"); var back = service.publish(command(2, question));
        assertThat(back.publication().revision()).isEqualTo(first.publication().revision());
        assertThat(back.publication().version()).isEqualTo(3);
        assertThat(count("quiz_revisions")).isEqualTo(2);
        edit(question, "Third draft");
        assertReason(() -> service.publish(command(1, question)), STALE_PUBLICATION);
        assertThat(service.current(quizId)).contains(back.publication());
        assertThat(second.publication().revision().id()).isNotEqualTo(back.publication().revision().id());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentIdenticalPublicationsCommitOneRevision(boolean sameCommand) throws Exception {
        UUID q = question(QuestionType.MCQ_SINGLE, null); var first = command(0, q);
        var outcomes = race(first, sameCommand ? first : command(0, q));
        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(Result.class));
        assertThat(((Result) outcomes.get(0)).publication()).isEqualTo(((Result) outcomes.get(1)).publication());
        assertThat(count("quiz_revisions")).isEqualTo(1); assertThat(count("quiz_publication_heads")).isEqualTo(1);
        assertThat(count("quiz_publication_receipts")).isEqualTo(sameCommand ? 1 : 2);
    }

    @Test void competingDifferentAuthorOrdersHaveOneSuccessfulWinnerAndOneTypedConflict() throws Exception {
        UUID a = question(QuestionType.TRUE_FALSE, null), b = question(QuestionType.MCQ_SINGLE, null);
        var outcomes = race(command(0, a, b), command(0, b, a));
        assertThat(outcomes.stream().filter(Result.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(PublicationException.class::isInstance)).singleElement()
                .satisfies(e -> assertThat(((PublicationException) e).reason()).isEqualTo(STALE_PUBLICATION));
        var winner = (Result) outcomes.stream().filter(Result.class::isInstance).findFirst().orElseThrow();
        assertThat(service.current(quizId)).contains(winner.publication());
        assertThat(service.read(quizId, winner.publication().revision().id()).content().items()).hasSize(2);
        assertThat(count("quiz_revisions")).isEqualTo(1); assertThat(count("quiz_publication_receipts")).isEqualTo(1);
    }

    @Test void changedCommandIdentityConflictsWithoutChangingTheOriginalReceipt() {
        UUID a = question(QuestionType.TRUE_FALSE, null), b = question(QuestionType.TRUE_FALSE, null);
        var command = command(0, a, b); var first = service.publish(command);
        assertReason(() -> service.publish(new Command(1, quizId, command.commandId(), 0, List.of(b, a))), COMMAND_CONFLICT);
        assertReason(() -> service.publish(new Command(1, quizId, command.commandId(), 1, List.of(a, b))), COMMAND_CONFLICT);
        assertThat(service.publish(command).publication()).isEqualTo(first.publication());
        assertThat(count("quiz_publication_receipts")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"insert", "pointTo", "record"})
    void reachedPublicationWritesRollbackTogetherAndRetryAfterRestartReusesRetainedObject(String checkpoint) {
        UUID image = image(username); UUID q = question(QuestionType.TRUE_FALSE, image);
        String retainedKey = sql.queryForObject("SELECT object_key FROM saved_image_versions", String.class);
        var command = command(0, q); faults.fail = checkpoint;
        assertReason(() -> service.publish(command), PERSISTENCE_UNAVAILABLE);
        assertThat(faults.reached).isTrue();
        for (String table : List.of("quiz_revisions", "question_revisions", "quiz_publications", "quiz_revision_images",
                "quiz_publication_heads", "quiz_publication_receipts")) assertThat(count(table)).as(table).isZero();
        assertThat(count("saved_image_bindings")).isEqualTo(1); assertThat(storage.objects.get(retainedKey)).isEqualTo("A");
        faults.fail = null;
        var result = restarted().publish(command);
        assertThat(service.current(quizId)).contains(result.publication());
        assertThat(service.read(quizId, result.publication().revision().id()).images()).hasSize(1);
        assertThat(storage.copies.get()).isEqualTo(1); assertThat(count("quiz_publication_receipts")).isEqualTo(1);
    }

    @Test void lostCommitAcknowledgementHasAnUnknownOutcomeAndDurableReplay() {
        UUID q = question(QuestionType.TRUE_FALSE, null); var command = command(0, q);
        faults.loseAcknowledgement = true;
        assertReason(() -> service.publish(command), UNKNOWN_OUTCOME);
        assertThat(faults.reached).isTrue(); assertThat(count("quiz_publication_receipts")).isEqualTo(1);
        faults.loseAcknowledgement = false;
        var replay = restarted().publish(command);
        assertThat(replay.replayed()).isTrue(); assertThat(service.current(quizId)).contains(replay.publication());
        assertThat(count("quiz_revisions")).isEqualTo(1);
    }

    @Test void failedReplacementRestoresThePreviousPointerAndItsCompleteContent() {
        UUID q = question(QuestionType.TRUE_FALSE, image(username));
        var original = service.publish(command(0, q));
        var originalContent = service.read(quizId, original.publication().revision().id());
        edit(q, "Edited draft awaiting publication");
        var replacement = command(1, q); faults.fail = "pointTo";
        assertReason(() -> service.publish(replacement), PERSISTENCE_UNAVAILABLE);
        assertThat(faults.reached).isTrue();
        assertThat(restarted().current(quizId)).contains(original.publication());
        assertThat(restarted().read(quizId, original.publication().revision().id())).isEqualTo(originalContent);
        assertThat(count("quiz_revisions")).isEqualTo(1);
        assertThat(count("quiz_publication_receipts")).isEqualTo(1);
        faults.fail = null;
        var committed = restarted().publish(replacement);
        assertThat(committed.publication().version()).isEqualTo(2);
        assertThat(service.current(quizId)).contains(committed.publication());
        assertThat(count("quiz_revisions")).isEqualTo(2);
    }

    @Test void removedImagePublishesItsExactBoundGenerationAndOldFormSurvivesLaterEditing() {
        UUID image = image(username); UUID q = question(QuestionType.TRUE_FALSE, image);
        var first = service.publish(command(0, q)); var original = service.read(quizId, first.publication().revision().id());
        var form = form(original); forms.createOrReplay(ownerId, form);
        library.delete(image, username);
        storage.sources.put("source/" + image, "B");
        edit(q, "Later question with retained A");
        int calls = storage.calls.get(); var second = service.publish(command(1, q));
        assertThat(storage.calls.get()).isEqualTo(calls); // The publication transaction performs no storage I/O.
        var oldImage = original.images().get(0);
        assertThat(service.read(quizId, second.publication().revision().id()).images().get(0).versionId()).isEqualTo(oldImage.versionId());
        assertThat(storage.objects.get(oldImage.objectKey())).isEqualTo("A");
        assertThat(service.read(quizId, original.revision().id())).isEqualTo(original);
        assertThat(forms.findByIds(ownerId, List.of(form.id()))).containsExactly(form);
        assertThat(library.search(null, null, 0, 10, null, null, username).getContent()).isEmpty();
        assertThatThrownBy(() -> question(QuestionType.TRUE_FALSE, image)).isInstanceOf(RuntimeException.class);
        assertThat(questions.count()).isEqualTo(1);
    }

    @Test void removalAndPublicationBothSucceedWithoutADanglingBinding() throws Exception {
        UUID image = image(username); UUID q = question(QuestionType.TRUE_FALSE, image);
        faults.holdPublication = new Gate(); var pool = Executors.newFixedThreadPool(2);
        try {
            var publication = pool.submit(() -> asOwner(() -> service.publish(command(0, q))));
            await(faults.holdPublication.entered);
            var removal = pool.submit(() -> asOwner(() -> { library.delete(image, username); return true; }));
            assertThat(removal.get(15, TimeUnit.SECONDS)).isTrue();
            faults.holdPublication.release.countDown();
            var published = publication.get(15, TimeUnit.SECONDS);
            var stored = service.read(quizId, published.publication().revision().id());
            assertThat(stored.images()).hasSize(1);
            assertThat(storage.objects.get(stored.images().get(0).objectKey())).isEqualTo("A");
            assertThat(assets.findById(image).orElseThrow().isLibraryRemoved()).isTrue();
        } finally {
            faults.holdPublication.release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void missingOrForeignImageAuthorityAndExternalUrlsCannotCreatePublicationEvidence() {
        UUID q = question(QuestionType.TRUE_FALSE, null);
        var control = service.publish(command(0, q));
        UUID image = image("another-owner");
        // A legacy source may contain an unbound foreign asset. An asset ID is not a retained-use grant.
        tx.executeWithoutResult(s -> sql.update("UPDATE questions SET attachment_asset_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", image.toString(), q.toString()));
        assertReason(() -> service.publish(command(1, q)), IMAGE_UNAVAILABLE);
        tx.executeWithoutResult(s -> sql.update("UPDATE questions SET attachment_asset_id=NULL,attachment_url='https://example.invalid/image.png' WHERE id=UUID_TO_BIN(?)", q.toString()));
        assertReason(() -> service.publish(command(1, q)), IMAGE_UNAVAILABLE);
        assertThat(storage.calls.get()).isZero(); assertThat(service.current(quizId)).contains(control.publication());
        assertThat(count("quiz_revisions")).isEqualTo(1); assertThat(count("quiz_revision_images")).isZero();
    }

    @Test void authenticationCurrentPermissionsOwnershipAndPublicModerationAreIndependent() {
        UUID q = question(QuestionType.TRUE_FALSE, null); var command = command(0, q);
        var control = service.publish(command);
        SecurityContextHolder.clearContext(); assertReason(() -> service.publish(command), UNAUTHENTICATED);
        user("pub-stranger", true); authenticate("pub-stranger");
        assertReason(() -> service.publish(command), UNAVAILABLE);
        assertReason(() -> service.history(quizId, 0, 10), UNAVAILABLE);
        assertReason(() -> service.read(quizId, control.publication().revision().id()), UNAVAILABLE);
        authenticate(username);
        tx.executeWithoutResult(s -> sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        assertReason(() -> service.publish(command), UNAVAILABLE);
        grant(ownerId, "QUIZ_UPDATE");
        assertThat(service.publish(command).replayed()).isTrue();
        tx.executeWithoutResult(s -> sql.update("UPDATE quizzes SET visibility='PUBLIC' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString()));
        assertReason(() -> service.publish(command), UNAVAILABLE);
        grant(ownerId, "QUIZ_MODERATE"); assertThat(service.publish(command).replayed()).isTrue();
        tx.executeWithoutResult(s -> sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        assertReason(() -> service.publish(command), UNAVAILABLE);
        assertThat(count("quiz_publication_receipts")).isEqualTo(1);
    }

    @Test void staleOsivAuthorityCannotPublishOrReadAfterPermissionRevocation() {
        UUID q = question(QuestionType.TRUE_FALSE, null); var command = command(0, q); service.publish(command);
        var em = entityManagers.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagers, new org.springframework.orm.jpa.EntityManagerHolder(em));
        try {
            tx.executeWithoutResult(s -> users.findByIdWithRolesAndPermissions(ownerId).orElseThrow().getRoles().size());
            sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
            assertReason(() -> service.publish(command), UNAVAILABLE);
            assertReason(() -> service.current(quizId), UNAVAILABLE);
        } finally { TransactionSynchronizationManager.unbindResource(entityManagers); em.close(); }
    }

    @Test void exactMembershipValidationAndCallerTransactionsCannotLeavePartialWrites() {
        UUID a = question(QuestionType.TRUE_FALSE, null), b = question(QuestionType.TRUE_FALSE, null);
        assertReason(() -> service.publish(command(0, a)), INVALID_CONTENT);
        assertReason(() -> service.publish(command(0, a, UUID.randomUUID())), INVALID_CONTENT);
        assertReason(() -> tx.execute(s -> service.publish(command(0, a, b))), TRANSACTION_NOT_ALLOWED);
        assertThat(count("quiz_revisions")).isZero();
        var good = service.publish(command(0, a, b));
        assertReason(() -> service.publish(command(99, a, b)), STALE_PUBLICATION);
        assertThat(service.current(quizId)).contains(good.publication());
    }

    @ParameterizedTest @EnumSource(QuestionType.class)
    void allExistingAuthoringFamiliesAreSnapshottedWithoutActivatingTheirTargetScoring(QuestionType type) {
        UUID image = type == QuestionType.HOTSPOT ? image(username) : null;
        UUID q = question(type, image);
        var result = service.publish(command(0, q));
        var stored = service.read(quizId, result.publication().revision().id()).content().items().get(0).question();
        assertThat(stored.type()).isEqualTo(type);
        assertThat(stored.questionId()).isEqualTo(q);
        assertThat(stored.content()).contains(type == QuestionType.HOTSPOT ? "retained-images/" : "{");
        assertThat(count("question_revision_contract_bindings")).isZero();
        assertThat(stored.options().stream().map(o -> o.id()).distinct().count()).isEqualTo(stored.options().size());
    }

    @Test void migrationConstraintsRejectInvalidHeadVersionAndCrossQuizPointers() {
        UUID q = question(QuestionType.TRUE_FALSE, null); var result = service.publish(command(0, q));
        assertThatThrownBy(() -> sql.update("UPDATE quiz_publication_heads SET publication_version=0"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .hasStackTraceContaining("ck_publication_head_version");
        assertThatThrownBy(() -> sql.update("INSERT INTO quiz_publication_heads VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),1)",
                UUID.randomUUID().toString(), result.publication().revision().id().toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.current(quizId)).contains(result.publication());
    }

    @Test void multiParentPublicationAndSerializedHistoryUseBoundedSql() throws Exception {
        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < 5; i++) order.add(question(QuestionType.MCQ_SINGLE, image(username)));
        int fiveWrites = measure(() -> service.publish(command(0, order.toArray(UUID[]::new))));
        var first = service.current(quizId).orElseThrow();
        int fiveReads = measure(() -> serialize(service.read(quizId, first.revision().id())));
        for (int i = 0; i < 5; i++) order.add(question(QuestionType.MCQ_SINGLE, image(username)));
        int tenWrites = measure(() -> service.publish(command(1, order.toArray(UUID[]::new))));
        var next = service.current(quizId).orElseThrow();
        int tenReads = measure(() -> serialize(service.read(quizId, next.revision().id())));
        assertThat(tenWrites).as("Batched question, option and image writes/reads").isEqualTo(fiveWrites);
        assertThat(tenReads).as("Complete content and media serialization").isEqualTo(fiveReads);
        assertThat(tenReads).isLessThan(15);
        int oneHistory = measure(() -> serialize(service.history(quizId, 0, 1).getContent()));
        int fullHistory = measure(() -> serialize(service.history(quizId, 0, 100).getContent()));
        assertThat(fullHistory).isEqualTo(oneHistory).isLessThan(8);
        assertThat(service.read(quizId, next.revision().id()).images()).hasSize(10);
        System.out.printf("Publication SQL: writes 5=%d 10=%d; complete reads 5=%d 10=%d; history 1=%d all=%d%n",
                fiveWrites, tenWrites, fiveReads, tenReads, oneHistory, fullHistory);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void foreignSavedQuestionCannotBeLinkedOrPublishedEvenAfterLibraryRemoval(boolean removed) {
        UUID image = image(username), q = question(QuestionType.TRUE_FALSE, image);
        var original = service.publish(command(0, q));
        var originalContent = service.read(quizId, original.publication().revision().id());
        if (removed) library.delete(image, username);
        String borrower = "pub-borrower";
        UUID destination = anotherQuiz(user(borrower, true));
        authenticate(borrower);
        assertThatThrownBy(() -> authoring.getQuestion(q, SecurityContextHolder.getContext().getAuthentication()))
                .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class);
        assertThatThrownBy(() -> library.getByIdForValidation(image, borrower)).isInstanceOf(RuntimeException.class);
        int providerCalls = storage.calls.get();
        assertThatThrownBy(() -> relations.addQuestionToQuiz(borrower, destination, q))
                .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class)
                .hasMessage("Question is unavailable for reuse");
        assertThat(memberCount(destination)).isZero();

        // Exercise the same borrower's legitimate publication before the forbidden replacement.
        UUID own = questionFor(borrower, destination, QuestionType.TRUE_FALSE, null);
        var valid = service.publish(new Command(1, destination, UUID.randomUUID(), 0, List.of(own)));
        // A pre-fix database can already contain this unauthorized association. It must not grant publication.
        sql.update("INSERT INTO quiz_questions(quiz_id,question_id) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?))", destination.toString(), q.toString());
        assertReason(() -> service.publish(new Command(1, destination, UUID.randomUUID(), 1, List.of(own, q))), UNAVAILABLE);
        assertThat(service.current(destination)).contains(valid.publication());
        assertThat(count("quiz_publications")).isEqualTo(2);
        assertThat(count("quiz_publication_receipts")).isEqualTo(2);
        assertThat(count("saved_image_bindings")).isEqualTo(1);
        assertThat(storage.calls.get()).isEqualTo(providerCalls);
        // Hiding the original quiz cannot turn ambiguous foreign source authority into ownership.
        sql.update("UPDATE quizzes SET is_deleted=TRUE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        assertReason(() -> service.publish(new Command(1, destination, UUID.randomUUID(), 1, List.of(own, q))), UNAVAILABLE);
        sql.update("UPDATE quizzes SET is_deleted=FALSE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        authenticate(username);
        assertThat(service.read(quizId, original.publication().revision().id())).isEqualTo(originalContent);
    }

    @ParameterizedTest @ValueSource(strings = {"QUIZ_MODERATE", "QUESTION_ADMIN"})
    void ownRetainedQuestionCanBeReusedAfterRemovalAndAuthorizedAdministratorCanReuseItsSource(String permission) {
        UUID image = image(username), q = question(QuestionType.TRUE_FALSE, image);
        library.delete(image, username);
        UUID destination = anotherQuiz(ownerId);
        relations.addQuestionToQuiz(username, destination, q);
        var own = service.publish(new Command(1, destination, UUID.randomUUID(), 0, List.of(q)));
        var saved = service.read(destination, own.publication().revision().id());
        assertThat(saved.images()).singleElement().satisfies(i -> assertThat(storage.objects.get(i.objectKey())).isEqualTo("A"));
        UUID moderator = user("pub-moderator", true); grant(moderator, permission);
        UUID moderated = anotherQuiz(moderator); authenticate("pub-moderator");
        if (permission.equals("QUESTION_ADMIN")) {
            assertThatThrownBy(() -> relations.addQuestionToQuiz("pub-moderator", quizId, q))
                    .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class);
            assertReason(() -> service.publish(command(0, q)), UNAVAILABLE);
        }
        relations.addQuestionToQuiz("pub-moderator", moderated, q);
        var result = service.publish(new Command(1, moderated, UUID.randomUUID(), 0, List.of(q)));
        assertThat(service.read(moderated, result.publication().revision().id()).images()).singleElement()
                .satisfies(i -> assertThat(i.versionId()).isEqualTo(saved.images().get(0).versionId()));
        assertThat(count("saved_image_bindings")).isEqualTo(1);
        assertThat(storage.copies.get()).isEqualTo(1);
    }

    @Test void concurrentImageEditPublishesConsistentContentWithRepeatableReadConnectionDefault() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        }
        UUID image = image(username); images.prepare(Set.of(image), username);
        UUID q = question(QuestionType.TRUE_FALSE, null);
        Gate gate = new Gate(); faults.beforeQuestions = gate;
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> asOwner(() -> service.publish(command(0, q))));
            await(gate.entered); // The publication has read its receipt, but has not locked questions yet.
            var update = new UpdateQuestionRequest(); update.setQuestionText("Question with a newly saved image");
            update.setType(QuestionType.TRUE_FALSE); update.setDifficulty(Difficulty.EASY);
            update.setContent(QuestionContracts.example(QuestionType.TRUE_FALSE, QuestionContracts.View.AUTHOR));
            update.setAttachmentAssetId(image);
            authoring.updateQuestion(username, q, update);
            assertThat(count("saved_image_bindings")).isEqualTo(1);
            gate.release.countDown();
            var result = pending.get(15, TimeUnit.SECONDS);
            var published = service.read(quizId, result.publication().revision().id());
            assertThat(published.images()).singleElement().satisfies(i -> assertThat(i.assetId()).isEqualTo(image));
            assertThat(published.content().items().get(0).question().questionText()).isEqualTo("Question with a newly saved image");
            assertThat(service.current(quizId)).contains(result.publication());
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            }
        } finally {
            gate.release.countDown(); faults.beforeQuestions = null;
            pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void sourcePermissionCheckIsCurrentAndDoesNotLoadOnePermissionCollectionPerRole() {
        UUID first = question(QuestionType.TRUE_FALSE, null), second = question(QuestionType.TRUE_FALSE, null);
        String administrator = "pub-question-admin"; UUID actor = user(administrator, true);
        grant(actor, "QUESTION_ADMIN");
        for (int i = 0; i < 3; i++) grant(actor, "QUIZ_READ");
        UUID destination = anotherQuiz(actor); authenticate(administrator);
        int fiveRoles = measure(() -> relations.addQuestionToQuiz(administrator, destination, first));
        for (int i = 0; i < 5; i++) grant(actor, "QUIZ_READ");
        int tenRoles = measure(() -> relations.addQuestionToQuiz(administrator, destination, second));
        assertThat(tenRoles).as("Permission loading with five versus ten roles").isEqualTo(fiveRoles);
        assertThat(memberCount(destination)).isEqualTo(2);
        System.out.printf("Question reuse SQL: roles 5=%d 10=%d%n", fiveRoles, tenRoles);

        UUID forbidden = anotherQuiz(actor);
        var em = entityManagers.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagers, new org.springframework.orm.jpa.EntityManagerHolder(em));
        try {
            tx.executeWithoutResult(s -> users.findByIdWithRolesAndPermissions(actor).orElseThrow().getRoles().forEach(r -> r.getPermissions().size()));
            sql.update("""
                    DELETE rp FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id
                    JOIN permissions p ON p.permission_id=rp.permission_id
                    WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name='QUESTION_ADMIN'
                    """, actor.toString());
            assertThatThrownBy(() -> relations.addQuestionToQuiz(administrator, forbidden, first))
                    .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class)
                    .hasMessage("Question is unavailable for reuse");
            assertThat(memberCount(forbidden)).isZero();
        } finally { TransactionSynchronizationManager.unbindResource(entityManagers); em.close(); }
    }

    private UUID anotherQuiz(UUID creator) {
        return tx.execute(status -> {
            var quiz = new Quiz(); quiz.setCreator(users.findById(creator).orElseThrow());
            quiz.setCategory(categories.findAll().get(0)); quiz.setTitle("Another private quiz " + UUID.randomUUID());
            quiz.setDescription("Synthetic destination"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
    }
    private int memberCount(UUID quiz) {
        return sql.queryForObject("SELECT COUNT(*) FROM quiz_questions WHERE quiz_id=UUID_TO_BIN(?)", Integer.class, quiz.toString());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void detachingOriginalSourceCannotAuthorizeAnOldForeignMembership(boolean removed) {
        UUID image = image(username), q = question(QuestionType.TRUE_FALSE, image);
        if (removed) library.delete(image, username);
        String borrower = "pub-detached-borrower";
        UUID destination = anotherQuiz(user(borrower, true));
        // Reproduce an unauthorized association left by the old writer, not a new reuse grant.
        sql.update("INSERT INTO quiz_questions(quiz_id,question_id) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?))",
                destination.toString(), q.toString());
        relations.removeQuestionFromQuiz(username, quizId, q);
        assertThat(memberCount(quizId)).isZero();
        assertThat(memberCount(destination)).isEqualTo(1);
        authenticate(borrower);
        assertThatThrownBy(() -> library.getByIdForValidation(image, borrower)).isInstanceOf(RuntimeException.class);
        var request = new Command(1, destination, UUID.randomUUID(), 0, List.of(q));
        assertReason(() -> service.publish(request), UNAVAILABLE);
        assertThat(service.current(destination)).isEmpty();
        for (String table : List.of("quiz_revisions", "quiz_publications", "quiz_publication_heads",
                "quiz_publication_receipts", "quiz_revision_images")) assertThat(count(table)).isZero();
        assertThat(count("saved_image_bindings")).isEqualTo(1);
        assertThat(storage.objects.values()).containsExactly("A");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void legacyAuthorityNeedsExplicitConfirmationAndSurvivesDetachment(boolean removed) {
        UUID image = image(username), q = legacyQuestion(image);
        if (removed) library.delete(image, username);
        String borrower = "pub-legacy-borrower";
        UUID borrowerId = user(borrower, true), destination = anotherQuiz(borrowerId);
        sql.update("INSERT INTO quiz_questions(quiz_id,question_id) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?))",
                destination.toString(), q.toString());
        // A plausible current membership is not proof, even for the real owner.
        assertReason(() -> service.publish(command(0, q)), UNAVAILABLE);
        assertThatThrownBy(() -> confirmations.confirm(Set.of(q), ownerId, "Verified original author's record"))
                .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class);
        relations.removeQuestionFromQuiz(username, quizId, q);
        authenticate(borrower);
        assertReason(() -> service.publish(new Command(1, destination, UUID.randomUUID(), 0, List.of(q))), UNAVAILABLE);
        assertThatThrownBy(() -> relations.addQuestionToQuiz(borrower, destination, q))
                .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class);

        UUID moderator = user("pub-legacy-moderator", true); grant(moderator, "QUIZ_MODERATE");
        authenticate("pub-legacy-moderator");
        assertReason(() -> service.publish(new Command(1, destination, UUID.randomUUID(), 0, List.of(q))), UNAVAILABLE);
        confirmations.confirm(Set.of(q), ownerId, "Verified original author's record");
        confirmations.confirm(Set.of(q), ownerId, "Repeated confirmation must not replace first evidence");
        assertThat(count("question_source_confirmations")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT BIN_TO_UUID(confirmed_by) FROM question_source_confirmations", String.class))
                .isEqualTo(moderator.toString());
        assertThat(sql.queryForObject("SELECT evidence FROM question_source_confirmations", String.class))
                .isEqualTo("Verified original author's record");
        assertThatThrownBy(() -> confirmations.confirm(Set.of(q), borrowerId, "Cannot transfer existing authority"))
                .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class);
        assertThatThrownBy(() -> sql.update("UPDATE questions SET source_owner_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",
                borrowerId.toString(), q.toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        authenticate(borrower);
        assertReason(() -> service.publish(new Command(1, destination, UUID.randomUUID(), 0, List.of(q))), UNAVAILABLE);
        authenticate(username);
        relations.addQuestionToQuiz(username, quizId, q);
        var result = restarted().publish(command(0, q));
        assertThat(service.read(quizId, result.publication().revision().id()).images()).singleElement()
                .satisfies(i -> assertThat(storage.objects.get(i.objectKey())).isEqualTo("A"));
        assertThat(count("saved_image_bindings")).isEqualTo(1);
    }

    @Test void generatedQuestionsKeepSourceAuthorityAcrossChunkAndConsolidatedQuizzes() {
        var question = new uk.gegc.quizmaker.features.question.domain.model.Question();
        // Durable generation checkpoints restore new entities without authoring IDs.
        question.setQuestionText("Generated shared question"); question.setType(QuestionType.TRUE_FALSE);
        question.setDifficulty(Difficulty.EASY); question.setContent("{\"answer\":true}");
        var request = new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest(
                UUID.randomUUID(), null, null, null, null, "Consolidated generated quiz", null,
                Map.of(QuestionType.TRUE_FALSE, 1), Difficulty.EASY, 2, null, null, "en");
        var quizzesAndQuestion = tx.execute(s -> {
            var actor = users.findById(ownerId).orElseThrow();
            var category = categories.findAll().get(0);
            var chunk = assembly.createChunkQuiz(actor, List.of(question), 0, request, category, Set.of(), request.documentId());
            var consolidated = assembly.createConsolidatedQuiz(actor, List.of(question), request, category, Set.of(), request.documentId(), 1);
            return List.of(chunk.getId(), consolidated.getId(), chunk.getQuestions().iterator().next().getId());
        });
        UUID generated = quizzesAndQuestion.get(2);
        assertThat(questions.findById(generated).orElseThrow().getSourceOwnerId()).isEqualTo(ownerId);
        for (UUID quiz : quizzesAndQuestion.subList(0, 2))
            assertThat(service.publish(new Command(1, quiz, UUID.randomUUID(), 0, List.of(generated))).publication().version()).isEqualTo(1);
        assertThat(count("question_source_confirmations")).isZero();
    }

    @Test void anOwnerValueWithoutItsAuthorityRowCannotGrantReuse() {
        UUID q = legacyQuestion(null);
        sql.update("UPDATE questions SET source_owner_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", ownerId.toString(), q.toString());
        assertReason(() -> service.publish(command(0, q)), UNAVAILABLE);
        UUID destination = anotherQuiz(ownerId);
        assertThatThrownBy(() -> relations.addQuestionToQuiz(username, destination, q))
                .isInstanceOf(uk.gegc.quizmaker.shared.exception.ForbiddenException.class);
        assertThat(memberCount(destination)).isZero();
        assertThat(count("quiz_publications")).isZero();
        assertThat(count("question_source_authorities")).isZero();
    }

    @Test void confirmationFailureRollsBackBothAuthorityAndEvidence() {
        UUID q = legacyQuestion(null);
        grant(ownerId, "QUIZ_MODERATE");
        faults.failConfirmation = true;
        assertThatThrownBy(() -> confirmations.confirm(Set.of(q), ownerId, "Reviewed provenance"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(faults.reached).isTrue();
        assertThat(count("question_source_confirmations")).isZero();
        assertThat(tx.execute(s -> questionSources.lockSources(List.of(q))).get(0).ownerId()).isNull();
        assertThat(count("question_source_authorities")).isZero();
        faults.failConfirmation = false;
        confirmations.confirm(Set.of(q), ownerId, "Reviewed provenance");
        assertThat(service.publish(command(0, q)).publication().version()).isEqualTo(1);
    }

    private UUID legacyQuestion(UUID image) {
        if (image != null) images.prepare(Set.of(image), username);
        return tx.execute(s -> {
            // Exact pre-V94 state: authoring row and authorized retained media, but no source-owner metadata.
            var question = new uk.gegc.quizmaker.features.question.domain.model.Question();
            question.setQuestionText("Legacy question"); question.setType(QuestionType.TRUE_FALSE);
            question.setDifficulty(Difficulty.EASY); question.setContent("{\"answer\":true}");
            question.setAttachmentAssetId(image); question.setQuizId(new ArrayList<>(List.of(quizzes.findById(quizId).orElseThrow())));
            questions.saveAndFlush(question);
            if (image != null) images.bind(question.getId(), Set.of(image), Set.of(), username);
            return question.getId();
        });
    }

    @Test void concurrentPublicationsOfDifferentQuizzesSharingAQuestionBothCommit() throws Exception {
        UUID image = image(username), q = question(QuestionType.TRUE_FALSE, image);
        UUID destination = anotherQuiz(ownerId);
        relations.addQuestionToQuiz(username, destination, q);
        library.delete(image, username);
        Command firstCommand = command(0, q);
        Command secondCommand = new Command(1, destination, UUID.randomUUID(), 0, List.of(q));
        faults.afterOwnedQuiz = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes;
        try {
            var first = pool.submit(() -> outcome(firstCommand));
            var second = pool.submit(() -> outcome(secondCommand));
            outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            faults.afterOwnedQuiz = null;
            pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(outcomes).allSatisfy(result -> assertThat(result).isInstanceOf(Result.class));
        for (String table : List.of("quiz_revisions", "quiz_publications", "quiz_publication_heads",
                "quiz_publication_receipts", "quiz_revision_images")) assertThat(count(table)).isEqualTo(2);
        assertThat(service.current(quizId)).contains(((Result) outcomes.get(0)).publication());
        assertThat(service.current(destination)).contains(((Result) outcomes.get(1)).publication());
        assertThat(service.publish(firstCommand).replayed()).isTrue();
        assertThat(service.publish(secondCommand).replayed()).isTrue();
        assertThat(count("saved_image_bindings")).isEqualTo(1);
        assertThat(storage.copies.get()).isEqualTo(1);
        assertThat(storage.objects.values()).containsExactly("A");
    }

    private void serialize(Object value) { try { new ObjectMapper().findAndRegisterModules().writeValueAsBytes(value); } catch (Exception e) { throw new AssertionError(e); } }
    private int measure(Runnable work) {
        CountingSource.calls.set(0); CountingSource.enabled = true;
        try { work.run(); return CountingSource.calls.get(); } finally { CountingSource.enabled = false; }
    }
    private Command command(long version, UUID... questions) { return new Command(1, quizId, UUID.randomUUID(), version, List.of(questions)); }
    private int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void edit(UUID question, String text) {
        var existing = questions.findById(question).orElseThrow();
        var request = new UpdateQuestionRequest(); request.setQuestionText(text);
        request.setType(existing.getType()); request.setDifficulty(existing.getDifficulty());
        request.setHint(existing.getHint()); request.setExplanation(existing.getExplanation());
        try { request.setContent(json.readTree(existing.getContent())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new AssertionError(failure); }
        authoring.updateQuestion(username, question, request);
    }
    private UUID question(QuestionType type, UUID image) {
        return questionFor(username, quizId, type, image);
    }
    private UUID questionFor(String actor, UUID quiz, QuestionType type, UUID image) {
        var request = new CreateQuestionRequest(); request.setType(type); request.setDifficulty(Difficulty.EASY);
        request.setQuestionText("Synthetic question"); request.setContent(QuestionContracts.example(type, QuestionContracts.View.AUTHOR));
        request.setAttachmentAssetId(image); request.setQuizIds(List.of(quiz));
        return authoring.createQuestion(actor, request);
    }
    private UUID image(String owner) {
        return tx.execute(s -> {
            var asset = new MediaAsset(); var id = UUID.randomUUID(); asset.setId(id); asset.setKey("source/" + id);
            asset.setType(MediaAssetType.IMAGE); asset.setStatus(MediaAssetStatus.READY); asset.setMimeType("image/png");
            asset.setSizeBytes(1L); asset.setWidth(640); asset.setHeight(480); asset.setCreatedBy(owner);
            assets.saveAndFlush(asset); storage.sources.put(asset.getKey(), "A"); return id;
        });
    }
    private UUID user(String name, boolean permission) {
        UUID id = tx.execute(s -> {
            User user = new User(); user.setUsername(name); user.setEmail(name + "@example.invalid"); user.setHashedPassword("test-only");
            user.setActive(true); user.setRoles(new HashSet<>()); return users.saveAndFlush(user).getId();
        });
        if (permission) grant(id, "QUIZ_UPDATE"); return id;
    }
    private void grant(UUID user, String permission) {
        tx.executeWithoutResult(s -> {
            String role = "pub-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, permission);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", user.toString(), role);
        });
    }
    private FixedForm form(PublishedContent publication) {
        var items = publication.content().items().stream().map(i -> new FixedForm.Item(UUID.randomUUID(), i.id(), i.authoredPosition())).toList();
        return new FixedForm(UUID.randomUUID(), 1, publication.revision().id(), items);
    }
    private QuizRevisionPublicationService restarted() {
        var named = new NamedParameterJdbcTemplate(dataSource);
        return new QuizRevisionPublicationServiceImpl(new JdbcQuizPublicationRepository(named), new JdbcQuizRevisionRepository(named),
                images, content, clock, metrics, transactions, new JdbcQuestionSourceScopeRepository(named));
    }
    private List<Object> race(Command a, Command b) throws Exception {
        faults.contenders = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> outcome(a)); var second = pool.submit(() -> outcome(b));
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { faults.contenders = null; pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
    private Object outcome(Command command) {
        return asOwner(() -> { try { return service.publish(command); } catch (PublicationException failure) { return failure; } });
    }
    private <T> T asOwner(Supplier<T> work) { authenticate(username); try { return work.get(); } finally { SecurityContextHolder.clearContext(); } }
    private static void authenticate(String name) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null, List.of())); }
    private static void assertReason(Runnable work, PublicationException.Reason reason) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(PublicationException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static class Gate { final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); }
    @org.aspectj.lang.annotation.Aspect
    static class Faults {
        final JdbcTemplate sql;
        volatile String fail; volatile boolean loseAcknowledgement; final AtomicBoolean reached = new AtomicBoolean();
        volatile CyclicBarrier contenders; volatile CyclicBarrier afterOwnedQuiz; volatile Gate holdPublication;
        volatile Gate beforeQuestions;
        volatile boolean failConfirmation;
        final Set<Long> gated = ConcurrentHashMap.newKeySet();
        Faults(JdbcTemplate sql) { this.sql = sql; }
        void reset() { fail = null; loseAcknowledgement = false; failConfirmation = false; reached.set(false); contenders = null; afterOwnedQuiz = null; holdPublication = null; beforeQuestions = null; gated.clear(); }
        @org.aspectj.lang.annotation.Around("execution(* uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository.confirmLegacy(..))")
        Object confirmation(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            if (failConfirmation) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_source_confirmations", Integer.class)).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM questions WHERE source_owner_id IS NOT NULL", Integer.class)).isEqualTo(1);
                reached.set(true); throw new IllegalStateException("Reached source confirmation write checkpoint");
            }
            return result;
        }
        @org.aspectj.lang.annotation.Around("execution(* uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository.*(..))")
        Object around(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
            String method = call.getSignature().getName();
            if (method.equals("draft") && Boolean.TRUE.equals(call.getArgs()[1]) && contenders != null && gated.add(Thread.currentThread().getId()))
                contenders.await(15, TimeUnit.SECONDS);
            if (method.equals("lockQuestions") && beforeQuestions != null) {
                beforeQuestions.entered.countDown(); await(beforeQuestions.release);
            }
            Object result = call.proceed();
            if (method.equals("draft") && Boolean.TRUE.equals(call.getArgs()[1]) && afterOwnedQuiz != null
                    && gated.add(Thread.currentThread().getId())) afterOwnedQuiz.await(15, TimeUnit.SECONDS);
            if (method.equals("lockQuestions") && holdPublication != null) { holdPublication.entered.countDown(); await(holdPublication.release); }
            if (method.equals(fail)) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_revisions", Integer.class)).isPositive();
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_revision_images", Integer.class)).isPositive();
                if (!method.equals("insert")) assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_publication_heads", Integer.class)).isPositive();
                if (method.equals("record")) assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_publication_receipts", Integer.class)).isPositive();
                reached.set(true); throw new IllegalStateException("Reached publication write checkpoint");
            }
            if (method.equals("record") && loseAcknowledgement) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { reached.set(true); throw new IllegalStateException("Lost acknowledgement after commit"); }
            });
            return result;
        }
    }
    static class Storage implements SavedImageStorage {
        final Map<String, String> sources = new ConcurrentHashMap<>(), objects = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger(), copies = new AtomicInteger();
        void reset() { sources.clear(); objects.clear(); calls.set(0); copies.set(0); }
        void boundary() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); calls.incrementAndGet(); }
        @Override public Source inspect(String key) {
            boundary(); String value = sources.get(key); assertThat(value).isNotNull(); return new Source(value, value.length(), "image/png");
        }
        @Override public void retain(SavedImageVersion version) {
            boundary(); String value = sources.get(version.getSourceKey());
            assertThat(value).isEqualTo(version.getSourceEtag());
            if (objects.putIfAbsent(version.getObjectKey(), value) == null) copies.incrementAndGet();
        }
        @Override public void verify(SavedImageVersion version) { boundary(); assertThat(objects.get(version.getObjectKey())).isEqualTo(version.getSourceEtag()); }
    }
    static class CountingSource extends DelegatingDataSource {
        static volatile boolean enabled; static final AtomicInteger calls = new AtomicInteger();
        CountingSource(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return connection(super.getConnection(u, p)); }
        static Connection connection(Connection target) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                Object result = invoke(target, method, args);
                if (result instanceof Statement statement) {
                    Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p, m, a) -> {
                        if (enabled && m.getName().startsWith("execute")) calls.incrementAndGet();
                        return invoke(statement, m, a);
                    });
                }
                return result;
            });
        }
        static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
