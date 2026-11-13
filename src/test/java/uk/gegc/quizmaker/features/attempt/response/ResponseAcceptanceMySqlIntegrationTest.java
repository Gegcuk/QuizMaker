package uk.gegc.quizmaker.features.attempt.response;

import uk.gegc.quizmaker.features.attempt.application.ResponseBatchAcceptanceService;
import uk.gegc.quizmaker.features.attempt.application.ResponseBatchAcceptanceService.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.media.application.SavedImageStorage;
import uk.gegc.quizmaker.features.media.application.impl.RetainedImageServiceImpl;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.*;
import uk.gegc.quizmaker.features.question.config.BaselineQuestionContractConfig;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizPracticeSourceServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionPublicationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.Command;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;
import uk.gegc.quizmaker.features.quiz.infra.repository.*;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import static org.assertj.core.api.Assertions.*;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

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
        ResponseAcceptanceServiceImpl.class, JdbcAcceptedResponseRepository.class,
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, uk.gegc.quizmaker.features.attempt.application.impl.AttemptTimingServiceImpl.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAttemptTimingRepository.class, AttemptAllowanceServiceImpl.class, JdbcAttemptAllowanceRepository.class, RetakeEligibilityServiceImpl.class, PublicationAttemptEntryReader.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        uk.gegc.quizmaker.features.attempt.application.impl.ResponseBatchAcceptanceServiceImpl.class,
        uk.gegc.quizmaker.features.attempt.infra.repository.JdbcResponseBatchResultRepository.class,
        ResponseAcceptanceMySqlIntegrationTest.Config.class})
class ResponseAcceptanceMySqlIntegrationTest {
    @Autowired ResponseBatchAcceptanceService batches;
    static final String SCHEMA = "attempt_acceptance_" + UUID.randomUUID().toString().replace("-", "");
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

    static class AdjustableClock extends Clock {
        volatile Instant now=Instant.parse("2026-11-05T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        @Override public Instant instant() { return now; }
    }
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock clock() { return new AdjustableClock(); }
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
                    return bean instanceof DataSource ds && !(bean instanceof AcceptanceDataSource) ? new AcceptanceDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired AttemptAccessService access;
    @Autowired ResponseAcceptanceService responses;
    @Autowired AttemptDeliveryService delivery;
    @Autowired org.springframework.context.ApplicationContext context;
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    @Autowired PublishedQuestionContractService binding;
    @Autowired QuizRevisionPublicationService publications;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired AttemptStartService starts;
    @Autowired AttemptLifecycleService lifecycle;
    @Autowired SimpleMeterRegistry metrics;
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    static final List<String> TARGET_TABLES = List.of("attempt_response_batch_result", "attempt_timing", "attempt_start_binding", "attempt_allowance_scope", "attempt_unclassified_publication", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form_selection", "attempt_form", "attempt_policy_snapshot");
    AcceptanceDataSource observer() { return (AcceptanceDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
        ((AdjustableClock)context.getBean(Clock.class)).now=Instant.parse("2026-11-05T12:00:00Z");
        tx = new TransactionTemplate(manager); observer().reset();
        username = "acceptance-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username, true);
        grant(ownerId, "ATTEMPT_READ", "ATTEMPT_CREATE");
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
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'acceptance-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'acceptance-%'");
        });
    }


