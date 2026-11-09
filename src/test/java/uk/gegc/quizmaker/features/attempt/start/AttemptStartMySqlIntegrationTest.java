package uk.gegc.quizmaker.features.attempt.start;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.FixedFormMaterializer;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.application.impl.FixedFormMaterializerImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.FormOptionOrderServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.Configuration;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;
import uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFixedFormRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFormOptionOrderRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.media.application.SavedImageStorage;
import uk.gegc.quizmaker.features.media.application.impl.RetainedImageServiceImpl;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.impl.*;
import uk.gegc.quizmaker.features.question.config.BaselineQuestionContractConfig;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizPracticeSourceServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionPublicationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.Command;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;
import uk.gegc.quizmaker.features.quiz.infra.repository.*;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.Reason.*;

/** Real publication and five-family bindings feed the real atomic start. Only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({uk.gegc.quizmaker.features.attempt.infra.repository.JdbcNavigationRepository.class, QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, PublicationContent.class, RetainedImageServiceImpl.class,
        AppPermissionEvaluator.class, QuestionHandlerFactory.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        QuestionContractRegistryImpl.class, QuestionRevisionContractServiceImpl.class, JdbcQuestionContractBindingRepository.class,
        PublishedQuestionContractServiceImpl.class, BaselineQuestionContractConfig.class,
        FixedFormMaterializerImpl.class, FormOptionOrderServiceImpl.class,
        JdbcFixedFormRepository.class, JdbcFormOptionOrderRepository.class,
        AttemptStartServiceImpl.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        AttemptStartMySqlIntegrationTest.Config.class})
class AttemptStartMySqlIntegrationTest {
    static final String SCHEMA = "attempt_start_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    static boolean schemaCreated;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true;
        p.add("spring.datasource.url", () -> base + SCHEMA + options);
        p.add("spring.datasource.username", () -> user); p.add("spring.datasource.password", () -> password);
    }
    @AfterAll static void dropDatabase() { if (schemaCreated) admin.execute("DROP DATABASE " + SCHEMA); }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaStorageProperties mediaProperties() { return new MediaStorageProperties(); }
        @Bean SavedImageStorage storage() {
            return new SavedImageStorage() {
                public Source inspect(String key) { throw new AssertionError("Text fixture must not call storage"); }
                public void retain(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
                public void verify(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
            };
        }
        @Bean static BeanPostProcessor measuredSql() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof StartDataSource) ? new StartDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired PublishedQuestionContractService binding;
    @Autowired FixedFormMaterializer materializer;
    @Autowired FixedFormRepository formRepository;
    @Autowired QuizRevisionPublicationService publications;
    @Autowired QuestionRevisionContractService contracts;
    @Autowired QuestionContractRegistry registry;
    @Autowired QuizRevisionRepository revisions;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired AttemptStartService starts;
    @Autowired AuthenticatedSubjectResolver identity;
    @Autowired QuizPracticeSourceService sources;
    @Autowired AttemptPresetService presets;
    @Autowired AttemptPolicyResolver policies;
    @Autowired PolicySnapshotService snapshots;
    @Autowired QuestionSelectionService selector;
    @Autowired AttemptSubjectService subjects;
    @Autowired AttemptLifecycleService lifecycle;
    @Autowired ExecutionTraceService trace;
    @Autowired Clock clock;
    @Autowired SimpleMeterRegistry metrics;
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    static final List<String> TARGET_TABLES = List.of("attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form_selection", "attempt_form", "attempt_policy_snapshot");
    StartDataSource observer() { return (StartDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
        username = "start-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username, true);
        quizId = newQuiz();
        authenticate(username);
    }

    @AfterEach void cleanup() {
        observer().reset(); SecurityContextHolder.clearContext();
        tx.executeWithoutResult(s -> {
            TARGET_TABLES.forEach(table -> sql.update("DELETE FROM " + table));
            for (String table : List.of("question_revision_contract_bindings", "quiz_publication_receipts", "quiz_publication_heads",
                    "quiz_revision_images", "quiz_publications", "quiz_revision_items", "question_option_revisions", "question_revisions",
                    "quiz_revisions", "quiz_questions", "question_tags", "question_source_confirmations", "questions", "quizzes",
                    "categories", "user_roles", "users")) sql.update("DELETE FROM " + table);
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'start-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'start-%'");
        });
    }

    @ParameterizedTest @EnumSource(AnswerOrder.class)
    void realFiveFamilyPublicationStartsOneCoherentAttemptAndStableReplay(AnswerOrder order) throws Exception {
        var command = command(prepared(), order, "first");
        var first = starts.start(command);
        assertThat(first.replayed()).isFalse();
        assertCoherent(first, 5);
        var stored = targetSnapshot();
        var replay = restarted().start(command);
        assertThat(replay).isEqualTo(new AttemptStartService.Result(first.attemptId(), first.formId(), first.quizRevisionId(), 1, true));
        assertThat(targetSnapshot()).isEqualTo(stored);
        assertThat(JSON.writeValueAsString(first)).doesNotContain("correct", "answer", "explanation", "owner", "subject");
        var policy = sql.queryForObject("SELECT canonical_json FROM attempt_policy_snapshot", String.class);
        assertThat(policy).contains(order.name(), "FREE_PRACTICE", "ALL_AT_ONCE");
        assertThat(count("attempts")).isZero(); assertThat(count("answers")).isZero();
    }

    @Test void freshStartUsesCurrentPublicationWhileCommittedReplayKeepsOriginalAfterDraftEditAndRepublish() {
        UUID question = draft(QuestionType.TRUE_FALSE);
        UUID oldRevision = publish(0, List.of(question)); binding.bind(quizId, oldRevision);
        var command = command(oldRevision, AnswerOrder.AUTHORED, "original");
        var first = starts.start(command);
        tx.executeWithoutResult(s -> {
            var q = questions.findById(question).orElseThrow(); q.setQuestionText("Later draft"); questions.saveAndFlush(q);
        });
        assertThat(starts.start(command).attemptId()).isEqualTo(first.attemptId());
        UUID next = publish(1, List.of(question)); binding.bind(quizId, next);
        assertThat(starts.start(command).attemptId()).isEqualTo(first.attemptId());
        denied(() -> starts.start(command(oldRevision, AnswerOrder.AUTHORED, "stale")), STALE_PUBLICATION);
        var nextCommand = new AttemptStartService.Command(1, quizId, content(next), 2, CommandReceipt.Key.from("new"),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", AnswerOrder.AUTHORED);
        assertThat(starts.start(nextCommand).quizRevisionId()).isEqualTo(next);
        assertThat(count("attempt_lifecycle_state")).isEqualTo(2);
        assertThat(revisions.findById(ownerId, oldRevision).orElseThrow().items().get(0).question().questionText()).isEqualTo("Frozen TRUE_FALSE");
    }

    @Test void keyConflictsDoNotCreateAnotherAttemptAndDifferentKeysRemainIndependent() {
        var original = command(prepared(), AnswerOrder.AUTHORED, "shared-key");
        starts.start(original);
        denied(() -> starts.start(command(original.content().quizRevisionId(), AnswerOrder.SHUFFLED_PER_ATTEMPT, "shared-key")), CONFLICT);
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
        starts.start(command(original.content().quizRevisionId(), AnswerOrder.AUTHORED, "different-key"));
        assertThat(count("attempt_lifecycle_state")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT INTO attempt_policy_snapshot", "INSERT INTO attempt_form (",
            "INSERT INTO attempt_participant_subject", "INSERT INTO attempt_lifecycle_state", "UPDATE attempt_lifecycle_state",
            "INSERT INTO attempt_trace_event", "UPDATE attempt_command_receipt SET"})
    void reachedFailureRollsBackEveryStartWriteThenFreshRetrySucceeds(String checkpoint) {
        var command = command(prepared(), AnswerOrder.AUTHORED, "fault");
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains(checkpoint) && reached.compareAndSet(false, true)) throw new SQLException("Synthetic reached write failure", "08006");
        };
        denied(() -> starts.start(command), PERSISTENCE_UNAVAILABLE);
        observer().reset(); assertThat(reached).isTrue();
        assertNoTargetRows();
        assertThat(count("quiz_publications")).isEqualTo(1);
        assertThat(count("question_revision_contract_bindings")).isEqualTo(5);
        assertCoherent(restarted().start(command), 5);
    }

    /** Fault injection proves propagation at each real SQL boundary; tests below force real InnoDB conflicts. */
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("databaseFailureBoundaries")
    void nestedSqlFailuresRetainSafeReasonsAndRollBackEveryWrite(String checkpoint, int occurrence, int code) {
        var command = command(prepared(), AnswerOrder.AUTHORED, "nested-sql-failure");
        var seen = new AtomicInteger(); var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains(checkpoint) && seen.incrementAndGet() == occurrence) {
                reached.set(true);
                throw new SQLException("Synthetic private SQL diagnostic", code == 1213 ? "40001" : code == 1205 ? "HY000" : "08006", code);
            }
        };
        var expected = code == 1213 ? RETRYABLE : code == 1205 ? IN_PROGRESS : PERSISTENCE_UNAVAILABLE;
        denied(() -> starts.start(command), expected);
        assertThat(reached).isTrue(); assertThat(seen).hasValue(occurrence);
        observer().reset(); assertNoTargetRows();
        assertCoherent(restarted().start(command), 5);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> databaseFailureBoundaries() {
        record Boundary(String sql, int occurrence) { }
        return List.of(
                new Boundary("from users ", 1), // AuthenticatedSubjectResolver.
                new Boundary("LEFT JOIN quiz_revision_items i ON i.quiz_revision_id=r.id", 1), // Contract scope.
                new Boundary("FROM question_revision_contract_bindings b", 1), // Contract resolution.
                new Boundary("INSERT INTO attempt_policy_snapshot", 1),
                new Boundary("SELECT * FROM quiz_revisions WHERE owner_id", 2), // Materializer's read.
                new Boundary("SELECT * FROM quiz_revisions WHERE owner_id", 3), // Option-order service's read.
                new Boundary("INSERT INTO attempt_form (", 1),
                new Boundary("INSERT INTO attempt_form_option_order", 1),
                new Boundary("INSERT INTO attempt_participant_subject", 1),
                new Boundary("INSERT INTO attempt_lifecycle_state", 1),
                new Boundary("UPDATE attempt_lifecycle_state", 1),
                new Boundary("INSERT INTO attempt_trace_event", 1),
                new Boundary("UPDATE attempt_command_receipt SET", 1))
                .stream().flatMap(point -> IntStream.of(1213, 1205, 0).mapToObj(code ->
                        org.junit.jupiter.params.provider.Arguments.of(point.sql(), point.occurrence(), code)));
    }

    @Test void outerJpaRollbackCannotLeaveAReceiptOrAnyTargetEvidence() {
        var command = command(prepared(), AnswerOrder.AUTHORED, "outer");
        tx.executeWithoutResult(status -> {
            var result = starts.start(command);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_trace_event", Integer.class)).isEqualTo(2);
            assertThat(result.replayed()).isFalse();
            status.setRollbackOnly();
        });
        assertNoTargetRows();
        assertCoherent(starts.start(command), 5);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownCommitOutcomeIsReconciledFromDurableStateAfterRestart(boolean committed) {
        var command = command(prepared(), AnswerOrder.AUTHORED, "lost-response");
        var reached = new AtomicBoolean();
        var written = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("UPDATE attempt_command_receipt SET")) written.set(true);
        };
        if (committed) observer().afterCommit = connection -> {
            if (written.get()) { reached.set(true); throw new SQLException("Synthetic lost commit acknowledgement", "08006"); }
        };
        else observer().beforeCommit = connection -> {
            if (written.get()) { connection.rollback(); reached.set(true); throw new SQLException("Synthetic rejected commit", "08006"); }
        };
        denied(() -> starts.start(command), UNKNOWN_OUTCOME);
        observer().reset(); assertThat(written).isTrue(); assertThat(reached).isTrue();
        var durable = targetSnapshot();
        if (!committed) assertNoTargetRows();
        var replay = restarted().start(command);
        assertThat(replay.replayed()).isEqualTo(committed);
        assertCoherent(replay, 5);
        if (committed) assertThat(targetSnapshot()).isEqualTo(durable);
    }

    @ParameterizedTest @CsvSource({"2,false", "4,false", "2,true", "4,true"})
    void independentTransactionsHaveARealWinnerAndDeterministicDuplicateOutcome(int isolation, boolean conflicting) throws Exception {
        var original = command(prepared(), AnswerOrder.AUTHORED, "race");
        var other = command(original.content().quizRevisionId(), conflicting ? AnswerOrder.SHUFFLED_PER_ATTEMPT : AnswerOrder.AUTHORED, "race");
        var both = new CyclicBarrier(2);
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("INSERT INTO attempt_command_receipt")) {
                try { both.await(10, TimeUnit.SECONDS); } catch (Exception failure) { throw new SQLException("Start barrier failed", failure); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> raced(original, isolation));
            var b = pool.submit(() -> raced(other, isolation));
            var outcomes = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            observer().reset();
            var successes = outcomes.stream().filter(AttemptStartService.Result.class::isInstance).map(AttemptStartService.Result.class::cast).toList();
            assertThat(successes).hasSize(conflicting ? 1 : 2);
            assertThat(successes.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
            if (conflicting) assertThat(outcomes).contains(CONFLICT);
            else assertThat(successes.get(0).attemptId()).isEqualTo(successes.get(1).attemptId());
            assertCoherent(successes.get(0), 5);
        } finally { observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void unauthenticatedForeignPrivateMetadataOnlyAndRoleNamesCannotStart() {
        var command = command(prepared(), AnswerOrder.AUTHORED, "access");
        var positive = starts.start(command); assertCoherent(positive, 5);
        var before = targetSnapshot();
        SecurityContextHolder.clearContext(); denied(() -> starts.start(command), UNAUTHENTICATED);
        String other = "start-other"; UUID id = user(other, true); authenticate(other);
        denied(() -> starts.start(command), ACCESS_DENIED); // QUIZ_UPDATE is only metadata authority.
        grant(id, "QUESTION_UPDATE"); denied(() -> starts.start(command), ACCESS_DENIED);
        tx.executeWithoutResult(s -> {
            sql.update("INSERT INTO roles(role_name,is_default) VALUES ('ADMIN',FALSE) ON DUPLICATE KEY UPDATE is_default=FALSE");
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name='ADMIN'", id.toString());
        });
        // This isolated catalogue's ADMIN role deliberately has no permissions: a name grants none.
        tx.executeWithoutResult(s -> sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name='ADMIN'"));
        denied(() -> starts.start(command), ACCESS_DENIED);
        grant(id, "ATTEMPT_CREATE"); denied(() -> starts.start(command), ACCESS_DENIED);
        grant(id, "QUIZ_ADMIN"); denied(() -> starts.start(command), ACCESS_DENIED);
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"QUESTION_ADMIN", "QUIZ_MODERATE"})
    void effectiveFullQuestionEditingAuthorityPermitsPracticeAndRevocationBlocksReplay(String permission) {
        var command = command(prepared(), AnswerOrder.AUTHORED, "editor");
        String editor = "start-editor"; UUID id = user(editor, false); grant(id, "QUESTION_UPDATE", permission);
        authenticate(editor);
        var result = starts.start(command);
        assertThat(sql.queryForObject("SELECT BIN_TO_UUID(user_id) FROM attempt_participant_subject", String.class)).isEqualTo(id.toString());
        var stored = targetSnapshot();
        tx.executeWithoutResult(s -> sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", id.toString()));
        denied(() -> starts.start(command), ACCESS_DENIED);
        assertThat(targetSnapshot()).isEqualTo(stored);
        assertThat(result.attemptId()).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings = {"deactivate", "delete-user", "delete-quiz", "transfer-quiz"})
    void changedCurrentAccessDeniesReplayWithoutDisclosingOrChangingTheOriginal(String change) {
        var command = command(prepared(), AnswerOrder.AUTHORED, "current-access"); starts.start(command);
        UUID other = user("start-recipient", true);
        var stored = targetSnapshot();
        tx.executeWithoutResult(s -> {
            switch (change) {
                case "deactivate" -> sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
                case "delete-user" -> sql.update("UPDATE users SET is_deleted=TRUE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
                case "delete-quiz" -> sql.update("UPDATE quizzes SET is_deleted=TRUE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
                case "transfer-quiz" -> sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?) WHERE quiz_id=UUID_TO_BIN(?)", other.toString(), quizId.toString());
            }
        });
        denied(() -> starts.start(command), ACCESS_DENIED);
        assertThat(targetSnapshot()).isEqualTo(stored);
    }

    @ParameterizedTest @ValueSource(strings = {"ONE_BY_ONE", "TIMED", "SECTIONS", "UNKNOWN"})
    void unsupportedModesHaveExactReasonsAndNoTargetEffects(String mode) {
        UUID revision = prepared();
        tx.executeWithoutResult(s -> sql.update("UPDATE quizzes SET is_timer_enabled=TRUE,timer_duration_min=10 WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString()));
        var request = new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from(mode),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, mode, AnswerOrder.AUTHORED);
        denied(() -> starts.start(request), List.of("SECTIONS", "UNKNOWN").contains(mode) ? UNSUPPORTED_LEGACY_MODE : UNSUPPORTED_CAPABILITY);
        assertNoTargetRows();
        assertCoherent(starts.start(command(revision, AnswerOrder.AUTHORED, "positive")), 5);
    }

    @Test void assignmentUnknownPresetAndUnboundOrUnknownContractsNeverFallBackToMutableQuestions() {
        UUID revision = prepared();
        var assignment = new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from("assignment"),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ASSIGNMENT, "ALL_AT_ONCE", AnswerOrder.AUTHORED);
        denied(() -> starts.start(assignment), UNSUPPORTED_CAPABILITY);
        var future = new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from("future"),
                new PresetReference("FREE_PRACTICE", 2), LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", AnswerOrder.AUTHORED);
        denied(() -> starts.start(future), UNSUPPORTED_PRESET);
        tx.executeWithoutResult(s -> sql.update("DELETE FROM question_revision_contract_bindings"));
        var good = command(revision, AnswerOrder.AUTHORED, "contracts");
        denied(() -> starts.start(good), CONTRACT_UNAVAILABLE); assertNoTargetRows();
        binding.bind(quizId, revision);
        tx.executeWithoutResult(s -> sql.update("UPDATE question_revision_contract_bindings SET scoring_contract_version=99"));
        denied(() -> starts.start(good), CONTRACT_UNAVAILABLE); assertNoTargetRows();
        tx.executeWithoutResult(s -> sql.update("UPDATE question_revision_contract_bindings SET scoring_contract_version=1"));
        assertCoherent(starts.start(good), 5);
    }

    @Test void disabledLegacyTimerKeepsTheCapturedUntimedPolicy() throws Exception {
        UUID revision = prepared();
        tx.executeWithoutResult(s -> sql.update("UPDATE quizzes SET is_timer_enabled=FALSE,timer_duration_min=10 WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString()));
        var command = new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from("disabled"),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "TIMED", AnswerOrder.AUTHORED);
        var result = starts.start(command);
        tx.executeWithoutResult(s -> sql.update("UPDATE quizzes SET is_timer_enabled=TRUE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString()));
        assertThat(starts.start(command).attemptId()).isEqualTo(result.attemptId());
        var policy = JSON.readTree(sql.queryForObject("SELECT canonical_json FROM attempt_policy_snapshot", String.class));
        assertThat(policy.at("/policy/timing/mode").asText()).isEqualTo("NONE");
    }

    @Test void sourceAndRelationshipReadsStayBoundedAcrossSeveralQuizzesAndLargerForms(TestReporter reporter) {
        List<Integer> reads = new ArrayList<>();
        List<Integer> replayReads = new ArrayList<>();
        for (int size : List.of(5, 10, 20)) {
            quizId = newQuiz();
            var ids = IntStream.range(0, size).mapToObj(i -> draft(FAMILIES.get(i % FAMILIES.size()))).toList();
            UUID revision = publish(0, ids); binding.bind(quizId, revision);
            var command = command(revision, AnswerOrder.AUTHORED, "measure-" + size);
            observer().selects.clear();
            starts.start(command);
            reads.add(observer().selects.size());
            observer().selects.clear(); starts.start(command);
            replayReads.add(observer().selects.size());
        }
        assertThat(reads.get(0)).isPositive();
        assertThat(reads).allMatch(n -> n.equals(reads.get(0)));
        assertThat(replayReads).allMatch(n -> n > 0 && n <= 7 && n.equals(replayReads.get(0)));
        reporter.publishEntry("startSelectCountsFor5_10_20Items", reads.toString());
        reporter.publishEntry("replaySelectCountsFor5_10_20Items", replayReads.toString());
    }
    @ParameterizedTest @CsvSource({"2,false", "4,false", "2,true", "4,true"})
    void freshPublicationIsVisibleToStartInsideExistingSnapshot(int isolation, boolean rollback) throws Exception {
        var questions = FAMILIES.stream().map(this::draft).toList();
        UUID oldRevision = publish(0, questions); binding.bind(quizId, oldRevision);
        var pool = Executors.newSingleThreadExecutor();
        var outer = new TransactionTemplate(manager); outer.setIsolationLevel(isolation);
        var request = new AtomicReference<AttemptStartService.Command>();
        try {
            var result = outer.execute(status -> {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_revisions", Integer.class)).isEqualTo(1);
                UUID next;
                try {
                    next = pool.submit(() -> {
                        authenticate(username);
                        try {
                            tx.executeWithoutResult(s -> questions.forEach(id -> {
                                var changed = this.questions.findById(id).orElseThrow();
                                changed.setQuestionText("New publication " + changed.getType());
                                this.questions.saveAndFlush(changed);
                            }));
                            UUID revision = publish(1, questions); binding.bind(quizId, revision);
                            return revision;
                        } finally { SecurityContextHolder.clearContext(); }
                    }).get(20, TimeUnit.SECONDS);
                } catch (Exception error) { throw new AssertionError(error); }
                assertThat(next).isNotEqualTo(oldRevision);
                var access = sources.lockAccess(ownerId, quizId);
                assertThat(access.currentRevisionId()).isEqualTo(next);
                assertThat(access.publicationVersion()).isEqualTo(2);
                request.set(new AttemptStartService.Command(1, quizId, content(next), 2, CommandReceipt.Key.from("fresh-after-snapshot"),
                        BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", AnswerOrder.SHUFFLED_PER_ATTEMPT));
                var started = starts.start(request.get());
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_trace_event", Integer.class)).isEqualTo(2);
                if (rollback) status.setRollbackOnly();
                return started;
            });
            if (rollback) {
                assertNoTargetRows();
                var retry = restarted().start(request.get());
                assertThat(retry.replayed()).isFalse(); assertCoherent(retry, 5);
            } else {
                assertCoherent(result, 5);
                assertThat(restarted().start(request.get()).attemptId()).isEqualTo(result.attemptId());
            }
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void differentKeysConcurrentStartBothCommit(int isolation) throws Exception {
        UUID revision = prepared();
        var a = command(revision, AnswerOrder.AUTHORED, "independent-A");
        var b = command(revision, AnswerOrder.AUTHORED, "independent-B");
        var barrier = new CyclicBarrier(2);
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("INSERT INTO attempt_command_receipt")) {
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new SQLException("Start barrier failed", e); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> raced(a, isolation)); var second = pool.submit(() -> raced(b, isolation));
            var outcomes = List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
            assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(AttemptStartService.Result.class));
            assertThat(observer().sqlErrorCodes).isEmpty();
            assertThat(((AttemptStartService.Result) outcomes.get(0)).attemptId())
                    .isNotEqualTo(((AttemptStartService.Result) outcomes.get(1)).attemptId());
            assertThat(count("attempt_lifecycle_state")).isEqualTo(2);
            assertThat(count("attempt_command_receipt")).isEqualTo(2);
            assertThat(count("attempt_trace_event")).isEqualTo(4);
        } finally { observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 2, 4})
    void realAuthorizationDeadlockRetainsRetryableReasonAndNoPartialWrites(int isolation) throws Exception {
        var command = command(prepared(), AnswerOrder.AUTHORED, "deadlock");
        sql.execute("CREATE TABLE start_deadlock_weight (id INT PRIMARY KEY, value INT) ENGINE=InnoDB");
        var quizLocked = new CountDownLatch(1); var actorLocked = new CountDownLatch(1);
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("username <> 'anonymous' FOR SHARE")) actorLocked.countDown();
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var editor = pool.submit(() -> tx.executeWithoutResult(status -> {
                for (int i = 0; i < 100; i++) sql.update("INSERT INTO start_deadlock_weight VALUES (?,0)", i);
                sql.update("UPDATE quizzes SET title='Concurrent edit' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
                quizLocked.countDown();
                try { assertThat(actorLocked.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                sql.update("UPDATE users SET is_active=TRUE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
            }));
            assertThat(quizLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var start = pool.submit(() -> {
                authenticate(username);
                try {
                    if (isolation == -1) return (Object) starts.start(command);
                    var outer = new TransactionTemplate(manager); outer.setIsolationLevel(isolation);
                    return outer.execute(status -> starts.start(command));
                }
                catch (AttemptStartException failure) {
                    assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
                    return failure.reason();
                }
                finally { SecurityContextHolder.clearContext(); }
            });
            Object result = start.get(20, TimeUnit.SECONDS); editor.get(20, TimeUnit.SECONDS);
            assertThat(observer().sqlErrorCodes).as("The database really chose a deadlock victim").contains(1213);
            observer().reset(); assertNoTargetRows();
            assertThat(result).isEqualTo(RETRYABLE);
            assertCoherent(restarted().start(command), 5);
        } finally {
            observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            sql.execute("DROP TABLE start_deadlock_weight");
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void realAuthorizationLockTimeoutRetainsInProgressWithoutRetryOrWrites(int isolation) throws Exception {
        var command = command(prepared(), AnswerOrder.AUTHORED, "access-timeout");
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var authorizations = new AtomicInteger();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("username <> 'anonymous' FOR SHARE")) authorizations.incrementAndGet();
        };
        var pool = Executors.newSingleThreadExecutor();
        try {
            var editor = pool.submit(() -> tx.executeWithoutResult(status -> {
                sql.update("UPDATE quizzes SET title='Concurrent edit' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
                locked.countDown();
                try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var outer = new TransactionTemplate(manager); outer.setIsolationLevel(isolation);
            denied(() -> outer.execute(status -> {
                int previous = sql.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                sql.execute("SET SESSION innodb_lock_wait_timeout=1");
                try { return starts.start(command); }
                finally { sql.execute("SET SESSION innodb_lock_wait_timeout=" + previous); }
            }), IN_PROGRESS);
            assertThat(observer().sqlErrorCodes).containsExactly(1205);
            assertThat(authorizations).hasValue(1);
            observer().reset(); assertNoTargetRows();
            release.countDown(); editor.get(10, TimeUnit.SECONDS);
            assertCoherent(restarted().start(command), 5);
        } finally {
            release.countDown(); observer().reset(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void nestedContractLockTimeoutPreservesInProgressAndRollback(int isolation) throws Exception {
        UUID revision = prepared();
        var command = command(revision, AnswerOrder.AUTHORED, "contract-timeout");
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var binder = pool.submit(() -> tx.executeWithoutResult(status -> {
                contracts.declareScope(ownerId, Set.of(revision));
                contracts.resolve(ownerId, revision);
                locked.countDown();
                try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var outer = new TransactionTemplate(manager); outer.setIsolationLevel(isolation);
            AttemptStartException.Reason actual;
            try {
                outer.execute(status -> {
                    int previous = sql.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    sql.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try { return starts.start(command); }
                    finally { sql.execute("SET SESSION innodb_lock_wait_timeout=" + previous); }
                });
                throw new AssertionError("A blocked start must time out");
            } catch (AttemptStartException failure) {
                assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
                actual = failure.reason();
            }
            assertThat(observer().sqlErrorCodes).containsExactly(1205);
            observer().reset(); assertNoTargetRows();
            release.countDown(); binder.get(10, TimeUnit.SECONDS);
            assertCoherent(restarted().start(command), 5);
            assertThat(actual).isEqualTo(IN_PROGRESS);
        } finally {
            release.countDown(); observer().reset(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void nestedContractDeadlockPreservesRetryableAndRollback(int isolation) throws Exception {
        UUID revision = prepared();
        var command = command(revision, AnswerOrder.AUTHORED, "contract-deadlock");
        sql.execute("CREATE TABLE nested_start_deadlock_weight (id INT PRIMARY KEY, value INT) ENGINE=InnoDB");
        var held = new CountDownLatch(1); var starting = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var editor = pool.submit(() -> tx.executeWithoutResult(status -> {
                for (int i = 0; i < 100; i++) sql.update("INSERT INTO nested_start_deadlock_weight VALUES (?,0)", i);
                contracts.declareScope(ownerId, Set.of(revision));
                contracts.resolve(ownerId, revision);
                held.countDown();
                try { assertThat(starting.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                sql.update("UPDATE quizzes SET title='Concurrent edit' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            observer().beforeSql = (statement, connection) -> {
                if (statement.contains("SELECT locked.id FROM")) starting.countDown();
            };
            Object actual = pool.submit(() -> raced(command, isolation)).get(20, TimeUnit.SECONDS);
            editor.get(20, TimeUnit.SECONDS);
            assertThat(observer().sqlErrorCodes).contains(1213);
            observer().reset(); assertNoTargetRows();
            assertCoherent(restarted().start(command), 5);
            assertThat(actual).isEqualTo(RETRYABLE);
        } finally {
            starting.countDown(); observer().reset(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            sql.execute("DROP TABLE nested_start_deadlock_weight");
        }
    }

    private UUID draft(QuestionType type) {
        return tx.execute(s -> {
            var q = new Question(); q.setType(type); q.setDifficulty(Difficulty.EASY); q.setQuestionText("Frozen " + type);
            var content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
            if (type == QuestionType.FILL_GAP)
                content.putArray("options").add("Paris").add("Rome").add("London").add("Berlin").add("Madrid").add("Lisbon").add("Vienna");
            q.setContent(content.toString());
            q.establishSourceOwner(ownerId); q.setQuizId(new ArrayList<>(List.of(quizzes.findById(quizId).orElseThrow())));
            return questions.saveAndFlush(q).getId();
        });
    }
    private UUID publish(long version, List<UUID> order) { return publications.publish(new Command(1, quizId, UUID.randomUUID(), version, order)).publication().revision().id(); }
    private UUID user(String name, boolean permission) {
        UUID id = tx.execute(s -> {
            var u = new User(); u.setUsername(name); u.setEmail(name + "@example.invalid"); u.setHashedPassword("test-only");
            u.setActive(true); u.setRoles(new HashSet<>()); return users.saveAndFlush(u).getId();
        });
        if (permission) tx.executeWithoutResult(s -> {
            String role = "start-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
    }

    private UUID newQuiz() {
        return tx.execute(s -> {
            var category = new Category(); category.setName("start-" + UUID.randomUUID()); categories.saveAndFlush(category);
            var quiz = new Quiz(); quiz.setCreator(users.findById(ownerId).orElseThrow()); quiz.setCategory(category);
            quiz.setTitle("Start fixture " + UUID.randomUUID()); quiz.setDescription("Synthetic source"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
    }
    private UUID prepared() {
        var revision = publish(0, FAMILIES.stream().map(this::draft).toList());
        binding.bind(quizId, revision);
        return revision;
    }
    private static ContentPolicy content(UUID revision) {
        return new ContentPolicy(revision, 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
    }
    private AttemptStartService.Command command(UUID revision, AnswerOrder order, String key) {
        return new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from(key),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", order);
    }
    private static void authenticate(String name) {
        var principal = org.springframework.security.core.userdetails.User.withUsername(name).password("test-only").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private void grant(UUID userId, String... names) {
        tx.executeWithoutResult(s -> {
            String role = "start-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            for (String name : names) assertThat(sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, name)).isEqualTo(1);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", userId.toString(), role);
        });
    }
    @Test void allStartKeepsThePreExtensionFingerprintAndExplicitAllReplaysTheSameReceipt() throws Exception {
        var command = command(prepared(), AnswerOrder.AUTHORED, "historical-all");
        var result = starts.start(command);
        var historical = JSON.readTree("""
                {"version":1,"quiz":"%s","revision":"%s","publicationVersion":1,"revisionVersion":1,
                 "formMode":"PRECOMPUTED","sections":"IMPLICIT_FLAT","cases":"NOT_CONFIGURED",
                 "preset":"FREE_PRACTICE","presetVersion":1,"context":"ORDINARY","legacyMode":"ALL_AT_ONCE","answerOrder":"AUTHORED"}
                """.formatted(quizId, command.content().quizRevisionId()));
        var expected = uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput.fingerprint(1, historical);
        assertThat(sql.queryForObject("SELECT input_hash FROM attempt_command_receipt", String.class)).isEqualTo(expected.digest());
        var replay = starts.start(selected(command, Configuration.all()));
        assertThat(replay.replayed()).isTrue(); assertThat(replay.attemptId()).isEqualTo(result.attemptId());
    }

    @ParameterizedTest @EnumSource(SelectionMode.class)
    void selectedStartsPersistRealAlgorithmEvidenceAndResumeWithoutRequiringTheSeedAgain(SelectionMode mode) throws Exception {
        UUID revision = prepared();
        var bank = revisions.findById(ownerId, revision).orElseThrow().items().stream().map(i -> i.id()).toList();
        Configuration config = switch (mode) {
            case ALL -> Configuration.all();
            case MANUAL_SET -> Configuration.manualSet(List.of(bank.get(3), bank.get(1)));
            case RANDOM_COUNT -> Configuration.randomCount(2);
            case RANDOM_PERCENTAGE -> Configuration.randomPercentage(3300); // ceil(5 * .33) = 2
        };
        var command = selected(command(revision, AnswerOrder.SHUFFLED_PER_ATTEMPT, "selected"), config);
        var result = randomStarts().start(command);
        assertCoherent(result, mode == SelectionMode.ALL ? 5 : 2);
        var captured = tx.execute(status -> formRepository.findSelection(ownerId, result.formId()).orElseThrow());
        assertThat(captured.configuration()).isEqualTo(config);
        var form = tx.execute(status -> formRepository.findByIds(ownerId, List.of(result.formId())).get(0));
        var ids = form.items().stream().map(i -> i.sourceItemId()).toList();
        assertThat(ids).doesNotHaveDuplicates().isSubsetOf(bank);
        assertThat(bank.stream().filter(ids::contains).toList()).isEqualTo(ids);
        if (mode == SelectionMode.MANUAL_SET) assertThat(ids).containsExactly(bank.get(1), bank.get(3));
        if (config.strategy().random()) {
            String receipt = sql.queryForObject("SELECT BIN_TO_UUID(id) FROM attempt_command_receipt", String.class);
            assertThat(captured.random().context().operationId()).isEqualTo(UUID.fromString(receipt));
            assertThat(captured.random().candidateCount()).isEqualTo(5);
            assertThat(captured.random().selectedCount()).isEqualTo(2);
            assertThat(captured.random().keyVersion()).isEqualTo(7);
        }
        var resolution = sql.queryForObject("SELECT canonical_json FROM attempt_policy_snapshot", String.class);
        assertThat(JSON.readTree(resolution).at("/policy/questionSelection/mode").asText()).isEqualTo(mode.name());
        var before = targetSnapshot();
        // A reconstructed receipt service and an unavailable seed source must read committed state.
        var replay = withSelector(input -> { throw new AssertionError("Committed replay must not select again"); }).start(command);
        assertThat(replay.attemptId()).isEqualTo(result.attemptId()); assertThat(replay.replayed()).isTrue();
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(JSON.writeValueAsString(result)).doesNotContain("seed", "candidate", "selection", "configuration");
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT INTO attempt_form_selection", "INSERT INTO attempt_participant_subject", "UPDATE attempt_command_receipt SET"})
    void selectedStartReachedWriteFailuresRollBackFormEvidenceAndReceiptBeforeSuccessfulRetry(String checkpoint) {
        var command = selected(command(prepared(), AnswerOrder.AUTHORED, "selected-fault"), Configuration.randomCount(2));
        AtomicBoolean reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains(checkpoint) && reached.compareAndSet(false, true)) throw new SQLException("Synthetic selected write failure", "08006");
        };
        denied(() -> randomStarts().start(command), PERSISTENCE_UNAVAILABLE);
        observer().reset(); assertThat(reached).isTrue(); assertNoTargetRows();
        assertCoherent(randomStarts().start(command), 2);
    }

    @ParameterizedTest @CsvSource({"0,ALL", "1213,ALL", "1205,ALL", "0,RANDOM_COUNT", "1213,RANDOM_COUNT", "1205,RANDOM_COUNT"})
    void selectedSqlFailuresAreSanitizedBeforeTransactionLoggingAndKeepRollbackAndRetry(int code, SelectionMode mode) {
        var config = mode == SelectionMode.ALL ? Configuration.all() : Configuration.randomCount(2);
        var command = selected(command(prepared(), AnswerOrder.AUTHORED, "selection-log-failure"), config);
        String marker = "selection-private-driver-marker";
        var reached = new AtomicBoolean();
        var logger = (Logger) LoggerFactory.getLogger(TransactionTemplate.class);
        Level previous = logger.getLevel(); Thread requestThread = Thread.currentThread();
        var captured = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (Thread.currentThread() == requestThread) {
                    event.prepareForDeferredProcessing(); super.append(event);
                }
            }
        };
        captured.setContext(logger.getLoggerContext()); captured.start();
        logger.addAppender(captured); logger.setLevel(Level.DEBUG);
        observer().afterSql = (statement, connection) -> {
            if (statement.stripLeading().startsWith("INSERT INTO attempt_form_selection") && reached.compareAndSet(false, true)) {
                // Same transaction: prove that the actual INSERT reached its write before failing.
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form_selection", Integer.class)).isEqualTo(1);
                var failure = new SQLException(marker, code == 1213 ? "40001" : code == 1205 ? "HY000" : "08006", code);
                failure.initCause(new IllegalArgumentException(marker + "-cause"));
                failure.addSuppressed(new IllegalStateException(marker + "-suppressed"));
                failure.setNextException(new SQLException(marker + "-next"));
                throw failure;
            }
        };
        try {
            var expected = code == 1213 ? RETRYABLE : code == 1205 ? IN_PROGRESS : PERSISTENCE_UNAVAILABLE;
            denied(() -> randomStarts().start(command), expected);
            assertThat(reached).isTrue(); observer().reset();
            assertNoTargetRows(); // Fresh transactions, after the real receipt transaction rolled back.
            var retry = randomStarts().start(command);
            assertCoherent(retry, mode == SelectionMode.ALL ? 5 : 2);
            assertThat(restarted().start(command).attemptId()).isEqualTo(retry.attemptId());
            var throwableEvents = captured.list.stream().filter(event -> event.getThrowableProxy() != null).toList();
            assertThat(throwableEvents).isNotEmpty();
            for (var event : captured.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(marker);
                var throwable = event.getThrowableProxy();
                if (throwable != null) {
                    assertThat(ThrowableProxyUtil.asString(throwable)).doesNotContain(marker);
                    assertThat(throwable.getCause()).isNull();
                    assertThat(throwable.getSuppressed()).isNullOrEmpty();
                }
            }
            assertThat(throwableEvents).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy().getMessage()).contains(code == 0
                        ? "PERSISTENCE_UNAVAILABLE" : code == 1213 ? "DEADLOCK" : "LOCK_TIMEOUT");
            });
        } finally {
            observer().reset(); logger.detachAppender(captured); logger.setLevel(previous); captured.stop();
        }
    }

    @Test void strategyFailureInsufficiencyEmptySelectionAndUnavailableSeedLeaveNoAuthoritativeWrites() {
        var original = command(prepared(), AnswerOrder.AUTHORED, "rejected-selection");
        denied(() -> randomStarts().start(selected(original, Configuration.randomCount(6))), SELECTION_REJECTED);
        assertNoTargetRows();
        for (var config : List.of(Configuration.randomCount(0), Configuration.randomPercentage(0), Configuration.manualSet(List.of()),
                Configuration.manualSet(List.of(UUID.randomUUID())))) {
            denied(() -> randomStarts().start(selected(original, config)), SELECTION_REJECTED); assertNoTargetRows();
        }
        var valid = selected(original, Configuration.randomCount(2));
        denied(() -> starts.start(valid), SELECTION_UNAVAILABLE); assertNoTargetRows(); // optional key is absent in this fixture
        denied(() -> withSelector(input -> { throw new QuestionSelectionException(QuestionSelectionException.Reason.CONSTRAINT_VIOLATION); })
                .start(valid), SELECTION_REJECTED);
        assertNoTargetRows();
        denied(() -> withSelector(input -> { throw new QuestionSelectionException(QuestionSelectionException.Reason.UNSUPPORTED_STRATEGY_VERSION); })
                .start(valid), UNSUPPORTED_VERSION);
        assertNoTargetRows();
        assertCoherent(randomStarts().start(valid), 2);
    }

    @Test void genericExtensionAcceptsAValidContractDoubleWhileRealStartAndMaterializerCommitAndReplay() {
        UUID revision = prepared();
        var bank = revisions.findById(ownerId, revision).orElseThrow().items();
        var config = Configuration.manualSet(List.of(bank.get(1).id(), bank.get(3).id()));
        var command = selected(command(revision, AnswerOrder.AUTHORED, "extension"), config);
        AtomicInteger calls = new AtomicInteger();
        QuestionSelectionService adapter = input -> {
            calls.incrementAndGet();
            return new QuestionSelection.Result(1, new Evidence(1, 1, revision, 1, config), List.of(bank.get(1).id(), bank.get(3).id()));
        };
        var result = withSelector(adapter).start(command); assertCoherent(result, 2);
        assertThat(withSelector(adapter).start(command).attemptId()).isEqualTo(result.attemptId());
        assertThat(calls).hasValue(1); // Extension composition proof, not random algorithm conformance.
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedDuplicateRacesHaveASuccessfulWinnerAndNoSecondForm(boolean conflicting) throws Exception {
        var original = selected(command(prepared(), AnswerOrder.AUTHORED, "selected-race"), Configuration.randomCount(2));
        var other = selected(original, Configuration.randomCount(conflicting ? 3 : 2));
        var barrier = new CyclicBarrier(2);
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("INSERT INTO attempt_command_receipt")) {
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new SQLException("Selected race barrier failed", failure); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> selectedRace(original)); var b = pool.submit(() -> selectedRace(other));
            var outcomes = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS)); observer().reset();
            var winners = outcomes.stream().filter(AttemptStartService.Result.class::isInstance).map(AttemptStartService.Result.class::cast).toList();
            assertThat(winners).hasSize(conflicting ? 1 : 2);
            assertThat(winners.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
            if (conflicting) assertThat(outcomes).contains(CONFLICT);
            else assertThat(winners.get(0).attemptId()).isEqualTo(winners.get(1).attemptId());
            assertThat(count("attempt_form_selection")).isEqualTo(1);
            assertThat(count("attempt_form")).isEqualTo(1);
            assertThat(count("attempt_participant_subject")).isEqualTo(1);
            assertThat(count("attempt_command_receipt")).isEqualTo(1);
        } finally { observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void selectedUnknownCommitReconcilesAfterRestartWithoutDuplicatingTheStoredSelection(boolean committed) {
        var command = selected(command(prepared(), AnswerOrder.AUTHORED, "selected-unknown"), Configuration.randomCount(2));
        var reached = new AtomicBoolean(); var written = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> { if (statement.contains("UPDATE attempt_command_receipt SET")) written.set(true); };
        if (committed) observer().afterCommit = connection -> { if (written.get()) { reached.set(true); throw new SQLException("Synthetic selected lost response", "08006"); } };
        else observer().beforeCommit = connection -> { if (written.get()) { connection.rollback(); reached.set(true); throw new SQLException("Synthetic selected rejected commit", "08006"); } };
        denied(() -> randomStarts().start(command), UNKNOWN_OUTCOME);
        observer().reset(); assertThat(reached).isTrue(); assertThat(written).isTrue();
        var before = targetSnapshot(); if (!committed) assertNoTargetRows();
        var replay = randomStarts().start(command); assertThat(replay.replayed()).isEqualTo(committed); assertCoherent(replay, 2);
        if (committed) assertThat(targetSnapshot()).isEqualTo(before);
    }

    @Test void preExtensionFormWithoutMetadataRemainsReadableWithoutInferringSelectionHistory() {
        UUID revisionId = prepared();
        var revision = revisions.findById(ownerId, revisionId).orElseThrow();
        var selection = selector.select(QuestionSelection.Input.fromRevision(content(revisionId), revision, Configuration.all(), List.of()));
        var order = AuthoredQuestionOrder.order(
                new AuthoredQuestionOrder.Input(1, revisionId, 1,
                        new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1), revision.items().stream().map(i ->
                        new AuthoredQuestionOrder.Item(i.id(), i.authoredPosition())).toList(), selection.itemIds()));
        var form = new FixedForm(UUID.randomUUID(), 1, revisionId,
                IntStream.range(0, order.itemIds().size()).mapToObj(i -> new FixedForm.Item(
                        UUID.randomUUID(), order.itemIds().get(i), i)).toList());
        var command = new FixedFormMaterializer.Command(form, selection, order, new AnswerOrderingPolicy(AnswerOrder.AUTHORED, 1));
        var before = materializer.materialize(ownerId, command);
        var absent = tx.execute(status -> formRepository.findSelection(ownerId, form.id()));
        assertThat(absent).isEmpty();
        assertThat(materializer.materialize(ownerId, command)).isEqualTo(before);
        assertThat(count("attempt_form_selection")).isZero();
        assertThat(count("attempt_participant_subject")).isZero();
    }

    @Test void persistedMetadataCannotBeOverwrittenOrReadByAnotherOwnerAndUnknownVersionsFailClosed() {
        var result = randomStarts().start(selected(command(prepared(), AnswerOrder.AUTHORED, "metadata"), Configuration.randomCount(2)));
        var original = sql.queryForObject("SELECT evidence_json FROM attempt_form_selection", String.class);
        var form = tx.execute(status -> formRepository.findByIds(ownerId, List.of(result.formId())).get(0));
        var alternate = Configuration.manualSet(form.items().stream().map(i -> i.sourceItemId()).toList());
        var changed = new QuestionSelection.Result(1, new Evidence(1, 1, form.quizRevisionId(), 1, alternate),
                form.items().stream().map(i -> i.sourceItemId()).toList());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> formRepository.createSelectionOrReplay(ownerId, form, changed)))
                .isInstanceOfSatisfying(FixedFormException.class, e -> assertThat(e.reason()).isEqualTo(FixedFormException.Reason.FORM_CONFLICT));
        assertThat(sql.queryForObject("SELECT evidence_json FROM attempt_form_selection", String.class)).isEqualTo(original);
        assertThatThrownBy(() -> tx.execute(status -> formRepository.findSelection(UUID.randomUUID(), result.formId())))
                .isInstanceOfSatisfying(FixedFormException.class, e -> assertThat(e.reason()).isEqualTo(FixedFormException.Reason.FORM_UNAVAILABLE));
        tx.executeWithoutResult(status -> sql.update("UPDATE attempt_form_selection SET evidence_json=?", original.replace("\"version\":1", "\"version\":2")));
        assertThatThrownBy(() -> tx.execute(status -> formRepository.findSelection(ownerId, result.formId())))
                .isInstanceOfSatisfying(FixedFormException.class, e -> assertThat(e.reason()).isEqualTo(FixedFormException.Reason.INVALID_STORED_FORM));
    }

    @Test void selectedConfigurationDoesNotGrantAuthorityAndConflictingRetriesCannotChangeIt() {
        var command = selected(command(prepared(), AnswerOrder.AUTHORED, "selected-access"), Configuration.randomCount(2));
        var result = randomStarts().start(command); var before = targetSnapshot();
        denied(() -> randomStarts().start(selected(command, Configuration.randomCount(3))), CONFLICT);
        SecurityContextHolder.clearContext(); denied(() -> randomStarts().start(command), UNAUTHENTICATED);
        String outsider = "selected-outsider"; user(outsider, true); authenticate(outsider);
        denied(() -> randomStarts().start(command), ACCESS_DENIED);
        assertThat(targetSnapshot()).isEqualTo(before); assertThat(result.replayed()).isFalse();
    }

    @Test void selectedRelationshipReadsRemainBoundedAcrossMultipleParentQuizzesAndLargerBanks(TestReporter reporter) {
        var counts = new ArrayList<Integer>();
        for (int size : List.of(5, 10, 20)) {
            quizId = newQuiz();
            UUID revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(FAMILIES.get(i % 5))).toList()); binding.bind(quizId, revision);
            var command = selected(command(revision, AnswerOrder.AUTHORED, "selected-size-" + size), Configuration.randomCount(size / 2));
            observer().selects.clear(); var result = randomStarts().start(command); counts.add(observer().selects.size());
            assertThat(tx.execute(status -> formRepository.findSelection(ownerId, result.formId()).orElseThrow()).random().candidateCount()).isEqualTo(size);
        }
        assertThat(counts.get(0)).isPositive(); assertThat(counts).allMatch(n -> n.equals(counts.get(0)));
        reporter.publishEntry("selectedStartSelectCountsFor5_10_20Items", counts.toString());
    }

    private AttemptStartService randomStarts() {
        byte[] testKey = new byte[32]; Arrays.fill(testKey, (byte) 19);
        return withSelector(new QuestionSelectionServiceImpl(new HmacSelectionSeedSource("7", Base64.getEncoder().encodeToString(testKey))));
    }
    private AttemptStartService withSelector(QuestionSelectionService configured) {
        var receipt = new CommandReceiptServiceImpl(new JdbcCommandReceiptRepository(new NamedParameterJdbcTemplate(source)), manager, clock, metrics);
        return new AttemptStartServiceImpl(identity, sources, presets, policies, snapshots, contracts, configured, materializer,
                subjects, lifecycle, trace, receipt, metrics, new JdbcNavigationRepository(new NamedParameterJdbcTemplate(source)));
    }
    private static AttemptStartService.Command selected(AttemptStartService.Command c, Configuration selection) {
        return new AttemptStartService.Command(c.version(), c.quizId(), c.content(), c.publicationVersion(), c.key(), c.preset(),
                c.context(), c.legacyMode(), c.answerOrder(), c.draftsEnabled(), c.navigation(), c.delivery(), selection);
    }
    private Object selectedRace(AttemptStartService.Command command) {
        authenticate(username);
        try { return randomStarts().start(command); }
        catch (AttemptStartException failure) { return failure.reason(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private AttemptStartService restarted() {
        var receipt = new CommandReceiptServiceImpl(new JdbcCommandReceiptRepository(new NamedParameterJdbcTemplate(source)), manager, clock, metrics);
        return new AttemptStartServiceImpl(identity, sources, presets, policies, snapshots, contracts, selector, materializer,
                subjects, lifecycle, trace, receipt, metrics, new JdbcNavigationRepository(new NamedParameterJdbcTemplate(source)));
    }
    private Object raced(AttemptStartService.Command command, int isolation) {
        authenticate(username);
        try {
            var independent = new TransactionTemplate(manager); independent.setIsolationLevel(isolation);
            return independent.execute(s -> starts.start(command));
        } catch (AttemptStartException failure) { return failure.reason(); }
        finally { SecurityContextHolder.clearContext(); }
    }
    private static void denied(Runnable action, AttemptStartException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AttemptStartException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason); assertThat(e.getCause()).isNull(); assertThat(e.getSuppressed()).isEmpty();
        });
    }
    private int count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)); }
    private void assertNoTargetRows() { TARGET_TABLES.forEach(t -> assertThat(count(t)).as(t).isZero()); }
    private Map<String, List<String>> targetSnapshot() {
        return tx.execute(s -> {
            var result = new TreeMap<String, List<String>>();
            for (String table : TARGET_TABLES) {
                result.put(table, sql.queryForList("SELECT * FROM " + table).stream().map(row -> {
                    var normalized = new TreeMap<String, String>();
                    row.forEach((key, value) -> normalized.put(key, value instanceof byte[] bytes
                            ? HexFormat.of().formatHex(bytes) : String.valueOf(value)));
                    return normalized.toString();
                }).sorted().toList());
            }
            return result;
        });
    }
    private void assertCoherent(AttemptStartService.Result result, int size) {
        tx.executeWithoutResult(s -> {
            var tuple = sql.queryForMap("""
                    SELECT BIN_TO_UUID(t.attempt_id) attempt_id, BIN_TO_UUID(t.form_id) form_id,
                           BIN_TO_UUID(f.quiz_revision_id) form_revision, BIN_TO_UUID(p.quiz_revision_id) policy_revision,
                           l.execution_state, l.answer_collection_state, l.row_version, t.last_sequence,
                           BIN_TO_UUID(u.user_id) user_id
                    FROM attempt_trace_stream t JOIN attempt_form f ON f.id=t.form_id
                    JOIN attempt_policy_snapshot p ON p.id=t.policy_snapshot_id
                    JOIN attempt_lifecycle_state l ON l.attempt_id=t.attempt_id
                    JOIN attempt_participant_subject u ON u.attempt_id=t.attempt_id
                    WHERE t.attempt_id=UUID_TO_BIN(?)
                    """, result.attemptId().toString());
            assertThat(tuple).containsEntry("attempt_id", result.attemptId().toString()).containsEntry("form_id", result.formId().toString())
                    .containsEntry("form_revision", result.quizRevisionId().toString()).containsEntry("policy_revision", result.quizRevisionId().toString())
                    .containsEntry("execution_state", "IN_PROGRESS").containsEntry("answer_collection_state", "NOT_STARTED")
                    .containsEntry("row_version", 1L).containsEntry("last_sequence", 2L);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form_item WHERE form_id=UUID_TO_BIN(?)", Integer.class, result.formId().toString())).isEqualTo(size);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_command_receipt WHERE attempt_id=UUID_TO_BIN(?) AND status='COMMITTED'", Integer.class, result.attemptId().toString())).isEqualTo(1);
            assertThat(sql.queryForList("SELECT event_type FROM attempt_trace_event WHERE attempt_id=UUID_TO_BIN(?) ORDER BY sequence_number", String.class, result.attemptId().toString()))
                    .containsExactly("FORM_CREATED", "ATTEMPT_STARTED");
        });
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
        assertThat(count("attempt_form")).isEqualTo(1);
        assertThat(count("attempts")).isZero();
    }
}
