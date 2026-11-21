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
import org.springframework.transaction.TransactionDefinition;
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
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.*;
import uk.gegc.quizmaker.features.learning.config.LearningCommandRateConfig;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.infra.repository.*;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import uk.gegc.quizmaker.features.learning.api.LearningSessionApi;
import uk.gegc.quizmaker.features.learning.api.LearningSessionController;
import uk.gegc.quizmaker.features.learning.LearningTransactionLogs;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository;
import uk.gegc.quizmaker.features.learning.api.LearningSessionErrorHandler;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.Reason.*;

/** Real authoring, media retention, authorization, publication and MySQL; only object storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "quizmaker.learning.due.max-batch-size=20", "quizmaker.learning.sessions-enabled=true",
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
        LearningSessionServiceImpl.class, AuthenticatedUserLookupImpl.class, JdbcLearningSessionRepository.class,
        JdbcPublishedLearningSourceVerifier.class, JdbcLearningCommandBudget.class, LearningCommandRateConfig.class,
        LearningDueSelectionServiceImpl.class, JdbcLearningDueSelectionRepository.class, LearningDueSelectionConfig.class,
        LearningStudyServiceImpl.class, PublishedLearningItemMaterial.class, JdbcLearningStudyRepository.class,
        LearningFormServiceImpl.class, JdbcLearningFormRepository.class, LearningScheduleServiceImpl.class,
        JdbcLearningScheduleRepository.class, FsrsSchedulingStrategyImpl.class,
        uk.gegc.quizmaker.features.question.application.impl.PublishedQuestionContractServiceImpl.class,
        uk.gegc.quizmaker.features.question.application.impl.QuestionRevisionContractServiceImpl.class,
        uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl.class,
        uk.gegc.quizmaker.features.question.config.BaselineQuestionContractConfig.class,
        uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository.class,
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
        @Bean @org.springframework.context.annotation.Primary
        Clock clock() { return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC); }
        @Bean Clock authAdmissionClock() {
            return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
        }
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
    @Autowired PublishedQuestionContractService publishedContracts;
    @Autowired LearningStudyService study;
    @Autowired org.springframework.beans.factory.config.AutowireCapableBeanFactory beans;
    @Autowired LearningSessionService sessions;
    @Autowired LearningDueSelectionService dueSelection;
    @Autowired LearningScheduleService learningSchedules;
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
            sql.update("UPDATE learning_schedule_slot SET active_cycle_id=NULL,cycle_version=0");
            for (String table : List.of("learning_enrollment_item", "learning_enrollment", "learning_prepared_publication",
                    "learning_session_response", "learning_session_start", "learning_cycle_session", "learning_review_event", "learning_fsrs_state",
                    "learning_schedule_receipt", "learning_schedule_cycle", "learning_schedule_slot", "learning_form_option", "learning_form_item", "learning_form", "learning_session_create_receipt",
                    "learning_session", "question_revision_contract_bindings", "attempt_form_item", "attempt_form", "quiz_publication_receipts", "quiz_publication_heads",
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

    @Test void publicLearningStartsFromEmptyStorageAndKeepsTheOriginalFormAfterEdits() {
        UUID first = question(QuestionType.TRUE_FALSE, null);
        UUID second = question(QuestionType.MCQ_SINGLE, null);
        UUID third = question(QuestionType.TRUE_FALSE, null);
        var publication = service.publish(command(0, first, second, third)).publication().revision();
        var content = service.read(quizId, publication.id()).content();
        var source = new LearningSession.Source(1, quizId, content.id(), content.schemaVersion());
        var ids = content.items().stream().map(i -> i.question().id()).toList();
        loginLearner(username);
        for (String table : List.of("spaced_repetition_entry", "learning_session", "learning_schedule_cycle",
                "learning_review_event", "question_revision_contract_bindings")) assertThat(count(table)).isZero();
        var emptyKey = new LearningStudyService.Start(UUID.randomUUID(), source, 3);
        var empty = study.start(emptyKey);
        assertThat(empty.form().outcome()).isEqualTo(LearningForm.Outcome.EMPTY_COMPLETE);
        assertThat(count("learning_schedule_slot")).isZero();

        var enrollment = new LearningStudyService.Enroll(UUID.randomUUID(), source, ids);
        var receipt = study.enroll(enrollment);
        assertThat(receipt.items()).extracting(LearningStudyService.EnrolledItem::questionRevisionId)
                .containsExactlyElementsOf(ids);
        assertThat(count("question_revision_contract_bindings")).isEqualTo(3);
        assertThat(count("learning_schedule_cycle")).isEqualTo(3);
        assertThat(study.enroll(enrollment)).isEqualTo(receipt);
        assertThat(study.start(emptyKey)).isEqualTo(empty);
        deniedLearning(() -> study.enroll(new LearningStudyService.Enroll(enrollment.commandId(), source, ids.subList(0,2))),
                LearningSessionException.Reason.CONFLICT);
        var begin = new LearningStudyService.Start(UUID.randomUUID(), source, 3);
        var due = dueSelection.select(new LearningDueSelectionService.Select(1, source, 3, null)).items();
        var original = study.start(begin);
        assertThat(original.form().items()).extracting(LearningForm.Item::questionRevisionId)
                .containsExactlyElementsOf(due.stream().map(LearningDueSelection.Item::questionRevisionId).toList());
        assertThat(count("learning_cycle_session")).isEqualTo(3);
        var single = study.start(new LearningStudyService.Start(UUID.randomUUID(), source, 1));
        int smallReads = measure(() -> study.resume(single.form().sessionId()));
        assertThat(CountingSource.selectedQuestions.get()).isOne();
        assertThat(measure(() -> study.resume(original.form().sessionId()))).isEqualTo(smallReads);
        assertThat(CountingSource.selectedQuestions.get()).isEqualTo(3);

        sql.update("UPDATE quizzes SET shuffle_learning_questions=TRUE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        var shuffledKey = new LearningStudyService.Start(UUID.randomUUID(), source, 3);
        var shuffled = study.start(shuffledKey);
        assertThat(shuffled.form().items()).containsExactlyInAnyOrderElementsOf(original.form().items());
        assertThat(sql.queryForObject("SELECT shuffled FROM learning_session_start WHERE session_id=UUID_TO_BIN(?)",
                Boolean.class, shuffled.form().sessionId().toString())).isTrue();
        sql.update("UPDATE quizzes SET shuffle_learning_questions=FALSE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        edit(first, "Changed in publication B");
        service.publish(command(1, first, second, third));
        assertThat(study.start(shuffledKey)).isEqualTo(shuffled);
        LearningStudyService restarted = beans.createBean(LearningStudyServiceImpl.class);
        assertThat(restarted).isNotSameAs(study);
        assertThat(restarted.resume(shuffled.form().sessionId())).isEqualTo(shuffled);
        assertThat(restarted.start(shuffledKey)).isEqualTo(shuffled);
        assertThat(study.resume(original.form().sessionId())).isEqualTo(original);
        assertThat(study.start(begin)).isEqualTo(original);
        deniedLearning(() -> study.start(new LearningStudyService.Start(begin.commandId(), source, 2)),
                LearningSessionException.Reason.CONFLICT);
        String foreignName = "other-" + UUID.randomUUID().toString().substring(0,8);
        user(foreignName, true);
        loginLearner(foreignName);
        deniedLearning(() -> study.resume(original.form().sessionId()), LearningSessionException.Reason.UNAVAILABLE);
        deniedLearning(() -> study.enroll(enrollment), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        loginLearner(username);
        sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
        deniedLearning(() -> study.start(begin), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        deniedLearning(() -> study.enroll(enrollment), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        deniedLearning(() -> study.resume(shuffled.form().sessionId()), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        for (String table : List.of("attempts","answers","attempt_accepted_response","attempt_automatic_result",
                "learning_review_event","spaced_repetition_entry")) assertThat(count(table)).isZero();
    }

    @Test void publicLearningRollsBackPreparationEnrollmentAndReachedFormWrites() {
        UUID question = question(QuestionType.TRUE_FALSE, null);
        var pub = service.publish(command(0,question)).publication().revision();
        var content = service.read(quizId, pub.id()).content();
        var source = new LearningSession.Source(1,quizId,pub.id(),content.schemaVersion());
        loginLearner(username);
        var questions = List.of(content.items().get(0).question().id());
        var cmd = new LearningStudyService.Enroll(UUID.randomUUID(),source,questions);
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var incompatible = new TransactionTemplate(transactions);
        incompatible.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        deniedLearning(() -> incompatible.executeWithoutResult(status -> study.enroll(cmd)),
                LearningSessionException.Reason.UNSUPPORTED_TRANSACTION);
        assertThat(count("learning_command_budget")).isZero();
        outer.executeWithoutResult(status -> {
            study.enroll(cmd);
            for (String table : List.of("learning_prepared_publication","question_revision_contract_bindings",
                    "learning_enrollment_item","learning_schedule_cycle","learning_command_budget")) assertThat(count(table)).isOne();
            status.setRollbackOnly();
        });
        for (String table : List.of("learning_prepared_publication","question_revision_contract_bindings",
                "learning_enrollment_item","learning_schedule_cycle","learning_command_budget")) assertThat(count(table)).isZero();
        study.enroll(cmd);
        var begin = new LearningStudyService.Start(UUID.randomUUID(),source,1);
        outer.executeWithoutResult(status -> {
            study.start(begin);
            for (String table : List.of("learning_cycle_session","learning_form_item","learning_session_start"))
                assertThat(count(table)).isOne();
            status.setRollbackOnly();
        });
        for (String table : List.of("learning_session","learning_form","learning_cycle_session","learning_session_start"))
            assertThat(count(table)).isZero();
        long balance = sql.queryForObject("SELECT credit_units FROM learning_command_budget", Long.class);
        sql.execute("""
                CREATE TRIGGER learning_start_write_failure AFTER INSERT ON learning_session_start FOR EACH ROW
                BEGIN
                    IF EXISTS (SELECT 1 FROM learning_form_item WHERE form_id=NEW.form_id)
                       AND EXISTS (SELECT 1 FROM learning_cycle_session WHERE session_id=NEW.session_id) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LEARNING_PRIVATE_START_DIAGNOSTIC';
                    END IF;
                END
                """);
        try (var logs = new LearningTransactionLogs()) {
            deniedLearning(() -> study.start(begin), LearningSessionException.Reason.PERSISTENCE_UNAVAILABLE);
            logs.assertCleanFailure(JdbcLearningStudyRepository.class, "recordStart", "INSERT INTO learning_session_start",
                    ownerId.toString(), begin.commandId().toString());
        } finally { sql.execute("DROP TRIGGER learning_start_write_failure"); }
        for (String table : List.of("learning_session", "learning_form", "learning_cycle_session", "learning_session_start")) {
            assertThat(count(table)).isZero();
        }
        assertThat(sql.queryForObject("SELECT credit_units FROM learning_command_budget", Long.class)).isEqualTo(balance);
        assertThat(study.start(begin).items()).hasSize(1);
    }

    @Test void learningAnswersFollowSavedOrderAndReplayTheOriginalAcknowledgementAfterCompletion() throws Exception {
        var fixture = enrolledLearning(2);
        sql.update("UPDATE quizzes SET shuffle_learning_questions=TRUE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        var form = study.start(fixture.start());
        UUID session = form.form().sessionId();
        var first = answer(form, 0);
        var second = answer(form, 1);
        var mvc = learningHttp(study);
        var outOfOrder = new LearningReview.Command(session, second.commandId(), 1, second.sourceItemId(),
                second.rating(), second.response());
        var staleVersion = new LearningReview.Command(session, first.commandId(), 2, first.sourceItemId(),
                first.rating(), first.response());
        for (var rejected : List.of(outOfOrder, staleVersion)) {
            mvc.perform(learningAnswer(rejected)).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(
                    uk.gegc.quizmaker.shared.api.problem.ErrorTypes.OPTIMISTIC_LOCK_CONFLICT.toString()));
        }
        var foreign = "peer-" + UUID.randomUUID().toString().substring(0, 8);
        user(foreign, true);
        loginLearner(foreign);
        mvc.perform(learningAnswer(first)).andExpect(status().isNotFound());
        loginLearner(username);
        var wrongType = answerWith(first, json.createObjectNode().put("type", "OPEN").put("answer", "unmatched"));
        mvc.perform(learningAnswer(wrongType)).andExpect(status().isBadRequest());
        var firstQuestion = form.form().items().get(0).questionRevisionId().toString();
        // Deliberately damage retained strategy metadata for this scenario, then restore the valid control.
        sql.update("UPDATE learning_schedule_cycle SET strategy_id='fsrs/v99' WHERE question_revision_id=UUID_TO_BIN(?)", firstQuestion);
        mvc.perform(learningAnswer(first)).andExpect(status().isUnprocessableEntity());
        sql.update("UPDATE learning_schedule_cycle SET strategy_id='fsrs/v1' WHERE question_revision_id=UUID_TO_BIN(?)", firstQuestion);
        assertThat(count("learning_session_response")).isZero();
        assertThat(count("learning_review_event")).isZero();

        var firstAck = mvc.perform(learningAnswer(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionVersion").value(2))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.nextItemId").value(second.sourceItemId().toString()))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(learningAnswer(answerWith(first, json.createObjectNode().put("type", "TRUE_FALSE").put("answer", false))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(
                        uk.gegc.quizmaker.shared.api.problem.ErrorTypes.IDEMPOTENCY_CONFLICT.toString()));
        mvc.perform(get("/api/v1/learning/sessions/" + session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedItems").value(1))
                .andExpect(jsonPath("$.nextItemId").value(second.sourceItemId().toString()));
        mvc.perform(learningAnswer(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionVersion").value(3)).andExpect(jsonPath("$.completed").value(true));
        var restarted = beans.createBean(LearningStudyServiceImpl.class);
        var restartedHttp = learningHttp(restarted);
        assertThat(restartedHttp.perform(learningAnswer(first)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo(firstAck);
        assertThat(restarted.start(fixture.start())).isEqualTo(form);
        assertThat(restarted.resume(session).progress().completed()).isTrue();
        mvc.perform(learningAnswer(new LearningReview.Command(session, UUID.randomUUID(), 3, first.sourceItemId(),
                first.rating(), first.response()))).andExpect(status().isConflict());
        assertThat(count("learning_session_response")).isEqualTo(2);
        assertThat(count("learning_review_event")).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_cycle_session WHERE released=1", Integer.class)).isEqualTo(2);
        assertThat(learningCredit()).isEqualTo(16 * 60_000_000L);
        for (var table : List.of("attempts", "answers", "attempt_accepted_response", "attempt_automatic_result",
                "spaced_repetition_entry", "repetition_review_log")) assertThat(count(table)).as(table).isZero();
        sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
        restartedHttp.perform(learningAnswer(first)).andExpect(status().isNotFound());
        assertThat(count("learning_review_event")).isEqualTo(2);
    }

    @Test void learningGetAndRespondClassifyCorruptRetainedContentAsAStorageError() throws Exception {
        var fixture = enrolledLearning(1);
        var form = study.start(fixture.start());
        var command = answer(form, 0);
        var mvc = learningHttp(study);
        var question = fixture.questions().get(0);
        var snapshot = learningSchedules.read(fixture.start().source(), question);
        long credit = learningCredit();
        String original = sql.queryForObject("SELECT content FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                String.class, question.toString());
        sql.update("UPDATE question_revisions SET content=JSON_OBJECT('privateProbe','LEARNING_PRIVATE_CONTENT') WHERE id=UUID_TO_BIN(?)",
                question.toString());
        try {
            for (var request : List.of(get("/api/v1/learning/sessions/" + command.sessionId()), learningAnswer(command))) {
                String error = mvc.perform(request).andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.status").value(500)).andReturn().getResponse().getContentAsString();
                assertThat(error).doesNotContain("privateProbe", "LEARNING_PRIVATE_CONTENT");
            }
            assertThat(count("learning_session_response")).isZero();
            assertThat(count("learning_review_event")).isZero();
            assertThat(learningCredit()).isEqualTo(credit);
            assertThat(learningSchedules.read(fixture.start().source(), question)).isEqualTo(snapshot);
        } finally {
            sql.update("UPDATE question_revisions SET content=? WHERE id=UUID_TO_BIN(?)", original, question.toString());
        }
        assertThat(study.resume(form.form().sessionId())).isEqualTo(form);
        mvc.perform(learningAnswer(command)).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        assertThat(count("learning_review_event")).isOne();
    }

    @Test void explicitPracticeKeepsTheScheduleAndEarlyReviewAdvancesTheSameCycleOnce() throws Exception {
        var fixture = enrolledLearning(1);
        var question = fixture.questions().get(0);
        var source = fixture.start().source();
        var mvc = learningHttp(study);
        var due = study.start(fixture.start());
        var badEarly = explicitStart(fixture, UUID.randomUUID(), LearningSessionApi.SelectionMode.EARLY, question);
        mvc.perform(badEarly).andExpect(status().isConflict());
        assertThat(count("learning_session")).isOne();
        mvc.perform(learningAnswer(answer(due, 0))).andExpect(status().isOk());
        var initial = learningSchedules.read(source, question);
        assertThat(initial.state().dueAt()).isAfter(clock.instant());
        assertThat(initial.state().sequence()).isOne();

        var practice = sessionOf(mvc, explicitStart(fixture, UUID.randomUUID(), LearningSessionApi.SelectionMode.PRACTICE, question));
        mvc.perform(learningAnswer(answer(practice, 0))).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true)).andExpect(jsonPath("$.nextDueAt").doesNotExist());
        assertThat(learningSchedules.read(source, question)).isEqualTo(initial);
        assertThat(count("learning_review_event")).isOne();

        UUID key = UUID.randomUUID();
        var early = sessionOf(mvc, explicitStart(fixture, key, LearningSessionApi.SelectionMode.EARLY, question));
        assertThat(early.mode()).isEqualTo(LearningReview.Mode.EARLY);
        var earlyPeer = sessionOf(mvc, explicitStart(fixture, UUID.randomUUID(), LearningSessionApi.SelectionMode.EARLY, question));
        mvc.perform(explicitStart(fixture, key, LearningSessionApi.SelectionMode.PRACTICE, question)).andExpect(status().isConflict());
        mvc.perform(explicitStart(fixture, UUID.randomUUID(), LearningSessionApi.SelectionMode.PRACTICE, UUID.randomUUID()))
                .andExpect(status().isNotFound());
        var earlyCommand = answer(early, 0);
        var confirmed = study.respond(earlyCommand);
        assertThat(study.respond(earlyCommand)).isEqualTo(confirmed);
        assertThat(study.resume(earlyPeer.form().sessionId()).progress().status()).isEqualTo(LearningReview.Status.SUPERSEDED);
        var advanced = learningSchedules.read(source, question);
        assertThat(advanced.expected().cycleId()).isEqualTo(initial.expected().cycleId());
        assertThat(advanced.state().sequence()).isEqualTo(2);
        assertThat(advanced.state().dueAt()).isEqualTo(confirmed.nextDueAt());
        assertThat(count("learning_session_response")).isEqualTo(3);
        assertThat(count("learning_review_event")).isEqualTo(2);
        assertThat(count("learning_schedule_cycle")).isOne();

        var replacement = learningSchedules.execute(new LearningScheduleService.Restart(1, UUID.randomUUID(), source,
                question, advanced.expected(), FsrsManifest.approved())).snapshot();
        assertThat(replacement.expected().cycleId()).isNotEqualTo(advanced.expected().cycleId());
        assertThat(study.respond(earlyCommand)).isEqualTo(confirmed);
        var oldSessionNewCommand = answer(early, 0);
        deniedLearning(() -> study.respond(oldSessionNewCommand), LearningSessionException.Reason.REVIEW_COMPLETE);
        assertThat(learningSchedules.read(source, question)).isEqualTo(replacement);
        assertThat(count("learning_review_event")).isEqualTo(2);
    }

    @Test void concurrentLearningResponsesGiveOneReviewAndKeepTheExactReplay() throws Exception {
        var fixture = enrolledLearning(2);
        var source = fixture.start().source();
        var overlapping = study.start(fixture.start());
        var form = study.start(new LearningStudyService.Start(UUID.randomUUID(), source, 1));
        var sharedQuestion = form.form().items().get(0).questionRevisionId();
        assertThat(overlapping.form().items().get(0).questionRevisionId()).isEqualTo(sharedQuestion);
        var practice = study.start(new LearningStudyService.Start(UUID.randomUUID(), source, 1,
                LearningReview.Mode.PRACTICE, sharedQuestion));
        var winner = answer(form, 0);
        var peer = answer(overlapping, 0);
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        try {
            var first = workers.submit(() -> {
                loginLearner(username);
                return outer.execute(value -> {
                    var result = study.respond(winner);
                    assertThat(count("learning_review_event")).isOne();
                    reached.countDown();
                    await(release);
                    return result;
                });
            });
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(count("learning_review_event")).isZero();
            assertThat(study.resume(overlapping.form().sessionId()).progress().status()).isEqualTo(LearningReview.Status.ACTIVE);
            var second = workers.submit(() -> {
                loginLearner(username);
                deniedLearning(() -> study.respond(peer), LearningSessionException.Reason.REVIEW_SUPERSEDED);
            });
            var sameCommand = workers.submit(() -> {
                loginLearner(username);
                return study.respond(winner);
            });
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> admin.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
                    ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=?)
                    """, Boolean.class, SCHEMA));
            release.countDown();
            var accepted = first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(accepted.progress().completed()).isTrue();
            assertThat(sameCommand.get(15, TimeUnit.SECONDS)).isEqualTo(accepted);
            assertThat(study.start(fixture.start())).isEqualTo(overlapping);
            var restarted = beans.createBean(LearningStudyServiceImpl.class);
            var mvc = learningHttp(restarted);
            mvc.perform(get("/api/v1/learning/sessions/" + peer.sessionId())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                    .andExpect(jsonPath("$.sessionVersion").value(2))
                    .andExpect(jsonPath("$.completed").value(false))
                    .andExpect(jsonPath("$.reviewedItems").value(0))
                    .andExpect(jsonPath("$.nextItemId").doesNotExist())
                    .andExpect(jsonPath("$.allowedActions.length()").value(1))
                    .andExpect(jsonPath("$.allowedActions[0]").value("READ_SESSION"));
            var latestVersion = new LearningReview.Command(peer.sessionId(), peer.commandId(), 2, peer.sourceItemId(),
                    peer.rating(), peer.response());
            long credit = learningCredit();
            mvc.perform(learningAnswer(latestVersion)).andExpect(status().isConflict()).andExpect(jsonPath("$.type")
                    .value(uk.gegc.quizmaker.shared.api.problem.ErrorTypes.DATA_CONFLICT.toString()));
            assertThat(learningCredit()).isEqualTo(credit);
            assertThat(count("learning_review_event")).isOne();
            assertThat(count("learning_session_response")).isOne();

            // The practice capture survives supersession and still gives the ordinary restart barrier.
            assertThat(restarted.resume(practice.form().sessionId())).isEqualTo(practice);
            assertThatThrownBy(() -> restartLearningCycle(source, sharedQuestion))
                    .isInstanceOfSatisfying(LearningScheduleException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(LearningScheduleException.Reason.ACTIVE_SESSION));
            assertThat(restarted.respond(answer(practice, 0)).nextDueAt()).isNull();
            for (var item : overlapping.form().items()) {
                var restart = restartLearningCycle(source, item.questionRevisionId());
                assertThat(restart.cycleVersion()).isEqualTo(2);
            }
            assertThat(restarted.respond(winner)).isEqualTo(accepted);
            assertThat(count("learning_review_event")).isOne();
            assertThat(count("learning_session_response")).isEqualTo(2);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test void supersessionPreservesAnEarlierAcceptedResponseAndItsReplay() throws Exception {
        var fixture = enrolledLearning(2);
        var remaining = study.start(fixture.start());
        var first = answer(remaining, 0);
        var firstAck = study.respond(first);
        var winner = study.start(new LearningStudyService.Start(UUID.randomUUID(), fixture.start().source(), 1));
        assertThat(winner.form().items().get(0).sourceItemId()).isEqualTo(firstAck.progress().nextItemId());
        study.respond(answer(winner, 0));

        var superseded = study.resume(remaining.form().sessionId()).progress();
        assertThat(superseded.status()).isEqualTo(LearningReview.Status.SUPERSEDED);
        assertThat(superseded.reviewedItems()).isOne();
        assertThat(superseded.version()).isEqualTo(3);
        assertThat(study.respond(first)).isEqualTo(firstAck);
        assertThat(count("learning_session_response")).isEqualTo(2);
        assertThat(count("learning_review_event")).isEqualTo(2);
    }

    @Test void supersessionMigrationReconcilesAnOverlappingV123Session() throws Exception {
        var fixture = enrolledLearning(2);
        var source = fixture.start().source();
        var overlapping = study.start(fixture.start());
        var winning = study.start(new LearningStudyService.Start(UUID.randomUUID(), source, 1));
        var winner = answer(winning, 0);
        var acknowledgement = study.respond(winner);
        // Recreate the exact V123 state after the winning acknowledgement and event committed.
        // The old writer had left the peer's captures unreleased and stored no terminal marker.
        sql.update("UPDATE learning_cycle_session SET released=0 WHERE session_id=UUID_TO_BIN(?)",
                overlapping.form().sessionId().toString());
        sql.execute("ALTER TABLE learning_session_start DROP CHECK chk_learning_start_superseded, DROP COLUMN superseded");
        var migration = new org.springframework.core.io.ClassPathResource("db/migration/V124__release_superseded_learning_sessions.sql");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(migration).execute(dataSource);
        assertThat(study.resume(overlapping.form().sessionId()).progress().status()).isEqualTo(LearningReview.Status.SUPERSEDED);
        for (var item : overlapping.form().items()) {
            assertThat(restartLearningCycle(source, item.questionRevisionId()).cycleVersion()).isEqualTo(2);
        }
        assertThat(study.respond(winner)).isEqualTo(acknowledgement);
        assertThat(count("learning_review_event")).isOne();
        assertThat(count("learning_session_response")).isOne();
    }

    private LearningSchedule.Snapshot restartLearningCycle(LearningSession.Source source, UUID question) {
        var before = learningSchedules.read(source, question);
        return learningSchedules.execute(new LearningScheduleService.Restart(1, UUID.randomUUID(), source, question,
                before.expected(), FsrsManifest.approved())).snapshot();
    }

    @Test void learningResponseRollsBackScheduleProgressAndCreditAfterReachingTheReceiptWrite() throws Exception {
        var fixture = enrolledLearning(1);
        var form = study.start(fixture.start());
        var overlapping = study.start(new LearningStudyService.Start(UUID.randomUUID(), fixture.start().source(), 1));
        UUID peerSession = overlapping.form().sessionId();
        var response = answer(form, 0);
        long credit = learningCredit();
        var mvc = learningHttp(study);
        sql.execute("""
                CREATE TRIGGER fail_learning_response AFTER INSERT ON learning_session_response FOR EACH ROW
                BEGIN
                  IF EXISTS(SELECT 1 FROM learning_review_event WHERE command_id=NEW.command_id)
                     AND EXISTS(SELECT 1 FROM learning_session_start WHERE session_id=NEW.session_id AND reviewed_items=1)
                     AND EXISTS(SELECT 1 FROM learning_session_start WHERE session_id=UUID_TO_BIN('%s') AND superseded=1)
                     AND EXISTS(SELECT 1 FROM learning_cycle_session WHERE session_id=UUID_TO_BIN('%s') AND released=1)
                  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRIVATE_RESPONSE_WRITE_REACHED'; END IF;
                END
                """.formatted(peerSession, peerSession));
        try (var logs = new LearningTransactionLogs()) {
            mvc.perform(learningAnswer(response)).andExpect(status().isServiceUnavailable());
            logs.assertCleanFailure(JdbcLearningStudyRepository.class, "recordResponse",
                    "PRIVATE_RESPONSE_WRITE_REACHED", response.commandId().toString());
        } finally {
            sql.execute("DROP TRIGGER fail_learning_response");
        }
        assertThat(count("learning_review_event")).isZero();
        assertThat(count("learning_session_response")).isZero();
        assertThat(learningCredit()).isEqualTo(credit);
        assertThat(study.resume(form.form().sessionId())).isEqualTo(form);
        assertThat(study.resume(peerSession)).isEqualTo(overlapping);

        var incompatible = new TransactionTemplate(transactions);
        incompatible.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        deniedLearning(() -> incompatible.executeWithoutResult(value -> study.respond(response)),
                LearningSessionException.Reason.UNSUPPORTED_TRANSACTION);
        assertThat(learningCredit()).isEqualTo(credit);
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(transaction -> {
            assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
            assertThat(study.respond(response).progress().completed()).isTrue();
            assertThat(count("learning_review_event")).isOne();
            assertThat(count("learning_session_response")).isOne();
            assertThat(study.resume(peerSession).progress().status()).isEqualTo(LearningReview.Status.SUPERSEDED);
            transaction.setRollbackOnly();
        });
        assertThat(learningCredit()).isEqualTo(credit);
        assertThat(count("learning_review_event")).isZero();
        assertThat(count("learning_session_response")).isZero();
        assertThat(study.resume(form.form().sessionId())).isEqualTo(form);
        assertThat(study.resume(peerSession)).isEqualTo(overlapping);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_cycle_session WHERE released=1", Integer.class)).isZero();
        mvc.perform(learningAnswer(response)).andExpect(status().isOk());
        assertThat(study.resume(peerSession).progress().status()).isEqualTo(LearningReview.Status.SUPERSEDED);
        assertThat(count("learning_review_event")).isOne();
    }

    @Test void learningRateDenialAndStoreFailureCannotChangeTheScheduleOrProgress() throws Exception {
        var fixture = enrolledLearning(1);
        var form = study.start(fixture.start());
        var request = learningAnswer(answer(form, 0));
        var mvc = learningHttp(study);
        sql.update("UPDATE learning_command_budget SET credit_units=0");
        mvc.perform(request).andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"));
        sql.update("UPDATE learning_command_budget SET credit_units=60000000");
        sql.execute("""
                CREATE TRIGGER fail_learning_credit BEFORE UPDATE ON learning_command_budget FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic outage'
                """);
        try {
            mvc.perform(request).andExpect(status().isServiceUnavailable());
        } finally {
            sql.execute("DROP TRIGGER fail_learning_credit");
        }
        assertThat(count("learning_review_event")).isZero();
        assertThat(count("learning_session_response")).isZero();
        assertThat(study.resume(form.form().sessionId())).isEqualTo(form);
        assertThat(learningCredit()).isEqualTo(60_000_000L);
        mvc.perform(request).andExpect(status().isOk());
        assertThat(learningCredit()).isZero();
        mvc.perform(request).andExpect(status().isOk());
        assertThat(count("learning_review_event")).isOne();
    }

    @Test void learningResponseReadsOneImmutableQuestionAcrossDifferentFormSizes() {
        var fixture = enrolledLearning(6);
        var small = study.start(new LearningStudyService.Start(UUID.randomUUID(), fixture.start().source(), 2));
        int smallStatements = measure(() -> assertThat(study.respond(answer(small, 0)).progress().reviewedItems()).isOne());
        assertThat(CountingSource.selectedQuestions.get()).isOne();
        var large = study.start(new LearningStudyService.Start(UUID.randomUUID(), fixture.start().source(), 5));
        assertThat(large.form().items()).hasSize(5);
        int largeStatements = measure(() -> assertThat(study.respond(answer(large, 0)).progress().reviewedItems()).isOne());
        assertThat(largeStatements).isEqualTo(smallStatements);
        assertThat(CountingSource.selectedQuestions.get()).isOne();
    }

    private record EnrolledLearning(LearningStudyService.Start start, List<UUID> questions) { }

    private EnrolledLearning enrolledLearning(int count) {
        var drafts = java.util.stream.IntStream.range(0, count).mapToObj(i -> question(QuestionType.TRUE_FALSE, null)).toArray(UUID[]::new);
        var publication = service.publish(command(0, drafts)).publication().revision();
        var revision = service.read(quizId, publication.id()).content();
        var source = new LearningSession.Source(1, quizId, revision.id(), revision.schemaVersion());
        var questions = revision.items().stream().map(i -> i.question().id()).toList();
        loginLearner(username);
        study.enroll(new LearningStudyService.Enroll(UUID.randomUUID(), source, questions));
        return new EnrolledLearning(new LearningStudyService.Start(UUID.randomUUID(), source, count), questions);
    }

    private MockMvc learningHttp(LearningStudyService application) {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new LearningSessionController(application, json)).setControllerAdvice(new LearningSessionErrorHandler(),
                new uk.gegc.quizmaker.shared.api.advice.GlobalExceptionHandler()).build();
    }

    private MockHttpServletRequestBuilder explicitStart(EnrolledLearning fixture, UUID command,
            LearningSessionApi.SelectionMode mode, UUID question) throws Exception {
        var source = fixture.start().source();
        var selection = new LearningSessionApi.Selection(mode, question);
        var request = new LearningSessionApi.StartRequest(command,
                new LearningSessionApi.SourceRef(LearningSessionApi.SourceKind.QUIZ_REVISION, source.quizId(), source.quizRevisionId(),
                        source.revisionSchemaVersion()), new LearningSessionApi.Configuration("fsrs/v1", 1), 1, selection);
        return post("/api/v1/learning/sessions").contentType("application/json").content(json.writeValueAsBytes(request));
    }

    private LearningFormService.View sessionOf(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        var body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return study.resume(UUID.fromString(json.readTree(body).path("sessionId").asText()));
    }

    private LearningReview.Command answer(LearningFormService.View form, int position) {
        return new LearningReview.Command(form.form().sessionId(), UUID.randomUUID(), position + 1,
                form.form().items().get(position).sourceItemId(), LearningSchedule.Rating.GOOD,
                json.createObjectNode().put("type", "TRUE_FALSE").put("answer", true));
    }

    private static LearningReview.Command answerWith(LearningReview.Command command, com.fasterxml.jackson.databind.JsonNode response) {
        return new LearningReview.Command(command.sessionId(), command.commandId(), command.expectedVersion(),
                command.sourceItemId(), command.rating(), response);
    }

    private MockHttpServletRequestBuilder learningAnswer(LearningReview.Command command) {
        var request = json.createObjectNode().put("commandId", command.commandId().toString())
                .put("expectedSessionVersion", command.expectedVersion()).put("sourceItemId", command.sourceItemId().toString())
                .put("rating", command.rating().name()).set("response", command.response());
        return post("/api/v1/learning/sessions/" + command.sessionId() + "/responses").contentType("application/json").content(request.toString());
    }

    private long learningCredit() {
        return sql.queryForObject("SELECT credit_units FROM learning_command_budget WHERE learner_id=UUID_TO_BIN(?)",
                Long.class, ownerId.toString());
    }

    @Test void concurrentFirstEnrollmentReplaysOneRealCycleAndReceipt() throws Exception {
        UUID question = question(QuestionType.TRUE_FALSE,null);
        var pub = service.publish(command(0,question)).publication().revision();
        var content = service.read(quizId,pub.id()).content();
        var source = new LearningSession.Source(1,quizId,pub.id(),content.schemaVersion());
        var cmd = new LearningStudyService.Enroll(UUID.randomUUID(),source,List.of(content.items().get(0).question().id()));
        concurrentLearningReplay(() -> study.enroll(cmd));
        for (String table : List.of("learning_enrollment", "learning_enrollment_item", "learning_schedule_cycle",
                "learning_prepared_publication", "learning_schedule_receipt")) assertThat(count(table)).isOne();

        var start = new LearningStudyService.Start(UUID.randomUUID(), source, 1);
        concurrentLearningReplay(() -> study.start(start));
        for (String table : List.of("learning_session", "learning_form", "learning_cycle_session",
                "learning_session_start", "learning_session_create_receipt")) assertThat(count(table)).isOne();
    }

    private <T> void concurrentLearningReplay(Supplier<T> command) throws Exception {
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var pool = Executors.newFixedThreadPool(2);
        var gate = new Gate();
        try {
            var holder = pool.submit(() -> {
                loginLearner(username);
                try {
                    return outer.execute(status -> {
                        var result = command.get();
                        gate.entered.countDown();
                        await(gate.release);
                        return result;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            await(gate.entered);
            var peer = pool.submit(() -> {
                loginLearner(username);
                try { return command.get(); } finally { SecurityContextHolder.clearContext(); }
            });
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> admin.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
                    ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=?)
                    """, Boolean.class, SCHEMA));
            gate.release.countDown();
            assertThat(holder.get(15, TimeUnit.SECONDS)).isEqualTo(peer.get(15, TimeUnit.SECONDS));
        } finally { gate.release.countDown(); pool.shutdownNow(); }
    }

    @Test void firstEnrollmentPublicationReadLockTimeoutCanReplayAfterTheLockIsReleased() throws Exception {
        var publication = service.publish(command(0, question(QuestionType.TRUE_FALSE, null))).publication().revision();
        var revision = service.read(quizId, publication.id()).content();
        grant(ownerId, "QUIZ_READ");
        loginLearner(username);
        var input = new LearningSessionApi.EnrollRequest(UUID.randomUUID(),
                new LearningSessionApi.SourceRef(LearningSessionApi.SourceKind.QUIZ_REVISION, quizId, revision.id(), 1),
                new LearningSessionApi.Configuration("fsrs/v1", 1), List.of(revision.items().get(0).question().id()));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new LearningSessionController(study, json))
                .setControllerAdvice(new LearningSessionErrorHandler()).build();
        var request = post("/api/v1/learning/enrollments").contentType("application/json").content(json.writeValueAsBytes(input));
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("""
                    SELECT rp.role_id FROM role_permissions rp JOIN permissions p ON p.permission_id=rp.permission_id
                    JOIN user_roles ur ON ur.role_id=rp.role_id
                    WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name='QUIZ_READ' FOR UPDATE OF rp
                    """)) {
                lock.setString(1, ownerId.toString());
                try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                // The Learning verifier locks QUIZ_UPDATE. The nested publication read also locks QUIZ_READ.
                faults.shortPublicationLockWait = true;
                try (var logs = new LearningTransactionLogs()) {
                    mvc.perform(request).andExpect(status().isConflict())
                            .andExpect(jsonPath("$.type").value(uk.gegc.quizmaker.shared.api.problem.ErrorTypes.DATA_CONFLICT.toString()));
                    logs.assertCleanFailure(JdbcQuizPublicationRepository.class, "actor", ownerId.toString(), input.commandId().toString());
                }
            } finally {
                faults.shortPublicationLockWait = false;
                blocker.rollback();
            }
        }
        for (String table : List.of("learning_command_budget", "learning_prepared_publication", "question_revision_contract_bindings",
                "learning_schedule_cycle", "learning_schedule_receipt", "learning_enrollment", "learning_enrollment_item"))
            assertThat(count(table)).as(table).isZero();
        String enrolled = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(enrolled);
        assertThat(count("learning_schedule_cycle")).isOne();
        assertThat(count("learning_enrollment")).isOne();
        assertThat(sql.queryForObject("SELECT credit_units FROM learning_command_budget", Long.class)).isEqualTo(19 * 60_000_000L);
    }

    @Test void firstLearningHttpEnrollmentPreparesAllQuestionsAndRestoresTheSavedSession() throws Exception {
        UUID selected = question(QuestionType.MCQ_SINGLE, null);
        UUID other = question(QuestionType.TRUE_FALSE, null);
        var pub = service.publish(command(0, selected, other)).publication().revision();
        var source = service.read(quizId, pub.id()).content();
        var question = source.items().get(0).question();
        loginLearner(username);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new LearningSessionController(study, json))
                .setControllerAdvice(new LearningSessionErrorHandler()).build();
        var input = json.createObjectNode();
        input.put("commandId", UUID.randomUUID().toString());
        input.putObject("source").put("kind", "QUIZ_REVISION").put("quizId", quizId.toString())
                .put("quizRevisionId", pub.id().toString()).put("revisionSchemaVersion", source.schemaVersion());
        input.putObject("configuration").put("strategy", "fsrs/v1").put("version", 1);
        input.putArray("questionRevisionIds").add(question.id().toString());
        String root = "/api/v1/learning";
        var enrollment = post(root + "/enrollments")
                .contentType("application/json").content(input.toString());
        var enrolled = mvc.perform(enrollment).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(enrolled).at("/items/0/questionRevisionId").asText()).isEqualTo(question.id().toString());
        assertThat(count("question_revision_contract_bindings")).isEqualTo(2);
        assertThat(count("learning_schedule_cycle")).isOne();
        long afterEnrollment = sql.queryForObject("SELECT credit_units FROM learning_command_budget", Long.class);
        input.remove("questionRevisionIds");
        input.put("commandId", UUID.randomUUID().toString()).put("limit", 3);
        var start = post(root + "/sessions")
                .contentType("application/json").content(input.toString());
        var shown = mvc.perform(start).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var body = json.readTree(shown);
        assertThat(body.at("/items/0/content/kind").asText()).isEqualTo("CHOICES");
        assertThat(body.at("/items/0/content/options").size()).isEqualTo(question.options().size());
        assertThat(shown).doesNotContain("\"correct\"", "\"hint\"", "\"explanation\"", "\"learnerId\"");
        assertThat(body.path("outcome").asText()).isEqualTo("READY");
        var read = get(root + "/sessions/" + body.path("sessionId").asText());
        assertThat(mvc.perform(read).andReturn().getResponse().getContentAsString()).isEqualTo(shown);
        assertThat(mvc.perform(start).andReturn().getResponse().getContentAsString()).isEqualTo(shown);
        input.put("commandId", UUID.randomUUID().toString()).put("limit", 21);
        mvc.perform(post(root + "/sessions").contentType("application/json").content(input.toString()))
                .andExpect(status().isBadRequest());
        assertThat(count("learning_session")).isOne();
        long afterReadAndReplay = sql.queryForObject("SELECT credit_units FROM learning_command_budget", Long.class);
        // Frozen admission clock. One token per accepted command, and none for capture, reads or replay.
        assertThat(afterEnrollment).isEqualTo(19 * 60_000_000L);
        assertThat(afterReadAndReplay).isEqualTo(18 * 60_000_000L);
    }

    private record SavedSession(UUID revisionId, UUID questionId, MockMvc mvc,
                                List<MockHttpServletRequestBuilder> routes, String healthy) { }

    private SavedSession savedLearningSession() throws Exception {
        var publication = service.publish(command(0, question(QuestionType.TRUE_FALSE, null))).publication().revision();
        var revision = service.read(quizId, publication.id()).content();
        var source = new LearningSession.Source(1, quizId, revision.id(), 1);
        var question = revision.items().get(0).question().id();
        loginLearner(username);
        study.enroll(new LearningStudyService.Enroll(UUID.randomUUID(), source, List.of(question)));
        var start = new LearningStudyService.Start(UUID.randomUUID(), source, 1);
        var session = study.start(start).form().sessionId();
        var request = new LearningSessionApi.StartRequest(start.commandId(),
                new LearningSessionApi.SourceRef(LearningSessionApi.SourceKind.QUIZ_REVISION, quizId, revision.id(), 1),
                new LearningSessionApi.Configuration("fsrs/v1", 1), 1, null);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new LearningSessionController(study, json))
                .setControllerAdvice(new LearningSessionErrorHandler()).build();
        var routes = List.of(get("/api/v1/learning/sessions/" + session),
                post("/api/v1/learning/sessions").contentType("application/json").content(json.writeValueAsBytes(request)));
        String healthy = mvc.perform(routes.get(0)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new SavedSession(revision.id(), question, mvc, routes, healthy);
    }

    @ParameterizedTest(name = "saved binding corruption, row deleted = {0}")
    @ValueSource(booleans = {false, true})
    void missingOrChangedBindingInSavedFormIsAnIntegrityFailure(boolean deleteBinding) throws Exception {
        var saved = savedLearningSession();
        String question = saved.questionId().toString();
        if (deleteBinding) {
            assertThat(sql.update("DELETE FROM question_revision_contract_bindings WHERE question_revision_id=UUID_TO_BIN(?)",
                    question)).isOne();
        } else {
            assertThat(sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=2 WHERE question_revision_id=UUID_TO_BIN(?)",
                    question)).isOne();
        }
        for (var route : saved.routes()) saved.mvc().perform(route).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        var other = "peer-" + UUID.randomUUID().toString().substring(0, 8);
        user(other, true);
        loginLearner(other);
        for (var route : saved.routes()) saved.mvc().perform(route).andExpect(status().isNotFound());
        loginLearner(username);
        if (deleteBinding) publishedContracts.bind(quizId, saved.revisionId());
        else sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=1 WHERE question_revision_id=UUID_TO_BIN(?)",
                question);
        for (var route : saved.routes()) assertThat(saved.mvc().perform(route).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo(saved.healthy());
        assertThat(count("learning_form")).isOne();
        assertThat(count("learning_cycle_session")).isOne();
    }

    @Test void resumeAndStartReplaySeparateIntegrityStorageAndAccessFailures() throws Exception {
        var saved = savedLearningSession();
        var question = saved.questionId();
        var mvc = saved.mvc();
        var routes = saved.routes();
        var healthy = saved.healthy();
        String hash = sql.queryForObject("SELECT binding_hash FROM question_revision_contract_bindings WHERE question_revision_id=UUID_TO_BIN(?)",
                String.class, question.toString());
        sql.update("UPDATE question_revision_contract_bindings SET binding_hash=REPEAT('0',64) WHERE question_revision_id=UUID_TO_BIN(?)",
                question.toString());
        for (var route : routes) mvc.perform(route).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
        sql.update("UPDATE question_revision_contract_bindings SET binding_hash=? WHERE question_revision_id=UUID_TO_BIN(?)",
                hash, question.toString());
        String content = sql.queryForObject("SELECT content FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                String.class, question.toString());
        sql.update("UPDATE question_revisions SET content=JSON_OBJECT() WHERE id=UUID_TO_BIN(?)", question.toString());
        for (var route : routes) mvc.perform(route).andExpect(status().isInternalServerError());
        sql.update("UPDATE question_revisions SET content=? WHERE id=UUID_TO_BIN(?)", content, question.toString());
        CountingSource.failedQuestionReads.set(0);
        CountingSource.failQuestionRead = true;
        try {
            for (var route : routes) mvc.perform(route).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503));
        } finally { CountingSource.failQuestionRead = false; }
        assertThat(CountingSource.failedQuestionReads.get()).isEqualTo(routes.size());
        for (var route : routes) assertThat(mvc.perform(route).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo(healthy);
        assertThat(count("learning_session_start")).isOne();
        assertThat(count("learning_cycle_session")).isOne();
        sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
        for (var route : routes) mvc.perform(route).andExpect(status().isNotFound());
    }

    @Test void repeatedQuestionEnrollsOnceAndTheFormReadsItsExactOccurrence() {
        UUID draftQuestion = question(QuestionType.TRUE_FALSE, null);
        // The revision store permits repeated occurrences; the ordinary publisher takes distinct draft IDs.
        var repeated = tx.execute(status -> {
            var publications = beans.getBean(QuizPublicationRepository.class);
            var draft = publications.draft(quizId, true).orElseThrow();
            var question = publications.lockQuestions(quizId).get(0);
            assertThat(question.id()).isEqualTo(draftQuestion);
            var material = content.materialize(draft, List.of(question, question), Map.of(), clock.instant());
            var revision = material.revision();
            // Opposite UUID order makes accidental primary-key ordering observable.
            var first = new QuizRevision.Item(new UUID(0, 2), 0, revision.items().get(0).question());
            var second = new QuizRevision.Item(new UUID(0, 1), 1, first.question());
            var saved = new QuizRevision(revision.id(), quizId, ownerId, 1, revision.title(), revision.description(),
                    revision.createdAt(), List.of(first, second));
            beans.getBean(QuizRevisionRepository.class).insert(saved);
            publications.insert(new Revision(saved.id(), quizId, ownerId, 1, material.hash(), saved.createdAt()), List.of());
            return saved;
        });
        loginLearner(username);
        var source = new LearningSession.Source(1, quizId, repeated.id(), 1);
        var first = repeated.items().get(0);
        var second = repeated.items().get(1);
        var enrollment = new LearningStudyService.Enroll(UUID.randomUUID(), source, List.of(first.question().id()));
        var receipt = study.enroll(enrollment);
        assertThat(receipt.items()).hasSize(1);
        assertThat(study.enroll(enrollment)).isEqualTo(receipt);
        var start = new LearningStudyService.Start(UUID.randomUUID(), source, 2);
        var view = study.start(start);
        assertThat(view.form().items()).extracting(LearningForm.Item::sourceItemId).containsExactly(first.id());
        assertThat(study.start(start)).isEqualTo(view);
        assertThat(study.resume(view.form().sessionId())).isEqualTo(view);
        assertThat(count("learning_schedule_cycle")).isOne();
        assertThat(count("learning_cycle_session")).isOne();

        // A retained form can legitimately reference the other occurrence of the same question.
        var session = sessions.create(new LearningSessionService.Create(1, UUID.randomUUID(), source, FsrsManifest.approved())).session();
        var descriptor = view.form().items().get(0);
        var other = new LearningForm.Item(second.id(), second.question().id(), descriptor.questionSchemaVersion(),
                descriptor.response(), descriptor.optionIds());
        var formService = beans.getBean(LearningFormService.class);
        var saved = formService.materialize(new LearningFormService.Materialize(1, session.id(), List.of(other))).view();
        assertThat(formService.read(session.id())).isEqualTo(saved);
        assertThat(saved.items()).extracting(item -> item.item().sourceItemId()).containsExactly(second.id());
    }

    @Test void damagedUnselectedContractRejectsFirstEnrollmentWithoutPartialWrites() {
        UUID selected = question(QuestionType.TRUE_FALSE, null);
        UUID other = question(QuestionType.TRUE_FALSE, null);
        var pub = service.publish(command(0, selected, other)).publication().revision();
        var source = service.read(quizId, pub.id()).content();
        loginLearner(username);
        // Establish legitimate bindings through the real author operation; damage the unselected evidence explicitly.
        publishedContracts.bind(quizId, pub.id());
        var damaged = source.items().get(1).question().id().toString();
        sql.update("UPDATE question_revision_contract_bindings SET binding_hash=REPEAT('0',64) WHERE question_revision_id=UUID_TO_BIN(?)", damaged);
        var cmd = new LearningStudyService.Enroll(UUID.randomUUID(),
                new LearningSession.Source(1,quizId,pub.id(),source.schemaVersion()), List.of(source.items().get(0).question().id()));
        deniedLearning(() -> study.enroll(cmd), LearningSessionException.Reason.CORRUPT);
        for (String table : List.of("learning_prepared_publication","learning_enrollment","learning_schedule_slot",
                "learning_schedule_cycle","learning_command_budget")) assertThat(count(table)).isZero();
        assertThat(count("question_revision_contract_bindings")).isEqualTo(2);
    }

    @Test void learningFormKeepsRevisionAAfterRealAuthorEditAndPublicationB() {
        UUID question = question(QuestionType.TRUE_FALSE, null);
        var a = service.publish(command(0, question));
        var published = service.read(quizId, a.publication().revision().id()).content();
        var sourceItem = published.items().get(0);
        // Exact response binding is a fixture: #647 does not prove the separate registry writer.
        sql.update("INSERT INTO question_revision_contract_bindings VALUES(UUID_TO_BIN(?),'question.true-false.response',1,'question.true-false.binary',1,?)",
                sourceItem.question().id().toString(), new uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding(
                    sourceItem.question().id(), new ContractVersion("question.true-false.response", 1), new ContractVersion("question.true-false.binary", 1)).fingerprint());
        var named = new NamedParameterJdbcTemplate(sql);
        loginLearner(username);
        var session = sessions.create(new LearningSessionService.Create(1, UUID.randomUUID(),
                new LearningSession.Source(1, quizId, published.id(), published.schemaVersion()), FsrsManifest.approved())).session();
        var selected = new LearningForm.Item(sourceItem.id(), sourceItem.question().id(), 1,
                new ContractVersion("question.true-false.response", 1), List.of());
        // Participant-safe source contract fixture, reading only the requested retained revision.
        LearningFormContent safeContent = (saved, items) -> {
            var exact = service.read(quizId, saved.source().quizRevisionId()).content().items().get(0);
            return List.of(new LearningFormContent.PresentedItem(selected, exact.question().questionText(), new ParticipantContent.TrueFalse()));
        };
        LearningFormService learning = new LearningFormServiceImpl(sessions, new JdbcLearningFormRepository(named),
                List.of(safeContent), clock, metrics, transactions);
        var command = new LearningFormService.Materialize(1, session.id(), List.of(selected));
        var first = learning.materialize(command);
        edit(question, "New published question");
        var b = service.publish(command(1, question));
        assertThat(b.publication().revision().id()).isNotEqualTo(published.id());
        assertThat(service.current(quizId)).contains(b.publication());
        assertThat(learning.read(session.id())).isEqualTo(first.view());
        assertThat(learning.materialize(command).replayed()).isTrue();
        assertThat(learning.read(session.id()).items().get(0).questionText()).isEqualTo(sourceItem.question().questionText());
        assertThat(sessions.read(session.id())).isEqualTo(session);
        assertThat(count("attempts")).isZero();
    }

    @Test void learningAuthorAccessRequiresExactPublicationAndCurrentOwnershipAndPermission() {
        var publication = service.publish(command(0, question(QuestionType.TRUE_FALSE, null))).publication().revision();
        var source = new LearningSession.Source(1, quizId, publication.id(), 1);
        var command = new LearningSessionService.Create(1, UUID.randomUUID(), source, FsrsManifest.approved());
        loginLearner(username);
        assertThat(dueSelection.select(new LearningDueSelectionService.Select(1, source, 5, null)).outcome())
                .isEqualTo(LearningDueSelection.Outcome.EMPTY);
        var first = sessions.create(command);
        assertThat(first.replayed()).isFalse();
        assertThat(sessions.create(command).session()).isEqualTo(first.session());
        assertThat(sessions.read(first.session().id())).isEqualTo(first.session());
        var unpublished = UUID.randomUUID();
        sql.update("""
                INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at)
                SELECT UUID_TO_BIN(?),quiz_id,owner_id,schema_version,title,created_at FROM quiz_revisions WHERE id=UUID_TO_BIN(?)
                """, unpublished.toString(), publication.id().toString());
        for (var unavailable : List.of(new LearningSession.Source(1, quizId, unpublished, 1),
                new LearningSession.Source(1, quizId, publication.id(), 2),
                new LearningSession.Source(1, UUID.randomUUID(), publication.id(), 1))) {
            deniedLearning(() -> sessions.create(new LearningSessionService.Create(1, UUID.randomUUID(), unavailable,
                    FsrsManifest.approved())), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        }

        String other = "learner-" + UUID.randomUUID().toString().substring(0, 12);
        UUID foreign = user(other, true);
        grant(foreign, "QUIZ_MODERATE");
        sql.update("UPDATE quizzes SET visibility='PUBLIC',status='PUBLISHED' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        loginLearner(other);
        deniedLearning(() -> sessions.read(first.session().id()), LearningSessionException.Reason.UNAVAILABLE);
        deniedLearning(() -> sessions.read(UUID.randomUUID()), LearningSessionException.Reason.UNAVAILABLE);
        deniedLearning(() -> sessions.create(command), LearningSessionException.Reason.SOURCE_UNAVAILABLE);

        // Transferring current ownership does not transfer the old author's immutable publication to the new owner.
        sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?) WHERE quiz_id=UUID_TO_BIN(?)", foreign.toString(), quizId.toString());
        deniedLearning(() -> sessions.create(command), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        loginLearner(username);
        deniedLearning(() -> sessions.create(command), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?) WHERE quiz_id=UUID_TO_BIN(?)", ownerId.toString(), quizId.toString());
        assertThat(sessions.create(command).replayed()).isTrue();
        sql.update("UPDATE quizzes SET is_deleted=TRUE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        deniedLearning(() -> sessions.create(command), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        sql.update("UPDATE quizzes SET is_deleted=FALSE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
        deniedLearning(() -> sessions.read(first.session().id()), LearningSessionException.Reason.UNAVAILABLE);
        sql.update("UPDATE users SET is_active=TRUE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
        assertThat(sessions.read(first.session().id())).isEqualTo(first.session());
        SecurityContextHolder.clearContext();
        deniedLearning(() -> sessions.create(command), LearningSessionException.Reason.UNAUTHENTICATED);
        var guest = new org.springframework.security.authentication.AnonymousAuthenticationToken("test", "anonymousUser",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(guest);
        deniedLearning(() -> sessions.create(command), LearningSessionException.Reason.UNAUTHENTICATED);
        loginLearner(username);
        sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
        deniedLearning(() -> sessions.read(first.session().id()), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        deniedLearning(() -> sessions.create(command), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
        assertThat(count("learning_session")).isOne();
        assertThat(count("learning_session_create_receipt")).isOne();
        assertThat(count("learning_command_budget")).isOne();
    }

    private static void deniedLearning(Runnable action, LearningSessionException.Reason expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LearningSessionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(expected));
    }

    @Test void revocationWaitsForAdmittedSessionAndAnotherAuthorCanCommit() throws Exception {
        var first = publishForLearning(username, quizId, 1);
        String peer = "peer-" + UUID.randomUUID().toString().substring(0, 12);
        var second = publishForLearning(peer, anotherQuiz(user(peer, true)), 1);
        var gate = new Gate();
        var pool = Executors.newFixedThreadPool(3);
        try {
            var holder = pool.submit(() -> {
                loginLearner(username);
                var outer = new TransactionTemplate(transactions);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                try { return outer.execute(status -> {
                    var result = sessions.create(first);
                    gate.entered.countDown();
                    await(gate.release);
                    return result;
                }); } finally { SecurityContextHolder.clearContext(); }
            });
            await(gate.entered);
            var revoke = pool.submit(() -> sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> admin.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='user_roles')
                    """, Boolean.class, SCHEMA));
            var peerResult = pool.submit(() -> {
                loginLearner(peer);
                try { return sessions.create(second); }
                finally { SecurityContextHolder.clearContext(); }
            }).get(10, TimeUnit.SECONDS);
            assertThat(peerResult.replayed()).isFalse();
            assertThat(gate.release.getCount()).isOne();
            gate.release.countDown();
            var winner = holder.get(10, TimeUnit.SECONDS);
            assertThat(winner.replayed()).isFalse();
            assertThat(revoke.get(10, TimeUnit.SECONDS)).isPositive();
            loginLearner(username);
            deniedLearning(() -> sessions.create(first), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
            deniedLearning(() -> sessions.read(winner.session().id()), LearningSessionException.Reason.SOURCE_UNAVAILABLE);
            assertThat(count("learning_session_create_receipt")).isEqualTo(2);
        } finally { gate.release.countDown(); pool.shutdownNow(); }
    }

    @Test void learningSessionSqlIsBoundedAcrossAuthorsRevisionsAndPermissionFanout() {
        int[] small = measureLearningSource(username, quizId, 1);
        String second = "study-" + UUID.randomUUID().toString().substring(0, 12);
        UUID secondOwner = user(second, true);
        for (int i = 0; i < 5; i++) grant(secondOwner, "QUIZ_UPDATE");
        int[] large = measureLearningSource(second, anotherQuiz(secondOwner), 12);
        assertThat(sql.queryForList("SELECT credit_units FROM learning_command_budget", Long.class))
                .containsExactly(19 * 60_000_000L, 19 * 60_000_000L);
        assertThat(large).containsExactly(small);
        assertThat(small[0]).isBetween(1, 18);
        assertThat(small[1]).isBetween(1, 10);
        assertThat(small[2]).isBetween(1, 12);
        System.out.printf("Learning author SQL (create/read/replay): one item %s; twelve items %s%n",
                Arrays.toString(small), Arrays.toString(large));
    }

    private int[] measureLearningSource(String actor, UUID quiz, int size) {
        var command = publishForLearning(actor, quiz, size);
        loginLearner(actor);
        var created = new AtomicReference<LearningSession>();
        int create = measure(() -> created.set(sessions.create(command).session()));
        int read = measure(() -> assertThat(sessions.read(created.get().id())).isEqualTo(created.get()));
        int replay = measure(() -> assertThat(sessions.create(command)).isEqualTo(new LearningSessionService.Result(created.get(), true)));
        return new int[]{create, read, replay};
    }

    private LearningSessionService.Create publishForLearning(String actor, UUID quiz, int size) {
        authenticate(actor);
        List<UUID> questions = new ArrayList<>();
        for (int i = 0; i < size; i++) questions.add(questionFor(actor, quiz, QuestionType.TRUE_FALSE, null));
        var published = service.publish(new Command(1, quiz, UUID.randomUUID(), 0, questions)).publication().revision();
        return new LearningSessionService.Create(1, UUID.randomUUID(),
                new LearningSession.Source(1, quiz, published.id(), 1), FsrsManifest.approved());
    }

    private static void loginLearner(String name) {
        var principal = org.springframework.security.core.userdetails.User.withUsername(name)
                .password("unused").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
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
        CountingSource.calls.set(0); CountingSource.selectedQuestions.set(0); CountingSource.enabled = true;
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
        volatile boolean shortPublicationLockWait;
        final Set<Long> gated = ConcurrentHashMap.newKeySet();
        Faults(JdbcTemplate sql) { this.sql = sql; }
        void reset() { fail = null; loseAcknowledgement = false; failConfirmation = false; shortPublicationLockWait = false; reached.set(false); contenders = null; afterOwnedQuiz = null; holdPublication = null; beforeQuestions = null; gated.clear(); }
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
            if (shortPublicationLockWait && method.equals("actor")) {
                int original = sql.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                sql.execute("SET SESSION innodb_lock_wait_timeout=1");
                try { return call.proceed(); }
                finally { sql.execute("SET SESSION innodb_lock_wait_timeout=" + original); }
            }
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
        static volatile boolean failQuestionRead;
        static final AtomicInteger failedQuestionReads = new AtomicInteger();
        static final AtomicInteger selectedQuestions = new AtomicInteger();
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
                        if (failQuestionRead && m.getName().equals("executeQuery") && args != null && args.length > 0
                                && args[0] instanceof String query && query.contains("FROM question_revisions WHERE id IN")) {
                            failedQuestionReads.incrementAndGet();
                            throw new SQLTransientConnectionException("LEARNING_PRIVATE_CONTENT_READ", "08001");
                        }
                        Object executed = invoke(statement, m, a);
                        if (enabled && executed instanceof ResultSet rows && args != null && args.length > 0
                                && args[0] instanceof String query && query.contains("FROM question_revisions WHERE id IN")) {
                            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class},
                                    (ignored, op, values) -> {
                                        Object read = invoke(rows, op, values);
                                        if (op.getName().equals("next") && Boolean.TRUE.equals(read)) selectedQuestions.incrementAndGet();
                                        return read;
                                    });
                        }
                        return executed;
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
