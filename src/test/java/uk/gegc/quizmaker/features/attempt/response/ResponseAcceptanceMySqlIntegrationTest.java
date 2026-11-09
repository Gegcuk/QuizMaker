package uk.gegc.quizmaker.features.attempt.response;

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
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        ResponseAcceptanceMySqlIntegrationTest.Config.class})
class ResponseAcceptanceMySqlIntegrationTest {
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
    static final List<String> TARGET_TABLES = List.of("attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form", "attempt_policy_snapshot");
    AcceptanceDataSource observer() { return (AcceptanceDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
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

    @ParameterizedTest @ValueSource(ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ})
    void realDeadlockInsideQuestionReadIsRetryableAndSameCommandRecovers(int isolation) throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "start"));
        var item = delivery.read(started.attemptId()).form().items().get(2);
        var request = answer(started.attemptId(), item, 1, "answer");
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
            assertThatThrownBy(() -> commandTransaction.execute(s -> responses.accept(request)))
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
            var accepted = recreated().accept(request);
            assertThat(accepted.replayed()).isFalse();
            assertThat(recreated().accept(request).acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
            assertThat(count("attempt_accepted_response")).isEqualTo(1);
            assertThat(count("attempt_trace_event")).isEqualTo(3);
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
        var two = starts.start(command(revision, AnswerOrder.AUTHORED, "second-start"));
        var other = delivery.read(two.attemptId()).form().items().get(2);
        var own = delivery.read(one.attemptId()).form().items().get(2);
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
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timeout"); }
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