    @ParameterizedTest @EnumSource(AnswerOrder.class)
    void fiveRealFamiliesAcceptOnceAndReplayFromNewServiceWithOriginalVersion(AnswerOrder order) {
        var started = starts.start(command(prepared(), order, "start"));
        var view = delivery.read(started.attemptId());
        var commands = new ArrayList<ResponseAcceptanceService.Command>();
        var results = new ArrayList<ResponseAcceptanceService.Result>();
        for (var item : view.form().items()) {
            var command = answer(started.attemptId(), item, 1 + commands.size(), "answer-" + commands.size());
            commands.add(command); var result = responses.accept(command); results.add(result);
            assertThat(result.replayed()).isFalse(); assertThat(result.stateVersion()).isEqualTo(commands.size() + 1);
        }
        var committed = targetSnapshot();
        for (int i = 0; i < commands.size(); i++) {
            var replay = recreated().accept(commands.get(i));
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.acceptedResponseId()).isEqualTo(results.get(i).acceptedResponseId());
            assertThat(replay.stateVersion()).isEqualTo(results.get(i).stateVersion());
        }
        assertThat(targetSnapshot()).isEqualTo(committed);
        assertThat(count("attempt_accepted_response")).isEqualTo(5);
        assertThat(count("attempt_trace_event")).isEqualTo(7);
        assertThat(count("attempt_command_receipt")).isEqualTo(6);
        assertThat(sql.queryForObject("SELECT execution_state FROM attempt_lifecycle_state", String.class)).isEqualTo("IN_PROGRESS");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM answers", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response a JOIN question_revision_contract_bindings b " +
                "ON a.question_revision_id=b.question_revision_id WHERE a.response_contract_id=b.response_contract_id " +
                "AND a.response_contract_version=b.response_contract_version", Integer.class)).isEqualTo(5);
        var choice = view.form().items().get(0);
        String chosen = stableInput(choice).path("selectedOptionId").asText();
        String expectedKey = sql.queryForObject("SELECT source_key FROM question_option_revisions WHERE id=UUID_TO_BIN(?)", String.class, chosen);
        assertThat(sql.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(normalized_response,'$.selectedOptionId')) " +
                "FROM attempt_accepted_response WHERE form_item_id=UUID_TO_BIN(?)", String.class, choice.id().toString())).isEqualTo(expectedKey);
    }

    @ParameterizedTest @ValueSource(strings = {"answer", "lifecycle", "trace", "receipt"})
    void failureAfterRealWriteRollsBackEveryEffectAndSameCommandCanSucceed(String checkpoint) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var request = answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(2), 1, "answer");
        var before = targetSnapshot(); var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            boolean selected = switch (checkpoint) {
                case "answer" -> statement.startsWith("INSERT INTO attempt_accepted_response");
                case "lifecycle" -> statement.stripLeading().startsWith("UPDATE attempt_lifecycle_state");
                case "trace" -> statement.stripLeading().startsWith("INSERT INTO attempt_trace_event");
                case "receipt" -> statement.stripLeading().startsWith("UPDATE attempt_command_receipt");
                default -> false;
            };
            if (selected && reached.compareAndSet(false, true)) throw new SQLException("private-response-marker", "HY000");
        };
        assertThatThrownBy(() -> responses.accept(request)).isInstanceOf(ResponseAcceptanceException.class)
                .hasMessageNotContaining("private-response-marker").hasNoCause();
        assertThat(reached).isTrue(); observer().reset();
        assertThat(targetSnapshot()).isEqualTo(before); // independent transactions, before fixture cleanup
        assertThat(recreated().accept(request).replayed()).isFalse();
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"1213,RETRYABLE", "1205,IN_PROGRESS", "0,UNAVAILABLE"})
    void nestedQuestionReadPreservesDatabaseFailureAndRollsBackTheReservation(int code, String reason) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var request = answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(2), 1, "answer");
        var before = targetSnapshot(); var reached = failNestedQuestionLock(code);
        try {
            assertThatThrownBy(() -> responses.accept(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class, failure -> {
                assertThat(failure.reason().name()).isEqualTo(reason);
                assertThat(failure.getSuppressed()).isEmpty();
            }).hasNoCause().hasMessageNotContaining("private-nested-read-marker");
            assertThat(reached).hasValue(1); // No automatic retry, even for the retryable classification.
        } finally { observer().reset(); }
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(recreated().accept(request).replayed()).isFalse();
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
        assertThat(count("attempt_trace_event")).isEqualTo(3);
        assertThat(count("attempt_command_receipt")).isEqualTo(2);
    }

    @ParameterizedTest @CsvSource({"1213,UNAVAILABLE", "1205,BUSY"})
    void deliveryKeepsItsExistingDatabaseConflictResponse(int code, String reason) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var before = targetSnapshot(); var reached = failNestedQuestionLock(code);
        try {
            assertThatThrownBy(() -> delivery.read(started.attemptId()))
                    .isInstanceOfSatisfying(uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                            failure -> assertThat(failure.reason().name()).isEqualTo(reason))
                    .hasNoCause().hasMessageNotContaining("private-nested-read-marker");
            assertThat(reached).hasValue(1);
        } finally { observer().reset(); }
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
    }

    private AtomicInteger failNestedQuestionLock(int code) {
        var reached = new AtomicInteger();
        observer().beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT locked.id FROM (")) {
                reached.incrementAndGet();
                throw new SQLException("private-nested-read-marker", code == 1213 ? "40001" : code == 1205 ? "HY000" : "08006", code);
            }
        };
        return reached;
    }

    @ParameterizedTest @CsvSource({"2,false", "4,false", "2,true", "4,true"})
    void realDeadlockInsideQuestionReadIsRetryableAndSameCommandRecovers(int isolation, boolean batched) throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var item = delivery.read(started.attemptId()).form().items().get(2);
        var request = answer(started.attemptId(), item, 1, "answer");
        var batchRequest = batch(started.attemptId(),delivery.read(started.attemptId()).form().items(),1,"deadlock-batch");
        String question = sql.queryForObject("SELECT BIN_TO_UUID(question_revision_id) FROM attempt_form_item_option_order " +
                "WHERE item_id=UUID_TO_BIN(?)", String.class, item.id().toString());
        sql.execute("CREATE TABLE acceptance_deadlock_weight(id INT PRIMARY KEY, n INT NOT NULL) ENGINE=InnoDB");
        sql.batchUpdate("INSERT INTO acceptance_deadlock_weight VALUES (?,0)",
                IntStream.range(0, 100).mapToObj(i -> new Object[]{i}).toList());
        var before = targetSnapshot(); var locked = new CountDownLatch(1); var reading = new CountDownLatch(1);
        var workers = Executors.newSingleThreadExecutor(); var reached = new AtomicInteger();
        try {
            var blocker = workers.submit(() -> {
                try (var connection = source.getConnection()) {
                    connection.setTransactionIsolation(isolation); connection.setAutoCommit(false);
                    try {
                        // More changed rows than acceptance: InnoDB selects the command as the cheaper victim.
                        try (var statement = connection.createStatement()) { statement.executeUpdate("UPDATE acceptance_deadlock_weight SET n=n+1"); }
                        lockRow(connection, "SELECT id FROM question_revisions WHERE id=UUID_TO_BIN(?) FOR UPDATE", question);
                        locked.countDown(); await(reading);
                        lockRow(connection, "SELECT attempt_id FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?) FOR UPDATE",
                                started.attemptId().toString());
                    } finally { connection.rollback(); }
                }
                return true;
            });
            await(locked);
            observer().beforeSql = (statement, connection) -> {
                if (statement.startsWith("SELECT locked.id FROM (")) { reached.incrementAndGet(); reading.countDown(); }
            };
            var commandTransaction = new TransactionTemplate(manager); commandTransaction.setIsolationLevel(isolation);
            assertThatThrownBy(() -> commandTransaction.execute(s -> batched ? batches.acceptBatch(batchRequest) : responses.accept(request)))
                    .isInstanceOfSatisfying(ResponseAcceptanceException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(ResponseAcceptanceException.Reason.RETRYABLE);
                        assertThat(failure.getSuppressed()).isEmpty();
                    }).hasNoCause();
            assertThat(blocker.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(reached).hasValue(1);
            assertThat(observer().sqlErrorCodes).containsExactly(1213); // Actual driver error, not a fault hook.
            observer().reset();
            assertThat(targetSnapshot()).isEqualTo(before); // New transactions after the deadlock and before cleanup.
            assertThat(sql.queryForObject("SELECT SUM(n) FROM acceptance_deadlock_weight", Integer.class)).isZero();
            if (batched) {
                var accepted = freshBatch().acceptBatch(batchRequest); assertThat(accepted.replayed()).isFalse();
                assertThat(freshBatch().acceptBatch(batchRequest).responses()).isEqualTo(accepted.responses());
                assertThat(count("attempt_accepted_response")).isEqualTo(5); assertThat(count("attempt_trace_event")).isEqualTo(7);
            } else {
                var accepted = recreated().accept(request); assertThat(accepted.replayed()).isFalse();
                assertThat(recreated().accept(request).acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
                assertThat(count("attempt_accepted_response")).isEqualTo(1); assertThat(count("attempt_trace_event")).isEqualTo(3);
            }
            assertThat(count("attempt_command_receipt")).isEqualTo(2);
        } finally {
            reading.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            observer().reset(); sql.execute("DROP TABLE acceptance_deadlock_weight");
        }
    }

    private static void lockRow(Connection connection, String query, String id) throws SQLException {
        try (var statement = connection.prepareStatement(query)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void commitUncertaintyReconcilesThroughFreshReceiptWithoutRepeatingAnAnswer(boolean committed) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var request = answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(2), 1, "answer");
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.stripLeading().startsWith("UPDATE attempt_command_receipt") && reached.compareAndSet(false, true)) {
                if (committed) observer().failAfterCommit.set(true); else observer().failBeforeCommit.set(true);
            }
        };
        rejected(request, "UNKNOWN_OUTCOME"); observer().reset();
        assertThat(reached).isTrue();
        assertThat(count("attempt_accepted_response")).isEqualTo(committed ? 1 : 0);
        var replay = recreated().accept(request);
        assertThat(replay.replayed()).isEqualTo(committed);
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
        assertThat(count("attempt_trace_event")).isEqualTo(3);
        assertThat(count("attempt_command_receipt")).isEqualTo(2);
    }

    @ParameterizedTest @CsvSource({"2,equal", "4,equal", "2,changed", "4,changed", "2,different-key", "4,different-key"})
    void independentConcurrentCommandsHaveARealWinnerAndOneDurableAnswer(int isolation, String variant) throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var request = answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(2), 1, "answer");
        var other = new ResponseAcceptanceService.Command(request.attemptId(), request.formItemId(), 1,
                CommandReceipt.Key.from(variant.equals("different-key") ? "other" : "answer"), request.type(),
                variant.equals("changed") ? json.createObjectNode().put("answer", false) : request.response());
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1); var workers = Executors.newFixedThreadPool(2);
        try {
            var one = workers.submit(() -> race(request, isolation, ready, go));
            var two = workers.submit(() -> race(other, isolation, ready, go));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            var results = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            var successes = results.stream().filter(ResponseAcceptanceService.Result.class::isInstance)
                    .map(ResponseAcceptanceService.Result.class::cast).toList();
            assertThat(successes.stream().filter(r -> !r.replayed())).hasSize(1);
            if (variant.equals("equal")) {
                assertThat(successes).hasSize(2);
                assertThat(successes).extracting(ResponseAcceptanceService.Result::acceptedResponseId).containsOnly(successes.get(0).acceptedResponseId());
            } else {
                assertThat(successes).hasSize(1);
                assertThat(results.stream().filter(ResponseAcceptanceException.Reason.class::isInstance))
                        .allMatch(reason -> reason == ResponseAcceptanceException.Reason.CONFLICT || reason == ResponseAcceptanceException.Reason.STALE);
            }
            var winner = (ResponseAcceptanceService.Result) results.stream().filter(ResponseAcceptanceService.Result.class::isInstance).findFirst().orElseThrow();
            var winningRequest = results.get(0) instanceof ResponseAcceptanceService.Result ? request : other;
            assertThat(recreated().accept(winningRequest).acceptedResponseId()).isEqualTo(winner.acceptedResponseId());
            assertThat(count("attempt_accepted_response")).isEqualTo(1);
            assertThat(count("attempt_trace_event")).isEqualTo(3);
            assertThat(count("attempt_command_receipt")).isEqualTo(2);
        } finally { go.countDown(); workers.shutdownNow(); }
    }

    private Object race(ResponseAcceptanceService.Command request, int isolation, CountDownLatch ready, CountDownLatch go) {
        authenticate(username); var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
        try { return transaction.execute(s -> { ready.countDown(); await(go); return responses.accept(request); }); }
        catch (ResponseAcceptanceException failure) { return failure.reason(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void staleForeignItemsWrongFamilyInvalidValuesAndPermissionLossLeaveNoWrites() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var item = delivery.read(started.attemptId()).form().items().get(2);
        var request = answer(started.attemptId(), item, 1, "answer");
        var before = targetSnapshot();
        rejected(new ResponseAcceptanceService.Command(request.attemptId(), item.id(), 0, request.key(), item.type(), request.response()), "STALE");
        rejected(new ResponseAcceptanceService.Command(request.attemptId(), UUID.randomUUID(), 1, request.key(), item.type(), request.response()), "NOT_FOUND");
        rejected(new ResponseAcceptanceService.Command(request.attemptId(), item.id(), 1, request.key(), QuestionType.MCQ_MULTI,
                json.createObjectNode().set("selectedOptionIds", json.createArrayNode().add(UUID.randomUUID().toString()))), "INVALID_INPUT");
        rejected(new ResponseAcceptanceService.Command(request.attemptId(), item.id(), 1, request.key(), item.type(), json.createObjectNode()), "INVALID_INPUT");
        assertThat(targetSnapshot()).isEqualTo(before);
        SecurityContextHolder.clearContext(); rejected(request, "UNAUTHENTICATED"); authenticate(username);
        UUID stranger = user("stranger-" + UUID.randomUUID(), true); grant(stranger, "ATTEMPT_CREATE");
        authenticate(users.findById(stranger).orElseThrow().getUsername()); rejected(request, "NOT_FOUND"); authenticate(username);
        var accepted = responses.accept(request);
        var after = targetSnapshot();
        revoke(ownerId, "ATTEMPT_CREATE"); rejected(request, "NOT_FOUND"); assertThat(targetSnapshot()).isEqualTo(after);
        grant(ownerId, "ATTEMPT_CREATE"); assertThat(responses.accept(request).acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
        // ANSWER uses ATTEMPT_CREATE; lack of the separate read permission must not change this contract.
        revoke(ownerId, "ATTEMPT_READ"); assertThat(responses.accept(request).replayed()).isTrue();
    }

    @Test void aSecondKeyCannotReplaceAcceptedEvidenceAndTerminalFixturesRejectFreshCommands() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var items = delivery.read(started.attemptId()).form().items();
        var request = answer(started.attemptId(), items.get(2), 1, "answer");
        responses.accept(request); var before = targetSnapshot();
        rejected(answer(started.attemptId(), items.get(2), 2, "second-key"), "CONFLICT");
        assertThat(targetSnapshot()).isEqualTo(before);
        tx.executeWithoutResult(s -> lifecycle.save(started.attemptId(), 2,
                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE)));
        // This is a terminal guard fixture, not proof of the future #529 terminal command.
        var terminal = targetSnapshot();
        rejected(answer(started.attemptId(), items.get(0), 3, "late"), "INACTIVE");
        assertThat(targetSnapshot()).isEqualTo(terminal);
        assertThat(recreated().accept(request).replayed()).isTrue();
    }

    @Test void unknownSavedContractHasNoAnswerAndRestoringItAllowsTheSameRequest() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var request = answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(2), 1, "answer");
        futureResponseContract(request.formItemId(), 2);
        var before = targetSnapshot();
        try {
            rejected(request, "UNSUPPORTED");
            assertThat(targetSnapshot()).isEqualTo(before);
        } finally { futureResponseContract(request.formItemId(), 1); }
        assertThat(responses.accept(request).replayed()).isFalse();
    }

    private void futureResponseContract(UUID itemId, int version) {
        // A coherent future-version fixture, with foreign keys enabled throughout. TRUE_FALSE has no option rows.
        tx.executeWithoutResult(s -> {
            var row = sql.queryForMap("SELECT * FROM attempt_form_item_option_order WHERE item_id=UUID_TO_BIN(?)", itemId.toString());
            assertThat(row.get("option_count")).isEqualTo(0);
            sql.update("DELETE FROM attempt_form_item_option_order WHERE item_id=UUID_TO_BIN(?)", itemId.toString());
            var bytes = java.nio.ByteBuffer.wrap((byte[]) row.get("question_revision_id"));
            var savedBinding = new uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding(
                    new UUID(bytes.getLong(), bytes.getLong()),
                    new uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion((String) row.get("response_contract_id"), version),
                    new uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion((String) row.get("scoring_contract_id"), (Integer) row.get("scoring_contract_version")));
            sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=?,binding_hash=? WHERE question_revision_id=?",
                    version, savedBinding.fingerprint(), row.get("question_revision_id"));
            row.put("response_contract_version", version);
            sql.update("INSERT INTO attempt_form_item_option_order (" + String.join(",", row.keySet()) + ") VALUES (" +
                    String.join(",", Collections.nCopies(row.size(), "?")) + ")", row.values().toArray());
        });
    }

    @Test void duplicateDatabaseWritesCannotBypassTheNaturalKey() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        responses.accept(answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(2), 1, "answer"));
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO attempt_accepted_response SELECT UUID_TO_BIN(UUID()),schema_version,attempt_id,form_id,form_item_id,
                question_revision_id,response_contract_id,response_contract_version,normalized_response,UUID_TO_BIN(UUID()),accepted_at
                FROM attempt_accepted_response
                """)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @Test void acceptanceQueryCountIsBoundedAcrossMultipleParentsAndLargerForms(TestReporter reporter) {
        var measured = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var requests = new ArrayList<ResponseAcceptanceService.Command>();
            for (int parent = 0; parent < 3; parent++) {
                quizId = newQuiz();
                var revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList());
                binding.bind(quizId, revision);
                var started = starts.start(command(revision, AnswerOrder.AUTHORED, "parent-" + size + "-" + parent));
                requests.add(answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(0), 1, "accept-" + size + "-" + parent));
            }
            observer().selects.clear(); requests.forEach(responses::accept); measured.add(observer().selects.size());
        }
        assertThat(measured.get(0)).isGreaterThan(0); assertThat(measured.get(1)).isEqualTo(measured.get(0));
        reporter.publishEntry("acceptance-selects-three-parents-1-vs-20-items", measured.toString());
    }

    private ResponseAcceptanceService recreated() {
        return new ResponseAcceptanceServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), access,
                context.getBean(AttemptReadFactsService.class), lifecycle,
                context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class),
                new CommandReceiptServiceImpl(context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.CommandReceiptRepository.class),
                        manager, context.getBean(Clock.class), metrics), context.getBean(ExecutionTraceService.class), context.getBean(Clock.class), metrics);
    }

    @Test void httpUsesRealAcceptanceAndCannotChangeOrMoveAnAcceptedAnswer() throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.SHUFFLED_PER_ATTEMPT, "start"));
        var items = delivery.read(started.attemptId()).form().items();
        var converter = new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter();
        var converters = new ArrayList<org.springframework.http.converter.HttpMessageConverter<?>>(List.of(converter));
        new uk.gegc.quizmaker.features.attempt.api.ResponseAcceptanceJsonConfig().extendMessageConverters(converters);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new uk.gegc.quizmaker.features.attempt.api.ResponseAcceptanceController(responses, json))
                .setControllerAdvice(new uk.gegc.quizmaker.features.attempt.api.ResponseAcceptanceErrorHandler())
                .setMessageConverters(converters.toArray(org.springframework.http.converter.HttpMessageConverter[]::new)).build();
        String path = "/api/v1/attempts/" + started.attemptId() + "/responses";
        var body = json.createObjectNode().put("formItemId", items.get(0).id().toString()).put("expectedStateVersion", 1);
        var value = body.putObject("response").put("type", "MCQ_SINGLE").put("selectedOptionId", UUID.randomUUID().toString());
        var before = targetSnapshot();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key", "http-answer")
                .contentType("application/json").content(body.toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
        assertThat(targetSnapshot()).isEqualTo(before);
        value.put("selectedOptionId", stableInput(items.get(0)).path("selectedOptionId").asText());
        String accepted = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Idempotency-Key", "http-answer").contentType("application/json").content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        var committed = targetSnapshot();
        String replay = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Idempotency-Key", "http-answer").contentType("application/json").content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replay).path("acceptedResponseId")).isEqualTo(json.readTree(accepted).path("acceptedResponseId"));
        assertThat(json.readTree(replay).path("replayed").booleanValue()).isTrue();
        body.put("formItemId", items.get(1).id().toString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key", "http-answer")
                .contentType("application/json").content(body.toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertThat(targetSnapshot()).isEqualTo(committed);
    }

    @Test void currentEditorAuthorityAndAccountActivityAreRequiredEvenForReplay() {
        UUID revision = prepared();
        String editor = "accept-editor-" + UUID.randomUUID().toString().substring(0, 8); UUID editorId = user(editor, false);
        grant(editorId, "ATTEMPT_READ", "ATTEMPT_CREATE", "QUESTION_UPDATE", "QUESTION_ADMIN");
        authenticate(editor); var started = starts.start(command(revision, AnswerOrder.AUTHORED, "editor"));
        var items = delivery.read(started.attemptId()).form().items();
        var request = answer(started.attemptId(), items.get(2), 1, "answer");
        var accepted = responses.accept(request); var committed = targetSnapshot();
        sql.update("UPDATE quizzes SET visibility='PUBLIC' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        revoke(editorId, "QUESTION_UPDATE"); rejected(request, "NOT_FOUND");
        rejected(answer(started.attemptId(), items.get(0), 2, "next"), "NOT_FOUND");
        grant(editorId, "QUESTION_UPDATE");
        sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", editorId.toString());
        rejected(request, "NOT_FOUND");
        sql.update("UPDATE users SET is_active=TRUE WHERE user_id=UUID_TO_BIN(?)", editorId.toString());
        assertThat(recreated().accept(request).acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
        assertThat(targetSnapshot()).isEqualTo(committed);
    }

    @Test void itemFromAnotherRealFormAndReusedStartKeyAreRejectedWithoutWrites() {
        UUID revision = prepared();
        var one = starts.start(command(revision, AnswerOrder.AUTHORED, "first-start"));
        // Another authorized subject may start the same publication. Keep identical question
        // revisions so the negative case proves form identity, not merely a different question.
        String editorName = "form-editor-" + UUID.randomUUID().toString().substring(0, 10);
        UUID editor = user(editorName, false);
        grant(editor, "QUESTION_UPDATE", "QUIZ_MODERATE", "ATTEMPT_READ");
        authenticate(editorName);
        var two = starts.start(command(revision, AnswerOrder.AUTHORED, "second-start"));
        var other = delivery.read(two.attemptId()).form().items().get(2);
        authenticate(username);
        var own = delivery.read(one.attemptId()).form().items().get(2);
        String sourceIdentity = "SELECT BIN_TO_UUID(source_item_id) FROM attempt_form_item WHERE form_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)";
        assertThat(sql.queryForObject(sourceIdentity, String.class, two.formId().toString(), other.id().toString()))
                .isEqualTo(sql.queryForObject(sourceIdentity, String.class, one.formId().toString(), own.id().toString()));
        assertThat(other.id()).isNotEqualTo(own.id());
        var before = targetSnapshot();
        rejected(answer(one.attemptId(), other, 1, "answer"), "NOT_FOUND");
        rejected(answer(one.attemptId(), own, 1, "first-start"), "CONFLICT");
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(responses.accept(answer(one.attemptId(), own, 1, "answer")).replayed()).isFalse();
    }

    @Test void receiptExpiryAndCleanupRetainAcceptedEvidence() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var request = answer(started.attemptId(), delivery.read(started.attemptId()).form().items().get(2), 1, "answer");
        var accepted = responses.accept(request);
        tx.executeWithoutResult(s -> lifecycle.save(started.attemptId(), 2,
                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE)));
        var future = Clock.offset(context.getBean(Clock.class), Duration.ofDays(31));
        var receipts = new CommandReceiptServiceImpl(context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.CommandReceiptRepository.class),
                manager, future, metrics);
        var later = new ResponseAcceptanceServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), access,
                context.getBean(AttemptReadFactsService.class), lifecycle,
                context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class),
                receipts, context.getBean(ExecutionTraceService.class), future, metrics);
        assertThatThrownBy(() -> later.accept(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                ex -> assertThat(ex.reason()).isEqualTo(ResponseAcceptanceException.Reason.EXPIRED));
        Integer purged = tx.execute(s -> receipts.purgeExpired(started.attemptId(), 3, 100));
        assertThat(purged).isEqualTo(2);
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT BIN_TO_UUID(id) FROM attempt_accepted_response", String.class))
                .isEqualTo(accepted.acceptedResponseId().toString());
        rejected(request, "INACTIVE");
        assertThat(count("attempt_command_receipt")).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void callerWithOldSnapshotAcceptsUsingCurrentCommittedState(int isolation) throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var items = delivery.read(started.attemptId()).form().items();
        var snapshot = new CountDownLatch(1); var committed = new CountDownLatch(1); var worker = Executors.newSingleThreadExecutor();
        try {
            var later = worker.submit(() -> {
                authenticate(username); var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                try { return transaction.execute(s -> {
                    assertThat(sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state", Long.class)).isEqualTo(1);
                    snapshot.countDown(); await(committed);
                    return responses.accept(answer(started.attemptId(), items.get(0), 2, "later"));
                }); } finally { SecurityContextHolder.clearContext(); }
            });
            await(snapshot); responses.accept(answer(started.attemptId(), items.get(2), 1, "first")); committed.countDown();
            assertThat(later.get(20, TimeUnit.SECONDS).stateVersion()).isEqualTo(3);
            assertThat(count("attempt_accepted_response")).isEqualTo(2);
        } finally { committed.countDown(); worker.shutdownNow(); }
    }
    private long count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)); }
    private void rejected(ResponseAcceptanceService.Command c, String reason) {
        assertThatThrownBy(() -> responses.accept(c)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                ex -> assertThat(ex.reason().name()).isEqualTo(reason)).hasNoCause();
    }
    private ResponseAcceptanceService.Command answer(UUID attempt, ParticipantForm.Item item, long version, String key) {
        return new ResponseAcceptanceService.Command(attempt, item.id(), version, CommandReceipt.Key.from(key), item.type(), stableInput(item));
    }
    private ObjectNode stableInput(ParticipantForm.Item item) {
        var value = json.createObjectNode();
        switch (item.type()) {
            case TRUE_FALSE -> value.put("answer", true);
            case MCQ_SINGLE -> value.put("selectedOptionId", ((ParticipantContent.Choices) item.content()).options().get(0).id().toString());
            case MCQ_MULTI -> value.putArray("selectedOptionIds").add(((ParticipantContent.Choices) item.content()).options().get(0).id().toString());
            case ORDERING -> { var ids = value.putArray("orderedItemIds"); ((ParticipantContent.Ordering) item.content()).items().forEach(o -> ids.add(o.id().toString())); }
            case FILL_GAP -> { var entries = value.putArray("answers"); ((ParticipantContent.FillGap) item.content()).gaps()
                    .forEach(g -> entries.addObject().put("gapId", g.id()).put("answer", "Paris")); }
            default -> throw new AssertionError(item.type());
        }
        return value;
    }

    private void revoke(UUID userId, String permission) {
        tx.executeWithoutResult(status -> sql.update("""
                DELETE rp FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name=?
                """, userId.toString(), permission));
    }
    private Map<String, List<String>> targetSnapshot() {
        return tx.execute(status -> {
            Map<String, List<String>> result = new TreeMap<>();
            TARGET_TABLES.forEach(table -> result.put(table, sql.queryForList("SELECT * FROM " + table).stream().map(row -> {
                var ordered = new TreeMap<String, String>();
                row.forEach((key, value) -> ordered.put(key, value instanceof byte[] b ? HexFormat.of().formatHex(b) : String.valueOf(value)));
                return ordered.toString();
            }).sorted().toList()));
            return result;
        });
    }
    private static void await(CountDownLatch latch) {
        await(latch,10);
    }
    private static void await(CountDownLatch latch,int seconds) {
        try { if (!latch.await(seconds, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }


    private UUID draft(QuestionType type) {
        return tx.execute(s -> {
            var q = new Question(); q.setType(type); q.setDifficulty(Difficulty.EASY); q.setQuestionText("Frozen " + type);
            var content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
            if (type == QuestionType.FILL_GAP)
                content.putArray("options").add("Paris").add("Rome").add("London").add("Berlin").add("Madrid").add("Lisbon").add("Vienna");
            q.setContent(content.toString()); q.setExplanation("protected-explanation");
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
            String role = "acceptance-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
    }

    private UUID newQuiz() {
        return tx.execute(s -> {
            var category = new Category(); category.setName("acceptance-" + UUID.randomUUID()); categories.saveAndFlush(category);
            var quiz = new Quiz(); quiz.setCreator(users.findById(ownerId).orElseThrow()); quiz.setCategory(category);
            quiz.setTitle("Start fixture " + UUID.randomUUID()); quiz.setDescription("Synthetic source"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
    }
    @Test void timedAcceptanceRejectsAtDeadlineWithoutWritesAndAcceptsBeforeIt() {
        UUID revision=prepared(); var started=starts.start(timed(command(revision,AnswerOrder.AUTHORED,"timed-response")));
        var item=delivery.read(started.attemptId()).form().items().get(0);
        Instant deadline=Instant.parse("2026-11-05T12:30:00Z"); var clock=(AdjustableClock)context.getBean(Clock.class);
        var request=answer(started.attemptId(),item,1,"timed-answer");
        clock.now=deadline; rejected(request,"INACTIVE"); assertThat(count("attempt_accepted_response")).isZero();
        clock.now=deadline.minusNanos(1000); var accepted=responses.accept(request);
        assertThat(accepted.replayed()).isFalse(); assertThat(count("attempt_accepted_response")).isEqualTo(1);
        clock.now=deadline.plusSeconds(60); assertThat(responses.accept(request).replayed()).isTrue();
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }
    @Test void timelyReceiptStillSucceedsWhenRealLockProcessingCrossesTheDeadline() {
        UUID revision=prepared(); var started=starts.start(timed(command(revision,AnswerOrder.AUTHORED,"timed-lock")));
        var item=delivery.read(started.attemptId()).form().items().get(0);
        var clock=(AdjustableClock)context.getBean(Clock.class); Instant deadline=clock.now.plusSeconds(1800);
        clock.now=deadline.minusSeconds(1); var reached=new AtomicBoolean();
        observer().beforeSql=(statement,connection) -> {
            if (statement.contains("FROM attempt_lifecycle_state") && statement.contains("FOR UPDATE")) {
                reached.set(true); clock.now=deadline.plusSeconds(1);
            }
        };
        var result=responses.accept(answer(started.attemptId(),item,1,"receipt-before-deadline"));
        assertThat(reached).isTrue(); assertThat(result.replayed()).isFalse(); assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }
    private static AttemptStartService.Command timed(AttemptStartService.Command c) {
        return new AttemptStartService.Command(c.version(),c.quizId(),c.content(),c.publicationVersion(),c.key(),c.preset(),c.context(),c.legacyMode(),
                c.answerOrder(),c.draftsEnabled(),c.navigation(),c.delivery(),c.selection(),c.attemptLimit(),new TimingPolicy(TimingMode.TOTAL_ATTEMPT,Duration.ofMinutes(30),null));
    }

    private BatchCommand batch(
            UUID attempt, List<ParticipantForm.Item> items, long version, String key) {
        return new BatchCommand(attempt, version,
                CommandReceipt.Key.from(key), items.stream().map(i -> new BatchItem(
                i.id(), i.type(), stableInput(i))).toList());
    }

    private ResponseBatchAcceptanceService freshBatch() {
        return new uk.gegc.quizmaker.features.attempt.application.impl.ResponseBatchAcceptanceServiceImpl(
                context.getBean(AuthenticatedSubjectResolver.class), access, context.getBean(AttemptReadFactsService.class), lifecycle,
                context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class),
                context.getBean(CommandReceiptService.class), context.getBean(ExecutionTraceService.class),
                context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.ResponseBatchResultRepository.class),
                context.getBean(Clock.class), metrics, manager);
    }

    @ParameterizedTest @CsvSource({"2,false","4,false","2,true","4,true"})
    void independentParticipantsAndQuizzesBothCommitWithoutAnswerGapLocks(int isolation, boolean populated) throws Exception {
        var left=starts.start(command(prepared(),AnswerOrder.AUTHORED,"independent-left-start"));
        var leftItems=delivery.read(left.attemptId()).form().items(); String leftUser=username;
        if(populated) responses.accept(answer(left.attemptId(),leftItems.get(0),1,"left-existing"));
        var a=batch(left.attemptId(),leftItems.subList(populated?1:0,5),populated?2:1,"independent-left");
        username="independent-"+UUID.randomUUID().toString().substring(0,12); ownerId=user(username,true);
        grant(ownerId,"ATTEMPT_READ","ATTEMPT_CREATE"); quizId=newQuiz(); authenticate(username);
        var right=starts.start(command(prepared(),AnswerOrder.AUTHORED,"independent-right-start"));
        var rightItems=delivery.read(right.attemptId()).form().items(); String rightUser=username;
        if(populated) responses.accept(answer(right.attemptId(),rightItems.get(0),1,"right-existing"));
        var b=batch(right.attemptId(),rightItems.subList(populated?1:0,5),populated?2:1,"independent-right");
        var ready=new CountDownLatch(2); var release=new CountDownLatch(1);
        var seen=ConcurrentHashMap.<Integer>newKeySet(); var workers=Executors.newFixedThreadPool(2);
        observer().beforeSql=(statement,connection)->{
            if(statement.startsWith("INSERT INTO attempt_accepted_response") && seen.add(connection)) {
                ready.countDown(); await(release);
            }
        };
        try {
            java.util.function.BiFunction<BatchCommand,String,Object> run=(request,user)->{
                authenticate(user); var transaction=new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                try { return transaction.execute(s->batches.acceptBatch(request)); }
                catch(ResponseAcceptanceException e) { return e.reason(); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var first=workers.submit(()->run.apply(a,leftUser)); var second=workers.submit(()->run.apply(b,rightUser));
            if(!ready.await(10,TimeUnit.SECONDS)) {
                release.countDown();
                assertThat(first.get(20,TimeUnit.SECONDS)).as("left reached insert; driver codes %s",observer().sqlErrorCodes).isInstanceOf(BatchResult.class);
                assertThat(second.get(20,TimeUnit.SECONDS)).as("right reached insert; driver codes %s",observer().sqlErrorCodes).isInstanceOf(BatchResult.class);
                fail("Both independent commands must reach their real insert checkpoint");
            }
            var locks=sql.queryForList("SELECT LOCK_MODE,LOCK_DATA FROM performance_schema.data_locks "
                    +"WHERE OBJECT_SCHEMA=? AND OBJECT_NAME='attempt_accepted_response' AND LOCK_TYPE='RECORD'",SCHEMA);
            release.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS)).isInstanceOfSatisfying(BatchResult.class,
                    result->assertThat(result.responses()).hasSize(populated?4:5));
            assertThat(second.get(30,TimeUnit.SECONDS)).isInstanceOfSatisfying(BatchResult.class,
                    result->assertThat(result.responses()).hasSize(populated?4:5));
            assertThat(locks).allSatisfy(lock->assertThat(lock.get("LOCK_MODE").toString()).contains("REC_NOT_GAP"));
            assertThat(observer().sqlErrorCodes).doesNotContain(1213);
            assertThat(count("attempt_accepted_response")).isEqualTo(10);
            assertThat(count("attempt_trace_event")).isEqualTo(14);
            assertThat(count("attempt_command_receipt")).isEqualTo(populated?6:4);
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(15,TimeUnit.SECONDS)).isTrue(); observer().reset();
        }
    }

    @ParameterizedTest @CsvSource({"4,499,false","4,500,true","2,499,false","2,500,true","4,37,false","4,37,true"})
    void populatedReadAndReplayDoNotBlockAnotherParticipants500Responses(int isolation,int acceptedCount,boolean replay) throws Exception {
        var revision=publish(0,IntStream.range(0,500).mapToObj(i->draft(QuestionType.TRUE_FALSE)).toList()); binding.bind(quizId,revision);
        var started=starts.start(command(revision,AnswerOrder.AUTHORED,"held-read-start"));
        var items=delivery.read(started.attemptId()).form().items(); String readerUser=username;
        var initial=batch(started.attemptId(),items.subList(0,acceptedCount),1,"held-read-initial"); var original=batches.acceptBatch(initial);
        UUID receipt=UUID.fromString(sql.queryForObject("SELECT BIN_TO_UUID(command_id) FROM attempt_response_batch_result WHERE attempt_id=UUID_TO_BIN(?)",String.class,started.attemptId().toString()));
        var readCommand=replay?initial:batch(started.attemptId(),items.subList(acceptedCount,acceptedCount+1),acceptedCount+1,"held-read-next");
        username="independent-large-"+UUID.randomUUID().toString().substring(0,10);ownerId=user(username,true);
        grant(ownerId,"ATTEMPT_READ","ATTEMPT_CREATE");quizId=newQuiz();authenticate(username);String writerUser=username;
        var otherRevision=publish(0,IntStream.range(0,500).mapToObj(i->draft(QuestionType.TRUE_FALSE)).toList());binding.bind(quizId,otherRevision);
        var other=starts.start(command(otherRevision,AnswerOrder.AUTHORED,"independent-large-start"));
        var request=batch(other.attemptId(),delivery.read(other.attemptId()).form().items(),1,"independent-large");
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var reader=workers.submit(()->{
                authenticate(readerUser);var outer=new TransactionTemplate(manager);outer.setIsolationLevel(isolation);
                try{return outer.execute(s->{
                    // Isolate both accepted-reference readers. The complete command also
                    // holds question-revision and receipt locks unrelated to this regression.
                    if(replay) {
                        var result=context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.ResponseBatchResultRepository.class)
                                .read(receipt,started.attemptId(),original.stateVersion());
                        assertThat(result.responses()).isEqualTo(original.responses());
                    } else {
                        var references=context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class)
                                .references(started.attemptId());
                        assertThat(references).extracting(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.Reference::formItemId)
                                .containsExactlyInAnyOrderElementsOf(items.subList(0,acceptedCount).stream().map(ParticipantForm.Item::id).toList());
                    }
                    held.countDown();await(release,30);return true;
                });}finally{SecurityContextHolder.clearContext();}
            });
            await(held,20);
            var writer=workers.submit(()->{
                authenticate(writerUser);var outer=new TransactionTemplate(manager);outer.setIsolationLevel(isolation);
                try{return outer.execute(s->{
                    Integer timeout=sql.queryForObject("SELECT @@session.innodb_lock_wait_timeout",Integer.class);
                    try{sql.execute("SET SESSION innodb_lock_wait_timeout=2");return (Object)batches.acceptBatch(request);}
                    finally{sql.execute("SET SESSION innodb_lock_wait_timeout="+timeout);}
                });}catch(ResponseAcceptanceException e){return e.reason();}finally{SecurityContextHolder.clearContext();}
            });
            var waits=new LinkedHashSet<Map<String,Object>>();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!writer.isDone() && System.nanoTime()<until){
                waits.addAll(sql.queryForList("SELECT r.OBJECT_NAME,r.INDEX_NAME,r.LOCK_MODE requested,b.LOCK_MODE held,b.LOCK_DATA "
                        +"FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                        +"JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                        +"WHERE r.OBJECT_SCHEMA=?",SCHEMA));
                Thread.sleep(10);
            }
            Object result=writer.get(5,TimeUnit.SECONDS);
            int beforeRelease=sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response WHERE attempt_id=UUID_TO_BIN(?)",Integer.class,other.attemptId().toString());
            assertThat(reader.isDone()).as("reader must still hold its transaction when the independent writer finishes").isFalse();
            release.countDown();reader.get(20,TimeUnit.SECONDS);
            // Successful control for the old blocking implementation; it still fails the
            // unconditional first-attempt assertion below rather than treating retry as success.
            authenticate(writerUser);assertThat(freshBatch().acceptBatch(request).responses()).hasSize(500);
            authenticate(readerUser);
            if(!replay) assertThat(freshBatch().acceptBatch(readCommand).responses()).hasSize(1);
            assertThat(freshBatch().acceptBatch(initial).replayed()).isTrue();
            assertThat(result).as("first independent command; observed waits %s; driver codes %s",waits,observer().sqlErrorCodes)
                    .isInstanceOfSatisfying(BatchResult.class,r->assertThat(r.responses()).hasSize(500));
            assertThat(beforeRelease).isEqualTo(500);assertThat(waits).isEmpty();assertThat(observer().sqlErrorCodes).doesNotContain(1205,1213);
        }finally{release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(20,TimeUnit.SECONDS)).isTrue();observer().reset();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void acceptedInsertFailureIsSanitizedBeforeTransactionTraceAndRollsBackCaller(boolean batch) {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"safe-insert-start"));
        var items=delivery.read(started.attemptId()).form().items(); var request=batch(started.attemptId(),items,1,"safe-insert");
        var before=targetSnapshot(); String original=sql.queryForObject("SELECT description FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)",String.class,quizId.toString());
        String marker="private-accepted-insert-"+UUID.randomUUID(); var reached=new AtomicInteger();
        observer().afterSql=(statement,connection)->{
            if(statement.startsWith("INSERT INTO attempt_accepted_response") && reached.incrementAndGet()==(batch?2:1)) {
                var failure=new SQLException(marker,"08006"); failure.initCause(new IllegalArgumentException(marker+"-cause"));
                failure.addSuppressed(new IllegalStateException(marker+"-suppressed")); throw failure;
            }
        };
        try(var logs=new TransactionLogs()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                sql.update("UPDATE quizzes SET description='caller-before' WHERE quiz_id=UUID_TO_BIN(?)",quizId.toString());
                assertThatThrownBy(()->{ if(batch) batches.acceptBatch(request); else responses.accept(answer(started.attemptId(),items.get(0),1,"safe-single")); })
                        .isInstanceOfSatisfying(ResponseAcceptanceException.class,e->assertThat(e.getSuppressed()).isEmpty()).hasNoCause()
                        .hasMessageNotContaining(marker);
                sql.update("UPDATE quizzes SET description='caller-after' WHERE quiz_id=UUID_TO_BIN(?)",quizId.toString());
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(reached).hasValue(batch?2:1);
            assertThat(logs.events.list).anyMatch(e->e.getLoggerName().contains("TransactionInterceptor")
                    && e.getLevel()==ch.qos.logback.classic.Level.TRACE);
            assertThat(logs.rendered()).doesNotContain(marker,"PreparedStatementCallback; SQL [INSERT INTO attempt_accepted_response");
        } finally { observer().reset(); }
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(sql.queryForObject("SELECT description FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)",String.class,quizId.toString())).isEqualTo(original);
        if(batch) assertThat(freshBatch().acceptBatch(request).responses()).hasSize(5);
        else assertThat(recreated().accept(answer(started.attemptId(),items.get(0),1,"safe-single")).replayed()).isFalse();
    }

    @Test void currentIdentityReadPreservesOlderRrSnapshotOwnWritesAndCallerRollback() throws Exception {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"index-rr-start"));
        var items=delivery.read(started.attemptId()).form().items();
        var request=batch(started.attemptId(),items.subList(1,5),2,"index-rr-batch");
        var reader=context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class);
        var workers=Executors.newSingleThreadExecutor(); var outer=new TransactionTemplate(manager); outer.setIsolationLevel(4);
        try {
            outer.executeWithoutResult(status->{
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response",Integer.class)).isZero();
                try {
                    workers.submit(()->{ authenticate(username); try { return responses.accept(answer(started.attemptId(),items.get(0),1,"index-rr-peer")); }
                        finally { SecurityContextHolder.clearContext(); } }).get(20,TimeUnit.SECONDS);
                } catch(Exception failure) { throw new AssertionError(failure); }
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response",Integer.class)).isZero();
                assertThat(reader.references(started.attemptId())).extracting(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.Reference::formItemId)
                        .containsExactly(items.get(0).id());
                assertThat(batches.acceptBatch(request).responses()).hasSize(4);
                assertThat(reader.references(started.attemptId())).hasSize(5);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response",Integer.class)).isEqualTo(4);
                assertThat(sql.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
                status.setRollbackOnly();
            });
        } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(15,TimeUnit.SECONDS)).isTrue(); }
        assertThat(count("attempt_accepted_response")).isEqualTo(1); assertThat(count("attempt_command_receipt")).isEqualTo(2);
        assertThat(count("attempt_trace_event")).isEqualTo(3); assertThat(count("attempt_response_batch_result")).isZero();
        assertResponseIndexMatchesSource(started.attemptId());
        assertThat(freshBatch().acceptBatch(request).replayed()).isFalse(); assertResponseIndexMatchesSource(started.attemptId());
    }

    @Test void referenceIndexMigrationBackfillsExistingRowsAndTracksSourceMetadataChanges() throws Exception {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"index-upgrade-start"));
        var items=delivery.read(started.attemptId()).form().items();
        // Recreate the previous schema in this disposable test database. Populate it through
        // the real single-response writer before applying the actual new Flyway migration.
        for(String name:List.of("accepted_reference_insert","accepted_reference_delete","accepted_reference_update",
                "accepted_response_index_insert","accepted_response_index_delete","accepted_response_index_update"))
            sql.execute("DROP TRIGGER "+name);
        sql.execute("DROP PROCEDURE change_accepted_reference_index");
        sql.execute("DROP PROCEDURE reconcile_accepted_reference_index");
        sql.execute("DROP FUNCTION accepted_response_reference_fact");
        sql.execute("ALTER TABLE attempt_trace_stream DROP CHECK chk_trace_reference_index, DROP COLUMN response_reference_index");
        sql.execute("DROP PROCEDURE change_accepted_response_index");
        sql.execute("ALTER TABLE attempt_trace_stream DROP CHECK chk_trace_response_index, DROP COLUMN response_index");
        sql.update("DELETE FROM flyway_schema_history WHERE version IN ('113','114')");
        var duringMigration=new ArrayList<UUID>(); var reachedDefaults=new HashSet<String>();
        try {
            for(int i=0;i<items.size();i++) responses.accept(answer(started.attemptId(),items.get(i),i+1,"index-upgrade-"+i));
            observer().afterSql=(statement,connection)->{
                if((statement.contains("ALTER COLUMN response_index") || statement.contains("ALTER COLUMN response_reference_index"))
                        && statement.contains("SET DEFAULT") && reachedDefaults.add(statement)) {
                    quizId=newQuiz();
                    var concurrent=starts.start(command(prepared(),AnswerOrder.AUTHORED,"index-upgrade-during-"+reachedDefaults.size()));
                    var item=delivery.read(concurrent.attemptId()).form().items().get(0);
                    responses.accept(answer(concurrent.attemptId(),item,1,"index-upgrade-during-answer-"+reachedDefaults.size()));
                    duringMigration.add(concurrent.attemptId());
                }
            };
        } finally {
            try { context.getBean(org.flywaydb.core.Flyway.class).migrate(); }
            finally { observer().reset(); }
        }
        assertThat(reachedDefaults).hasSize(2); assertThat(duringMigration).hasSize(2);
        for(var attempt:duringMigration) assertResponseIndexMatchesSource(attempt);
        assertResponseIndexMatchesSource(started.attemptId());
        var backfilled=tx.execute(s->context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class)
                .references(started.attemptId()));
        assertThat(backfilled).hasSize(5);
        // Adapter-level source maintenance, not a reachable participant edit of immutable evidence.
        String original=sql.queryForObject("SELECT BIN_TO_UUID(id) FROM attempt_accepted_response WHERE attempt_id=UUID_TO_BIN(?) LIMIT 1",String.class,started.attemptId().toString());
        UUID replacement=UUID.randomUUID();
        sql.update("UPDATE attempt_accepted_response SET id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",replacement.toString(),original);
        assertResponseIndexMatchesSource(started.attemptId());
        sql.update("UPDATE attempt_accepted_response SET response_contract_version=2,command_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",UUID.randomUUID().toString(),replacement.toString());
        assertResponseIndexMatchesSource(started.attemptId());
        var changed=tx.execute(s->context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class)
                .references(started.attemptId()));
        assertThat(changed).anySatisfy(reference->assertThat(reference.contract().version()).isEqualTo(2));
        sql.execute("CALL reconcile_accepted_reference_index(FALSE)");
        sql.update("DELETE FROM attempt_accepted_response WHERE id=UUID_TO_BIN(?)",replacement.toString());
        assertResponseIndexMatchesSource(started.attemptId());
        var remaining=tx.execute(s->context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class)
                .references(started.attemptId()));
        assertThat(remaining).hasSize(4);
    }

    private void assertResponseIndexMatchesSource(UUID attempt) throws Exception {
        var expected=sql.queryForList("SELECT BIN_TO_UUID(id) FROM attempt_accepted_response WHERE attempt_id=UUID_TO_BIN(?)",String.class,attempt.toString());
        var index=json.readTree(sql.queryForObject("SELECT response_index FROM attempt_trace_stream WHERE attempt_id=UUID_TO_BIN(?)",String.class,attempt.toString()));
        assertThat(index.path("version").intValue()).isEqualTo(1);
        var actual=new ArrayList<String>(); index.path("ids").fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        var references=json.readTree(sql.queryForObject("SELECT response_reference_index FROM attempt_trace_stream WHERE attempt_id=UUID_TO_BIN(?)",String.class,attempt.toString()));
        var facts=json.createObjectNode();
        sql.query("""
                SELECT BIN_TO_UUID(r.id) id,r.schema_version,BIN_TO_UUID(r.form_id) form_id,
                    BIN_TO_UUID(r.form_item_id) item_id,BIN_TO_UUID(r.question_revision_id) question_id,
                    r.response_contract_id,r.response_contract_version,BIN_TO_UUID(r.command_id) command_id,f.position
                FROM attempt_accepted_response r JOIN attempt_form_item f ON f.form_id=r.form_id AND f.id=r.form_item_id
                WHERE r.attempt_id=UUID_TO_BIN(?)
                """,row->{
            facts.putObject(row.getString("id")).put("schemaVersion",row.getInt("schema_version"))
                    .put("formId",row.getString("form_id")).put("formItemId",row.getString("item_id"))
                    .put("questionRevisionId",row.getString("question_id")).put("responseContractId",row.getString("response_contract_id"))
                    .put("responseContractVersion",row.getInt("response_contract_version")).put("commandId",row.getString("command_id"))
                    .put("position",row.getInt("position"));
        },attempt.toString());
        assertThat(references).isEqualTo(json.createObjectNode().put("version",1).set("responses",facts));
    }

    @Test void corruptReferenceMetadataFailsClosedAndExplicitSourceReconciliationRestoresReplay() throws Exception {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"reference-corrupt-start"));
        var request=batch(started.attemptId(),delivery.read(started.attemptId()).form().items(),1,"reference-corrupt");
        var original=batches.acceptBatch(request);assertResponseIndexMatchesSource(started.attemptId());
        String raw=sql.queryForObject("SELECT response_reference_index FROM attempt_trace_stream WHERE attempt_id=UUID_TO_BIN(?)",String.class,started.attemptId().toString());
        var broken=json.readTree(raw);((ObjectNode)broken.path("responses").elements().next()).put("schemaVersion",2);
        sql.update("UPDATE attempt_trace_stream SET response_reference_index=? WHERE attempt_id=UUID_TO_BIN(?)",broken.toString(),started.attemptId().toString());
        var before=targetSnapshot();
        assertThatThrownBy(()->tx.execute(s->context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class)
                .references(started.attemptId()))).isInstanceOf(ResponseAcceptanceException.class).hasMessage("Response acceptance rejected: CORRUPT").hasNoCause();
        assertThatThrownBy(()->freshBatch().acceptBatch(request)).isInstanceOf(ResponseAcceptanceException.class).hasMessage("Response acceptance rejected: CORRUPT").hasNoCause();
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThatThrownBy(()->sql.execute("CALL reconcile_accepted_reference_index(FALSE)")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        sql.execute("CALL reconcile_accepted_reference_index(TRUE)");sql.execute("CALL reconcile_accepted_reference_index(FALSE)");
        assertResponseIndexMatchesSource(started.attemptId());
        var replay=freshBatch().acceptBatch(request);assertThat(replay.replayed()).isTrue();assertThat(replay.responses()).isEqualTo(original.responses());
        assertThat(count("attempt_accepted_response")).isEqualTo(5);
    }

    private static final class TransactionLogs implements AutoCloseable {
        final ch.qos.logback.classic.Logger root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events=new ch.qos.logback.core.read.ListAppender<>();
        final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> levels=new HashMap<>();
        TransactionLogs() {
            events.start(); root.addAppender(events);
            for(String name:List.of("org.springframework.transaction","org.springframework.orm.jpa.JpaTransactionManager",
                    "uk.gegc.quizmaker.shared.transaction.SafeJpaTransactionManager")) {
                var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(name);
                levels.put(logger,logger.getLevel()); logger.setLevel(ch.qos.logback.classic.Level.TRACE);
            }
        }
        String rendered() {
            return events.list.stream().map(e->e.getFormattedMessage()+"\n"+(e.getThrowableProxy()==null?"":
                    ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(e.getThrowableProxy())))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        @Override public void close() { levels.forEach(ch.qos.logback.classic.Logger::setLevel); root.detachAppender(events); events.stop(); }
    }

    @Test void batchAcceptsFiveRealFamiliesAndReplaysAfterServiceRecreationWithoutMoreWrites() {
        var started = starts.start(command(prepared(), AnswerOrder.SHUFFLED_PER_ATTEMPT, "batch-start"));
        var items = delivery.read(started.attemptId()).form().items();
        var reversed = new ArrayList<>(items); java.util.Collections.reverse(reversed);
        var request = batch(started.attemptId(), reversed, 1, "batch");
        var result = batches.acceptBatch(request);
        assertThat(result.stateVersion()).isEqualTo(6); assertThat(result.replayed()).isFalse();
        assertThat(result.responses()).extracting(AcceptedItem::formItemId)
                .containsExactlyElementsOf(items.stream().map(ParticipantForm.Item::id).toList());
        assertThat(count("attempt_accepted_response")).isEqualTo(5);
        assertThat(count("attempt_response_batch_result")).isEqualTo(1);
        assertThat(count("attempt_command_receipt")).isEqualTo(2);
        assertThat(count("attempt_trace_event")).isEqualTo(7);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM answers", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_submission", Integer.class)).isZero();
        var committed = targetSnapshot(); var replay = freshBatch().acceptBatch(request);
        assertThat(replay.replayed()).isTrue(); assertThat(replay.responses()).isEqualTo(result.responses());
        assertThat(replay.stateVersion()).isEqualTo(6); assertThat(targetSnapshot()).isEqualTo(committed);
        assertThatThrownBy(() -> freshBatch().acceptBatch(batch(started.attemptId(), items, 1, "batch")))
                .isInstanceOfSatisfying(ResponseAcceptanceException.class, e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.CONFLICT));
        assertThat(targetSnapshot()).isEqualTo(committed);
    }

    @ParameterizedTest @ValueSource(strings={"answer", "lifecycle", "trace", "projection", "receipt"})
    void batchRollbackReachesRealWritesAndSameCommandCanSucceed(String checkpoint) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "batch-rollback-start"));
        var request = batch(started.attemptId(), delivery.read(started.attemptId()).form().items(), 1, "batch-rollback");
        var before = targetSnapshot(); var reached = new AtomicBoolean(); var inserts = new AtomicInteger();
        observer().afterSql = (statement, connection) -> {
            boolean selected = switch (checkpoint) {
                case "answer" -> statement.startsWith("INSERT INTO attempt_accepted_response") && inserts.incrementAndGet()==2;
                case "lifecycle" -> statement.stripLeading().startsWith("UPDATE attempt_lifecycle_state");
                case "trace" -> statement.stripLeading().startsWith("INSERT INTO attempt_trace_event");
                case "projection" -> statement.stripLeading().startsWith("INSERT INTO attempt_response_batch_result");
                default -> statement.stripLeading().startsWith("UPDATE attempt_command_receipt");
            };
            if (selected) { reached.set(true); throw new org.springframework.dao.DataAccessResourceFailureException("private-batch-marker"); }
        };
        assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOf(ResponseAcceptanceException.class)
                .hasMessageNotContaining("private-batch-marker");
        observer().reset(); assertThat(reached).isTrue(); assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(freshBatch().acceptBatch(request).responses()).hasSize(5);
    }

    @Test void invalidBatchCollectsErrorsInFormOrderAndDoesNotCommitAnyEarlierValidAnswer() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "batch-validation-start"));
        var items = delivery.read(started.attemptId()).form().items();
        var inputs = new ArrayList<>(batch(started.attemptId(), items, 1, "batch-invalid").responses());
        inputs.set(4, new BatchItem(items.get(4).id(), items.get(4).type(),
                json.createObjectNode().putArray("orderedItemIds").add(UUID.randomUUID().toString()).deepCopy()));
        // Deliberately wrong primitive response shape, still accepted by the internal transport-independent boundary.
        inputs.set(2, new BatchItem(items.get(2).id(), items.get(2).type(),
                json.createObjectNode().put("answer", "private-invalid-answer")));
        java.util.Collections.reverse(inputs); var before = targetSnapshot();
        var request = new BatchCommand(started.attemptId(), 1,
                CommandReceipt.Key.from("batch-invalid"), inputs);
        assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOfSatisfying(
                ResponseBatchException.class, e -> {
                    assertThat(e.reason()).isEqualTo(ResponseBatchException.Reason.INVALID_ITEMS);
                    assertThat(e.errors()).extracting(ResponseBatchException.ItemError::index)
                            .containsExactly(2, 0);
                });
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(batches.acceptBatch(batch(started.attemptId(), items, 1, "batch-invalid")).responses()).hasSize(5);
    }

    @Test void batchDuplicatesUnknownItemsAndDuplicateResponseElementsRejectWithoutMutation() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "batch-duplicates-start"));
        var items = delivery.read(started.attemptId()).form().items();
        var valid = batch(started.attemptId(), items, 1, "batch-duplicates"); var before = targetSnapshot();
        var inputs = new ArrayList<>(valid.responses()); inputs.add(inputs.get(0));
        var duplicateItems = new BatchCommand(
                started.attemptId(), 1, valid.key(), inputs);
        assertThatThrownBy(() -> batches.acceptBatch(duplicateItems)).isInstanceOf(ResponseBatchException.class);
        inputs = new ArrayList<>(valid.responses()); var multi = inputs.get(1); var duplicate = multi.response().deepCopy();
        var selected = (com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("selectedOptionIds"); selected.add(selected.get(0));
        inputs.set(1, new BatchItem(multi.formItemId(), multi.type(), duplicate));
        var duplicateElements = new BatchCommand(
                started.attemptId(), 1, valid.key(), inputs);
        assertThatThrownBy(() -> batches.acceptBatch(duplicateElements)).isInstanceOf(ResponseBatchException.class);
        for (int index : List.of(1,4)) {
            inputs=new ArrayList<>(valid.responses()); var item=inputs.get(index); var aliased=item.response().deepCopy();
            String field=index==1 ? "selectedOptionIds" : "orderedItemIds";
            var ids=(com.fasterxml.jackson.databind.node.ArrayNode)aliased.path(field);
            ids.add(ids.get(0).asText().toUpperCase(java.util.Locale.ROOT));
            inputs.set(index,new BatchItem(item.formItemId(),item.type(),aliased));
            var aliases=new BatchCommand(started.attemptId(),1,valid.key(),inputs);
            assertThatThrownBy(() -> batches.acceptBatch(aliases)).isInstanceOf(ResponseBatchException.class);
            assertThat(targetSnapshot()).isEqualTo(before);
        }
        inputs=new ArrayList<>(valid.responses()); var oversized=multi.response().deepCopy();
        var excessive=(com.fasterxml.jackson.databind.node.ArrayNode)oversized.path("selectedOptionIds"); excessive.removeAll();
        for (int i=0;i<101;i++) excessive.add(new UUID(0,i+1).toString());
        inputs.set(1,new BatchItem(multi.formItemId(),multi.type(),oversized));
        var excessiveElements=new BatchCommand(started.attemptId(),1,valid.key(),inputs);
        assertThatThrownBy(() -> batches.acceptBatch(excessiveElements)).isInstanceOf(ResponseBatchException.class);
        var unknown = new BatchItem(UUID.randomUUID(), QuestionType.TRUE_FALSE,
                json.createObjectNode().put("answer", true));
        assertThatThrownBy(() -> batches.acceptBatch(new BatchCommand(
                started.attemptId(), 1, valid.key(), List.of(unknown)))).isInstanceOf(ResponseBatchException.class);
        assertThat(targetSnapshot()).isEqualTo(before); assertThat(batches.acceptBatch(valid).responses()).hasSize(5);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownBatchCommitReconcilesThroughFreshReceipt(boolean committed) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "batch-unknown-start"));
        var request = batch(started.attemptId(), delivery.read(started.attemptId()).form().items(), 1, "batch-unknown");
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.stripLeading().startsWith("UPDATE attempt_command_receipt") && reached.compareAndSet(false, true)) {
                if (committed) observer().failAfterCommit.set(true); else observer().failBeforeCommit.set(true);
            }
        };
        assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.UNKNOWN_OUTCOME));
        observer().reset(); assertThat(reached).isTrue();
        assertThat(count("attempt_accepted_response")).isEqualTo(committed ? 5 : 0);
        var replay = freshBatch().acceptBatch(request);
        assertThat(replay.replayed()).isEqualTo(committed); assertThat(replay.responses()).hasSize(5);
        assertThat(count("attempt_accepted_response")).isEqualTo(5); assertThat(count("attempt_response_batch_result")).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"2,true", "4,true", "2,false", "4,false"})
    void concurrentBatchesHaveOneDurableWinnerAndNoPartialLoser(int isolation, boolean identical) throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "batch-race-start"));
        var items = delivery.read(started.attemptId()).form().items();
        var request = batch(started.attemptId(), items, 1, "batch-race");
        var other = identical ? request : batch(started.attemptId(), items, 1, "batch-other");
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1); var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<BatchCommand,Object> run = c -> {
                authenticate(username); var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                try { return transaction.execute(s -> { ready.countDown(); await(go); return batches.acceptBatch(c); }); }
                catch (ResponseAcceptanceException e) { return e.reason(); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var one = workers.submit(() -> run.apply(request)); var two = workers.submit(() -> run.apply(other));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            var results = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            var successes = results.stream().filter(BatchResult.class::isInstance)
                    .map(BatchResult.class::cast).toList();
            assertThat(successes.stream().filter(r -> !r.replayed())).hasSize(1);
            assertThat(successes).hasSize(identical ? 2 : 1);
            assertThat(count("attempt_accepted_response")).isEqualTo(5); assertThat(count("attempt_response_batch_result")).isEqualTo(1);
            assertThat(count("attempt_command_receipt")).isEqualTo(2); assertThat(count("attempt_trace_event")).isEqualTo(7);
        } finally { go.countDown(); workers.shutdownNow(); }
    }

    @Test void batchRechecksCurrentPermissionOnReplayAndDoesNotAffectSingleResponseBehavior() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "batch-permission-start"));
        var items = delivery.read(started.attemptId()).form().items(); var request = batch(started.attemptId(), items, 1, "batch-permission");
        SecurityContextHolder.clearContext(); assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOf(ResponseAcceptanceException.class);
        authenticate(username); revoke(ownerId,"ATTEMPT_CREATE"); var before=targetSnapshot();
        assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.NOT_FOUND));
        assertThat(targetSnapshot()).isEqualTo(before); grant(ownerId,"ATTEMPT_CREATE");
        assertThat(batches.acceptBatch(request).responses()).hasSize(5); var committed=targetSnapshot();
        revoke(ownerId,"ATTEMPT_CREATE"); assertThatThrownBy(() -> freshBatch().acceptBatch(request)).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(targetSnapshot()).isEqualTo(committed); grant(ownerId,"ATTEMPT_CREATE");
        assertThat(freshBatch().acceptBatch(request).replayed()).isTrue();
        rejected(answer(started.attemptId(), items.get(0), 6, "single-after-batch"),"CONFLICT");
        assertThat(targetSnapshot()).isEqualTo(committed);
    }

    @Test void batchSourceRelationshipReadsStayBoundedAcrossThreeAttemptsAndDifferentBatchSizes(org.junit.jupiter.api.TestReporter reporter) {
        var revision=publish(0, java.util.stream.IntStream.range(0,20).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList()); binding.bind(quizId,revision);
        var measured = new ArrayList<Long>();
        for (int size : List.of(1,20)) {
            var startsForSize = new ArrayList<AttemptStartService.Result>();
            for (int p=0;p<3;p++) { if(p>0) quizId=newQuiz();
                var published=p==0 ? revision : publish(0,java.util.stream.IntStream.range(0,20).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList());
                if(p>0) binding.bind(quizId,published);
                startsForSize.add(starts.start(command(published,AnswerOrder.AUTHORED,"batch-count-"+size+"-"+p)));
            }
            var requests=startsForSize.stream().map(start -> batch(start.attemptId(),delivery.read(start.attemptId()).form().items().subList(0,size),1,"batch-read-"+start.attemptId())).toList();
            observer().reset(); requests.forEach(batches::acceptBatch);
            measured.add(observer().selects.stream().filter(q -> q.contains("quiz_revision") || q.contains("question_revision") || q.contains("attempt_form")).count());
            // Use a fresh quiz for the next set; never rebuild an active form or fabricate another learner's authority.
            quizId=newQuiz(); revision=publish(0,java.util.stream.IntStream.range(0,20).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList()); binding.bind(quizId,revision);
        }
        assertThat(measured.get(0)).isPositive(); assertThat(measured.get(1)).isEqualTo(measured.get(0));
        reporter.publishEntry("batch-source-selects-three-parents-1-vs-20",measured.toString());
        System.out.println("batch-source-selects-three-parents-1-vs-20="+measured);
    }

    @Test void maximum500ItemBatchCommitsAndDoesNotCreateAssessmentResults() {
        var revision=publish(0,java.util.stream.IntStream.range(0,500).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList());
        binding.bind(quizId,revision); var started=starts.start(command(revision,AnswerOrder.AUTHORED,"maximum-batch-start"));
        var request=batch(started.attemptId(),delivery.read(started.attemptId()).form().items(),1,"maximum-batch");
        var result=batches.acceptBatch(request); assertThat(result.responses()).hasSize(500); assertThat(result.stateVersion()).isEqualTo(501);
        assertThat(count("attempt_accepted_response")).isEqualTo(500); assertThat(count("attempt_trace_event")).isEqualTo(502);
        assertThat(count("attempt_submission")).isZero();
        assertThat(freshBatch().acceptBatch(request).responses()).isEqualTo(result.responses());
    }

    @Test void foreignUserCannotWriteOrReplayPrivateBatchAndOwnUserStillSucceeds() {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"foreign-batch-start"));
        var request=batch(started.attemptId(),delivery.read(started.attemptId()).form().items(),1,"foreign-batch");
        String stranger="batch-stranger-"+UUID.randomUUID().toString().substring(0,10); UUID other=user(stranger,false);
        grant(other,"ATTEMPT_CREATE","ATTEMPT_READ"); authenticate(stranger); var before=targetSnapshot();
        assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.NOT_FOUND));
        assertThat(targetSnapshot()).isEqualTo(before); authenticate(username);
        assertThat(batches.acceptBatch(request).responses()).hasSize(5); authenticate(stranger); var committed=targetSnapshot();
        assertThatThrownBy(() -> freshBatch().acceptBatch(request)).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(targetSnapshot()).isEqualTo(committed); authenticate(username); assertThat(freshBatch().acceptBatch(request).replayed()).isTrue();
    }

    @Test void corruptBatchProjectionFailsClosedAndRestoredAcknowledgementReplaysWithoutNewAcceptance() throws Exception {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"corrupt-batch-start"));
        var request=batch(started.attemptId(),delivery.read(started.attemptId()).form().items(),1,"corrupt-batch");
        var result=batches.acceptBatch(request); String original=sql.queryForObject("SELECT items_json FROM attempt_response_batch_result",String.class);
        var tree=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(original);
        ((ObjectNode)tree.get(0)).put("acceptedResponseId",UUID.randomUUID().toString());
        sql.update("UPDATE attempt_response_batch_result SET items_json=?",tree.toString()); var corrupt=targetSnapshot();
        assertThatThrownBy(() -> freshBatch().acceptBatch(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.CORRUPT));
        assertThat(targetSnapshot()).isEqualTo(corrupt);
        sql.update("UPDATE attempt_response_batch_result SET items_json=?",original);
        assertThat(freshBatch().acceptBatch(request).responses()).isEqualTo(result.responses());
        assertThat(count("attempt_accepted_response")).isEqualTo(5);
    }

    @Test void batchReceiptCleanupRemovesProjectionAndRetainsAllAcceptedEvidence() {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"expired-batch-start"));
        var request=batch(started.attemptId(),delivery.read(started.attemptId()).form().items(),1,"expired-batch");
        batches.acceptBatch(request);
        tx.executeWithoutResult(s -> lifecycle.save(started.attemptId(),6,
                new AttemptLifecycle.State(1,AttemptLifecycle.Execution.SUBMITTED,AttemptLifecycle.AnswerCollection.COMPLETE)));
        var before=targetSnapshot(); var clock=(AdjustableClock)context.getBean(Clock.class);
        clock.now=clock.now.plus(Duration.ofDays(31));
        assertThatThrownBy(() -> freshBatch().acceptBatch(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.EXPIRED));
        assertThat(targetSnapshot()).isEqualTo(before);
        Integer purged=tx.execute(s -> context.getBean(CommandReceiptService.class).purgeExpired(started.attemptId(),7,100));
        assertThat(purged).isEqualTo(2);
        assertThat(count("attempt_response_batch_result")).isZero();
        assertThat(count("attempt_command_receipt")).isZero();
        var after=targetSnapshot();
        for (String table : List.of("attempt_accepted_response","attempt_trace_event","attempt_lifecycle_state"))
            assertThat(after.get(table)).isEqualTo(before.get(table));
        assertThat(count("attempt_accepted_response")).isEqualTo(5);
    }

    @Test void batchUsesExistingServerReceiptTimeBoundaryAndReplayAfterDeadline() {
        var started=starts.start(timed(command(prepared(),AnswerOrder.AUTHORED,"timed-batch-start")));
        var request=batch(started.attemptId(),delivery.read(started.attemptId()).form().items(),1,"timed-batch");
        var clock=(AdjustableClock)context.getBean(Clock.class); Instant deadline=clock.now.plusSeconds(1800); var before=targetSnapshot();
        clock.now=deadline; assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.INACTIVE));
        assertThat(targetSnapshot()).isEqualTo(before); clock.now=deadline.minusSeconds(1); var reached=new AtomicBoolean();
        observer().beforeSql=(statement,connection) -> {
            if(statement.contains("FROM attempt_lifecycle_state") && statement.contains("FOR UPDATE")) { reached.set(true); clock.now=deadline.plusSeconds(1); }
        };
        assertThat(batches.acceptBatch(request).responses()).hasSize(5); observer().reset(); assertThat(reached).isTrue();
        assertThat(freshBatch().acceptBatch(request).replayed()).isTrue();
    }

    @Test void unknownSavedBatchContractRejectsBeforeWritesAndSameCommandWorksAfterRestoration() {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"unknown-batch-start"));
        var items=delivery.read(started.attemptId()).form().items(); var request=batch(started.attemptId(),items,1,"unknown-batch");
        futureResponseContract(items.get(2).id(),2); var before=targetSnapshot();
        try {
            assertThatThrownBy(() -> batches.acceptBatch(request)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                    e -> assertThat(e.reason()).isEqualTo(ResponseAcceptanceException.Reason.UNSUPPORTED));
            assertThat(targetSnapshot()).isEqualTo(before);
        } finally { futureResponseContract(items.get(2).id(),1); }
        assertThat(batches.acceptBatch(request).responses()).hasSize(5);
    }

    @Test void batchTextLimitCountsUtf8BytesAndPreservesStricterFamilyValidation() {
        var started=starts.start(command(prepared(),AnswerOrder.AUTHORED,"text-batch-start"));
        var items=delivery.read(started.attemptId()).form().items(); var request=batch(started.attemptId(),items,1,"text-batch");
        var payload=stableInput(items.get(3)); var text=(ObjectNode)payload.path("answers").get(0);
        // Both boundary values contain 10,000 code points and stay below the frozen
        // contract's serialized-JSON limit, isolating the batch's UTF-8 text limit.
        String boundary="😀".repeat(2768)+"界".repeat(7232);
        String oversized="😀".repeat(2769)+"界".repeat(7231);
        assertThat(boundary.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(32768);
        assertThat(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(32769);
        text.put("answer",oversized); var invalid=new ArrayList<>(request.responses());
        invalid.set(3,new BatchItem(items.get(3).id(),QuestionType.FILL_GAP,payload)); var before=targetSnapshot();
        assertThatThrownBy(() -> batches.acceptBatch(new BatchCommand(started.attemptId(),1,request.key(),invalid)))
                .isInstanceOf(ResponseBatchException.class);
        assertThat(targetSnapshot()).isEqualTo(before);
        text.put("answer","a".repeat(10001));
        assertThatThrownBy(() -> batches.acceptBatch(new BatchCommand(started.attemptId(),1,request.key(),invalid)))
                .isInstanceOf(ResponseBatchException.class);
        assertThat(targetSnapshot()).isEqualTo(before);
        text.put("answer",boundary);
        assertThat(batches.acceptBatch(new BatchCommand(started.attemptId(),1,request.key(),invalid)).responses()).hasSize(5);
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
            String role = "acceptance-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            for (String name : names) assertThat(sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, name)).isEqualTo(1);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", userId.toString(), role);
        });
    }

}
