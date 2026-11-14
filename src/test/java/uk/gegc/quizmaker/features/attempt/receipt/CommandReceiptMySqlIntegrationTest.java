package uk.gegc.quizmaker.features.attempt.receipt;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.transaction.*;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.repository.CommandReceiptRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.receipt.CommandReceiptTest.*;

/** Real receipt, subject and lifecycle writes. Does not claim #600 start or #543 answer integration. */
@Tag("db-serial")
class CommandReceiptMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00.123456Z");
    private static final AttemptLifecycle.State STARTED = new AttemptLifecycle.State(1,
            AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.NOT_STARTED);
    private static final AttemptLifecycle.State TERMINAL = new AttemptLifecycle.State(1,
            AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE);
    private static JdbcTemplate admin;
    private static String catalog;
    private static ReceiptDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager manager;
    private static Map<String, Object> legacyDefinition;
    private SimpleMeterRegistry metrics;
    private CommandReceiptService service;
    private CommandReceiptRepository repository;
    private AttemptLifecycleService lifecycle;
    private AttemptSubjectService subjects;
    private UUID user;

    @BeforeAll
    static void migrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String username = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, username, password));
        String name = "quizmaker_599_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        catalog = name;
        source = new ReceiptDataSource(new DriverManagerDataSource(base + name + options, username, password));
        sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("90").load().migrate();
        legacyDefinition = sql.queryForMap("SHOW CREATE TABLE attempts");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("91").load().migrate();
    }

    @BeforeEach
    void prepare() {
        source.reset();
        sql.update("DELETE FROM attempt_command_receipt");
        sql.update("DELETE FROM attempt_participant_subject");
        sql.update("DELETE FROM attempt_lifecycle_state");
        metrics = new SimpleMeterRegistry();
        repository = proxy(new JdbcCommandReceiptRepository(named()), manager);
        service = service(manager, NOW);
        lifecycle = lifecycle(manager, NOW);
        subjects = new AttemptSubjectServiceImpl(proxy(new JdbcAttemptSubjectRepository(named()), manager), manager, metrics);
        user = user(); source.reset();
    }

    @AfterEach void reset() { source.reset(); metrics.close(); }
    @AfterAll static void dropOnlyOwnedCatalog() {
        if (source != null) source.reset();
        if (catalog != null) admin.execute("DROP DATABASE " + catalog);
    }

    @ParameterizedTest @ValueSource(strings = {"authorization", "work", "commit"})
    void transactionViolationIsBoundedThroughReceiptButCompletionStillHasUnknownOutcome(String phase) throws Exception {
        var failure = new AttemptTransactionException(AttemptTransactionException.Reason.INCOMPATIBLE_ISOLATION);
        failure.addSuppressed(new IllegalStateException("private-marker"));
        var reached = new AtomicBoolean(); var request = request("violation");
        if (phase.equals("commit")) source.afterCommit = c -> { reached.set(true); throw failure; };
        var observed = catchThrowable(() -> service.execute(request, () -> {
            if (phase.equals("authorization")) { reached.set(true); throw failure; }
        }, id -> {
            var result = start(user);
            if (phase.equals("work")) { reached.set(true); throw failure; }
            return result;
        }));
        source.reset(); assertThat(reached).isTrue();
        if (phase.equals("commit")) {
            assertThat(observed).isInstanceOfSatisfying(ReceiptException.class, e -> assertThat(e.reason()).isEqualTo(UNKNOWN_OUTCOME));
        } else assertThat(observed).isInstanceOfSatisfying(AttemptTransactionException.class,
                e -> assertThat(e.reason()).isEqualTo(AttemptTransactionException.Reason.INCOMPATIBLE_ISOLATION));
        assertThat(observed).hasNoCause(); assertThat(observed.getSuppressed()).isEmpty();
        assertThat(count("attempt_command_receipt")).isEqualTo(phase.equals("commit") ? 1 : 0);
        assertThat(count("attempt_lifecycle_state")).isEqualTo(phase.equals("commit") ? 1 : 0);
        assertThat(service.execute(request, () -> {}, id -> start(user)).replayed()).isEqualTo(phase.equals("commit"));
    }

    @ParameterizedTest @ValueSource(strings = {"TRANSACTION_REPEATABLE_READ", "TRANSACTION_READ_COMMITTED"})
    void pooledConnectionIsolationCommandsAreMeasuredOnTheServerAndRestored(String defaultIsolation) throws Exception {
        var config = new com.zaxxer.hikari.HikariConfig(); config.setDataSource(source.getTargetDataSource());
        config.setMaximumPoolSize(1); config.setMinimumIdle(1); config.setTransactionIsolation(defaultIsolation);
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            var transactionManager = new DataSourceTransactionManager(pool); var jdbc = new JdbcTemplate(pool);
            long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            long thread = admin.queryForObject("SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=?", Long.class, connectionId);
            var normal = new TransactionTemplate(transactionManager);
            var rc = AttemptTransactions.readCommitted(transactionManager);
            var baseline = serverStatements(thread, () -> normal.executeWithoutResult(status -> jdbc.queryForObject("SELECT 905", Integer.class)));
            var owned = serverStatements(thread, () -> rc.executeWithoutResult(status -> jdbc.queryForObject("SELECT 905", Integer.class)));
            assertThat(owned).contains("COMMIT", "SELECT 905");
            var isolationStatements = owned.stream().filter(q -> q.contains("transaction_isolation") || q.contains("ISOLATION LEVEL")).toList();
            boolean rr = defaultIsolation.equals("TRANSACTION_REPEATABLE_READ");
            if (rr) assertThat(isolationStatements).containsExactly("SELECT @@session.transaction_isolation",
                    "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED", "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            else assertThat(isolationStatements).containsExactly("SELECT @@session.transaction_isolation");
            assertThat(owned.size() - baseline.size()).isEqualTo(rr ? 3 : 1);
            var validated = serverStatements(thread, () -> normal.executeWithoutResult(status -> {
                if (rr) {
                    assertThatThrownBy(() -> AttemptTransactions.requireCompatible(transactionManager, false))
                            .isInstanceOf(AttemptTransactionException.class);
                } else for (int i = 0; i < 500; i++) AttemptTransactions.requireCompatible(transactionManager, true);
            }));
            assertThat(validated.stream().filter(q -> q.contains("transaction_isolation"))).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connectionId);
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                    .isEqualTo(rr ? "REPEATABLE-READ" : "READ-COMMITTED");
            normal.executeWithoutResult(status -> assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                    .isEqualTo(rr ? "REPEATABLE-READ" : "READ-COMMITTED"));
            System.out.println("905 server isolation commands " + defaultIsolation + ": baseline=" + baseline + "; owned=" + owned + "; participating=" + validated);
        }
    }

    private static List<String> serverStatements(long thread, Runnable action) {
        Long last = admin.queryForObject("SELECT COALESCE(MAX(EVENT_ID),0) FROM performance_schema.events_statements_history WHERE THREAD_ID=?", Long.class, thread);
        action.run();
        var statements = admin.queryForList("SELECT SQL_TEXT FROM performance_schema.events_statements_history WHERE THREAD_ID=? AND EVENT_ID>? AND SQL_TEXT IS NOT NULL ORDER BY EVENT_ID", String.class, thread, last);
        assertThat(statements).isNotEmpty().hasSizeLessThan(10); // Stay below MySQL's per-thread history capacity.
        return statements;
    }

    @Test void defaultRcCallerParticipatesAndStandaloneRestoresConfiguredPoolDefault() throws Exception {
        var request = request("default-rc");
        var original = source.getTargetDataSource();
        String serverDefault = admin.queryForObject("SELECT @@global.transaction_isolation", String.class);
        var config = new com.zaxxer.hikari.HikariConfig(); config.setDataSource(original);
        config.setMaximumPoolSize(1); config.setMinimumIdle(1); config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            // Only this suite's DataSource/connection default changes, never the worker's server.
            source.setTargetDataSource(pool);
            try {
                var caller = new TransactionTemplate(manager);
                caller.executeWithoutResult(status -> {
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
                    assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
                    source.isolationReads.set(0);
                    assertThat(service.execute(request, () -> {}, id -> start(user)).replayed()).isFalse();
                    assertThat(service.execute(request, () -> {}, id -> fail("Replay executed work")).replayed()).isTrue();
                    assertThat(source.isolationReads.get()).isEqualTo(1);
                    status.setRollbackOnly();
                });
                assertThat(count("attempt_command_receipt")).isZero(); assertThat(count("attempt_lifecycle_state")).isZero();
                assertThat(service.execute(request, () -> {}, id -> start(user)).replayed()).isFalse();
                assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
            } finally { source.setTargetDataSource(original); }
        }
        assertThat(admin.queryForObject("SELECT @@global.transaction_isolation", String.class)).isEqualTo(serverDefault);
    }

    @Test void defaultRcProofIsSuspendedWithItsConnectionAndClearedAfterCompletion() {
        var config = new com.zaxxer.hikari.HikariConfig(); config.setDataSource(source.getTargetDataSource());
        config.setMaximumPoolSize(2); config.setMinimumIdle(2); config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            var measured = new ReceiptDataSource(pool); var tm = new DataSourceTransactionManager(measured);
            var outer = new TransactionTemplate(tm); var child = new TransactionTemplate(tm);
            child.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            outer.executeWithoutResult(status -> {
                AttemptTransactions.requireCompatible(tm, true); assertThat(measured.isolationReads.get()).isEqualTo(1);
                child.executeWithoutResult(inner -> {
                    AttemptTransactions.requireCompatible(tm, true); assertThat(measured.isolationReads.get()).isEqualTo(2);
                });
                AttemptTransactions.requireCompatible(tm, true); assertThat(measured.isolationReads.get()).isEqualTo(2);
                status.setRollbackOnly();
            });
            outer.executeWithoutResult(status -> AttemptTransactions.requireCompatible(tm, false));
            assertThat(measured.isolationReads.get()).isEqualTo(3);
        }
    }

    @Test
    void migrationIsAdditiveHasBoundedColumnsAndDoesNotCascadeIntoDomainFacts() {
        assertThat(sql.queryForMap("SHOW CREATE TABLE attempts")).isEqualTo(legacyDefinition);
        assertThat(count("attempt_command_receipt")).isZero();
        assertThat(sql.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attempt_command_receipt' ORDER BY ORDINAL_POSITION", String.class))
                .containsExactly("id", "schema_version", "user_id", "key_hash", "context_id", "operation", "target_id",
                        "expected_version", "input_hash", "status", "recorded_at", "acknowledgement_version", "attempt_id", "result_id", "state_version");
        assertThat(sql.queryForList("SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='attempt_command_receipt'", String.class))
                .hasSize(2).doesNotContain("CASCADE");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attempt_command_receipt' AND CONSTRAINT_TYPE='CHECK' AND ENFORCED='YES'", Integer.class)).isEqualTo(4);
    }

    @Test
    void realEffectAndReceiptCommitTogetherAndOriginalAcknowledgementSurvivesRestartAndLaterState() throws Exception {
        var request = request("restart"); var calls = new AtomicInteger(); var access = new AtomicInteger();
        var first = service.execute(request, access::incrementAndGet, id -> { calls.incrementAndGet(); return start(user); });
        assertThat(first.replayed()).isFalse();
        var attempt = first.receipt().acknowledgement().attemptId();
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
        assertThat(count("attempt_command_receipt")).isEqualTo(1);
        lifecycle.save(attempt, 1, TERMINAL);
        var restarted = service(manager, NOW.plusSeconds(1));
        var replay = restarted.execute(request, access::incrementAndGet, id -> { calls.incrementAndGet(); return start(user); });
        assertThat(replay.replayed()).isTrue(); assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(calls).hasValue(1); assertThat(access).hasValue(2);
        assertThat(lifecycle.read(new LifecycleReference.Target(attempt))).extracting("rowVersion").isEqualTo(2L);
    }

    @Test
    void allSemanticFieldsAndScopeConflictsAreCheckedWithoutExecutingWork() throws Exception {
        var original = request("scope"); execute(original);
        var variants = List.of(
                new Request(1, user, UUID.randomUUID(), original.operation(), original.targetId(), 0, original.key(), original.input()),
                new Request(1, user, original.contextId(), AttemptCommands.Type.SUBMIT_ATTEMPT, original.targetId(), 0, original.key(), original.input()),
                new Request(1, user, original.contextId(), original.operation(), UUID.randomUUID(), 0, original.key(), original.input()),
                new Request(1, user, original.contextId(), original.operation(), original.targetId(), 1, original.key(), original.input()),
                new Request(1, user, original.contextId(), original.operation(), original.targetId(), 0, original.key(), fingerprint("{\"answer\":false}")));
        for (var changed : variants) rejected(() -> service.execute(changed, () -> {}, id -> fail("Conflicting command executed")), CONFLICT);
        assertThat(count("attempt_command_receipt")).isEqualTo(1); assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
        UUID other = user();
        var independent = new Request(1, other, original.contextId(), original.operation(), original.targetId(), 0, original.key(), original.input());
        assertThat(service.execute(independent, () -> {}, id -> start(other)).replayed()).isFalse();
        assertThat(count("attempt_command_receipt")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unauthenticated", "permission", "wrong-owner", "wrong-organization", "private", "revoked"})
    void currentConsumerAccessGuardPrecedesReplayAndProtectedLookup(String denial) throws Exception {
        var request = request("access"); execute(request); source.reset();
        rejected(() -> service.execute(request, () -> { throw new IllegalStateException(denial); }, id -> fail("Denied command executed")), ACCESS_DENIED);
        assertThat(source.selects).isEmpty();
        assertThat(count("attempt_command_receipt")).isEqualTo(1);
        // Same denial before any first receipt exists. The labels model consumer guards, not #537 implementation.
        rejected(() -> service.execute(request("new-denied"), () -> { throw new IllegalStateException(denial); }, id -> start(user)), ACCESS_DENIED);
        assertThat(count("attempt_command_receipt")).isEqualTo(1);
    }

    @Test
    void foreignOrMissingAcknowledgementCannotCommitSuccess() throws Exception {
        UUID other = user(); var foreign = start(other); var request = request("foreign");
        rejected(() -> service.execute(request, () -> {}, id -> foreign), CONTEXT_UNAVAILABLE);
        rejected(() -> service.execute(request, () -> {}, id -> new Acknowledgement(1, UUID.randomUUID(), UUID.randomUUID(), 1)), CONTEXT_UNAVAILABLE);
        rejected(() -> service.execute(request, () -> {}, id -> null), COMMAND_DENIED);
        rejected(() -> service.execute(request, () -> {}, id -> new Acknowledgement(1, foreign.attemptId(), foreign.resultId(), 500)), CONTEXT_UNAVAILABLE);
        assertThat(count("attempt_command_receipt")).isZero();
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT INTO attempt_command_receipt", "UPDATE attempt_lifecycle_state", "UPDATE attempt_command_receipt SET"})
    void actualWriteFailureMarksCaughtErrorRollbackOnlyAndLeavesNoPartialEffects(String checkpoint) throws Exception {
        var request = request("fault"); var hit = new AtomicBoolean();
        source.afterSql = (statement, connection) -> {
            if (statement.contains(checkpoint) && hit.compareAndSet(false, true)) throw new SQLException("Synthetic failure", "08006");
        };
        assertThatThrownBy(() -> tx("READ_COMMITTED").execute(status -> {
            rejected(() -> execute(request), checkpoint.contains("lifecycle") ? COMMAND_DENIED : PERSISTENCE_UNAVAILABLE);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        source.reset(); assertThat(hit).isTrue();
        assertThat(count("attempt_command_receipt")).isZero(); assertThat(count("attempt_lifecycle_state")).isZero();
        assertThat(count("attempt_participant_subject")).isZero();
        assertThat(execute(request).replayed()).isFalse();
    }

    @Test
    void outerRollbackAndReentrantExecutionCannotLeaveCommittedReceipts() throws Exception {
        var request = request("outer");
        tx("READ_COMMITTED").executeWithoutResult(status -> { execute(request); status.setRollbackOnly(); });
        assertThat(count("attempt_command_receipt")).isZero(); assertThat(count("attempt_lifecycle_state")).isZero();
        rejected(() -> service.execute(request, () -> {}, id -> {
            service.execute(request, () -> {}, nested -> start(user));
            return start(user);
        }), IN_PROGRESS);
        assertThat(count("attempt_command_receipt")).isZero();
        assertThat(execute(request).replayed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lostCommitAcknowledgementReconcilesWithoutRepeatingACommittedEffect(boolean committed) throws Exception {
        var request = request("uncertain"); var hit = new AtomicBoolean(); var calls = new AtomicInteger();
        if (committed) source.afterCommit = connection -> { hit.set(true); throw new SQLException("Lost acknowledgement", "08006"); };
        else source.beforeCommit = connection -> { connection.rollback(); hit.set(true); throw new SQLException("Commit rejected", "08006"); };
        rejected(() -> service.execute(request, () -> {}, id -> { calls.incrementAndGet(); return start(user); }), UNKNOWN_OUTCOME);
        source.reset(); assertThat(hit).isTrue();
        assertThat(count("attempt_command_receipt")).isEqualTo(committed ? 1 : 0);
        assertThat(count("attempt_lifecycle_state")).isEqualTo(committed ? 1 : 0);
        var replay = service(manager, NOW.plusSeconds(10)).execute(request, () -> {}, id -> { calls.incrementAndGet(); return start(user); });
        assertThat(replay.replayed()).isEqualTo(committed); assertThat(calls).hasValue(committed ? 1 : 2);
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
        assertThat(replay.receipt().recordedAt()).isEqualTo(committed ? NOW : NOW.plusSeconds(10));
    }

    @ParameterizedTest
    @CsvSource({"authorization,1213", "authorization,1205", "work,1213", "work,1205"})
    void sanitizedDependencyConflictsKeepTheirReasonAndAllowExplicitRetry(String phase, int code) throws Exception {
        var request = request("nested-conflict");
        var failure = DatabaseConflictException.from(new SQLException("Private driver diagnostic", "HY000", code)).orElseThrow();
        var access = new AtomicInteger(); var calls = new AtomicInteger();
        // The dependency's public signal is already sanitized; receipt must not need its SQL cause.
        rejected(() -> service.execute(request, () -> {
            access.incrementAndGet();
            if (phase.equals("authorization")) throw failure;
        }, id -> {
            calls.incrementAndGet(); start(user); throw failure;
        }), code == 1213 ? RETRYABLE : IN_PROGRESS);
        assertThat(access).hasValue(1);
        assertThat(calls).hasValue(phase.equals("authorization") ? 0 : 1);
        assertThat(count("attempt_command_receipt")).isZero();
        assertThat(count("attempt_lifecycle_state")).isZero();
        assertThat(count("attempt_participant_subject")).isZero();
        assertThat(execute(request).replayed()).isFalse();
        assertThat(count("attempt_command_receipt")).isEqualTo(1);
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unexpected", "known", "authorization"})
    void sensitiveCallbackDiagnosticsNeverReachCallerOrTransactionRollbackLog(String kind) throws Exception {
        String secret = "SYNTHETIC_PRIVATE_ANSWER_599";
        var logger = (Logger) LoggerFactory.getLogger(TransactionTemplate.class);
        Level previous = logger.getLevel(); var captured = new ListAppender<ILoggingEvent>(); captured.start();
        logger.addAppender(captured); logger.setLevel(Level.DEBUG);
        try {
            RuntimeException failure = kind.equals("known") ? new ReceiptException(STALE_VERSION) : new IllegalStateException(secret);
            if (kind.equals("known")) failure.initCause(new IllegalStateException(secret));
            failure.addSuppressed(new IllegalArgumentException(secret));
            var request = request("redaction");
            var reason = kind.equals("authorization") ? ACCESS_DENIED : kind.equals("known") ? STALE_VERSION : COMMAND_DENIED;
            rejected(() -> service.execute(request, () -> { if (kind.equals("authorization")) throw failure; }, id -> {
                start(user); throw failure;
            }), reason);
            assertThat(captured.list).isNotEmpty();
            for (var event : captured.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(secret);
                if (event.getThrowableProxy() != null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(secret);
            }
            assertThat(count("attempt_command_receipt")).isZero(); assertThat(count("attempt_lifecycle_state")).isZero();
        } finally { logger.detachAppender(captured); captured.stop(); logger.setLevel(previous); }
    }

    @ParameterizedTest
    @CsvSource({"beforeCommit,unexpected,false", "beforeCommit,known,false", "afterCommit,unexpected,false",
            "afterCommit,known,false", "afterCommit,connection,false", "beforeCommit,unexpected,true",
            "beforeCommit,known,true", "afterCommit,unexpected,true", "afterCommit,known,true",
            "beforeCommit,deadlock,false", "afterCommit,deadlock,false",
            "beforeCommit,timeout,false", "afterCommit,timeout,false"})
    void completionFailuresAreRedactedBeforeSpringLoggingAndReconcileWithTheSameKey(String phase, String kind, boolean joined) throws Exception {
        String marker = "SYNTHETIC_PRIVATE_COMPLETION_599";
        var logger = (Logger) LoggerFactory.getLogger(DataSourceTransactionManager.class);
        Level previous = logger.getLevel(); var captured = new ListAppender<ILoggingEvent>(); captured.start();
        logger.addAppender(captured); logger.setLevel(Level.DEBUG);
        try {
            RuntimeException failure = switch (kind) {
                case "known" -> new ReceiptException(COMMAND_DENIED);
                case "connection" -> new CannotCreateTransactionException(marker);
                case "deadlock", "timeout" -> DatabaseConflictException.from(new SQLException(marker,
                        "HY000", kind.equals("deadlock") ? 1213 : 1205)).orElseThrow();
                default -> new IllegalStateException(marker);
            };
            if (!(failure instanceof DatabaseConflictException)) failure.initCause(new IllegalArgumentException(marker));
            failure.addSuppressed(new IllegalArgumentException(marker));
            var request = request("completion"); var calls = new AtomicInteger();
            var acknowledgement = new AtomicReference<Acknowledgement>();
            java.util.function.Supplier<Result> command = () -> service.execute(request, () -> {}, id -> {
                calls.incrementAndGet(); acknowledgement.set(start(user));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { if (phase.equals("beforeCommit")) throw failure; }
                    // Observer failure only: no writes or external effects after commit.
                    @Override public void afterCommit() { if (phase.equals("afterCommit")) throw failure; }
                });
                return acknowledgement.get();
            });
            Throwable observed = catchThrowable(() -> {
                if (joined) tx("READ_COMMITTED").execute(status -> command.get());
                else command.get();
            });
            boolean committed = phase.equals("afterCommit");
            assertThat(count("attempt_command_receipt")).isEqualTo(committed ? 1 : 0);
            assertThat(count("attempt_lifecycle_state")).isEqualTo(committed ? 1 : 0);
            assertThat(count("attempt_participant_subject")).isEqualTo(committed ? 1 : 0);
            // A participating service returns before the outer owner completes its transaction.
            assertThat(metrics.get("attempt.command.receipt").tag("outcome", joined ? "returned" : "unknown_outcome").timer().count()).isEqualTo(1);
            var replay = service.execute(request, () -> {}, id -> { calls.incrementAndGet(); return start(user); });
            assertThat(replay.replayed()).isEqualTo(committed);
            assertThat(calls).hasValue(committed ? 1 : 2);
            if (committed) assertThat(replay.receipt().acknowledgement()).isEqualTo(acknowledgement.get());
            assertThat(observed).isInstanceOfSatisfying(ReceiptException.class, clean -> {
                assertThat(clean.reason()).isEqualTo(UNKNOWN_OUTCOME);
                assertThat(clean.getMessage()).doesNotContain(marker);
                assertThat(clean.getCause()).isNull(); assertThat(clean.getSuppressed()).isEmpty();
            });
            assertThat(captured.list).isNotEmpty();
            if (!committed) assertThat(captured.list).anyMatch(event -> event.getThrowableProxy() != null);
            for (var event : captured.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(marker);
                if (event.getThrowableProxy() != null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(marker);
            }
        } finally { logger.detachAppender(captured); captured.stop(); logger.setLevel(previous); }
    }

    @ParameterizedTest
    @CsvSource({"beforeCompletion,false", "afterCompletion,false", "beforeCompletion,true", "afterCompletion,true"})
    void cleanupFailuresAreRedactedEvenWhenSpringOnlyLogsThem(String phase, boolean rollback) throws Exception {
        String marker = "SYNTHETIC_PRIVATE_CLEANUP_599";
        var logger = (Logger) LoggerFactory.getLogger(TransactionSynchronizationUtils.class);
        Level previous = logger.getLevel(); var captured = new ListAppender<ILoggingEvent>(); captured.start();
        logger.addAppender(captured); logger.setLevel(Level.ERROR);
        try {
            var failure = new IllegalStateException(marker, new IllegalArgumentException(marker));
            failure.addSuppressed(new IllegalArgumentException(marker));
            var completion = new AtomicInteger(-1); var request = request("cleanup");
            Throwable observed = catchThrowable(() -> service.execute(request, () -> {}, id -> {
                var ack = start(user);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCompletion() { if (phase.equals("beforeCompletion")) throw failure; }
                    @Override public void afterCompletion(int status) {
                        completion.set(status); if (phase.equals("afterCompletion")) throw failure;
                    }
                });
                if (rollback) throw new ReceiptException(STALE_VERSION);
                return ack;
            }));
            if (rollback) assertThat(observed).isInstanceOfSatisfying(ReceiptException.class,
                    clean -> assertThat(clean.reason()).isEqualTo(STALE_VERSION));
            else assertThat(observed).isNull();
            assertThat(completion).hasValue(rollback ? TransactionSynchronization.STATUS_ROLLED_BACK : TransactionSynchronization.STATUS_COMMITTED);
            assertThat(count("attempt_command_receipt")).isEqualTo(rollback ? 0 : 1);
            assertThat(count("attempt_lifecycle_state")).isEqualTo(rollback ? 0 : 1);
            assertThat(captured.list).hasSize(1);
            var event = captured.list.get(0);
            assertThat(event.getFormattedMessage()).doesNotContain(marker);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).contains("UNKNOWN_OUTCOME").doesNotContain(marker);
        } finally { logger.detachAppender(captured); captured.stop(); logger.setLevel(previous); }
    }

    @Test
    void callbacksRegisteredDuringCompletionAreProtectedBeforeTheNextPhase() throws Exception {
        var request = request("late-observer"); var calls = new AtomicInteger();
        rejected(() -> service.execute(request, () -> {}, id -> {
            calls.incrementAndGet(); var ack = start(user);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { throw new IllegalStateException("SYNTHETIC_PRIVATE_LATE_599"); }
                    });
                }
            });
            return ack;
        }), UNKNOWN_OUTCOME);
        assertThat(service.execute(request, () -> {}, id -> { calls.incrementAndGet(); return start(user); }).replayed()).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(count("attempt_command_receipt")).isEqualTo(1); assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
    }

    @Test
    void joinedTransactionsPreserveCallbackOrderUniquenessAndResourceLifecycle() throws Exception {
        var events = new ArrayList<String>(); var first = request("ordered-first"); var second = request("ordered-second");
        var savepoint = new AtomicReference<Object>();
        var resource = new TransactionSynchronization() {
            @Override public int getOrder() { return -10; }
            @Override public void suspend() { events.add("suspend"); }
            @Override public void resume() { events.add("resume"); }
            @Override public void flush() { events.add("flush"); }
            @Override public void savepoint(Object point) { savepoint.set(point); events.add("savepoint"); }
            @Override public void savepointRollback(Object point) { assertThat(point).isSameAs(savepoint.get()); events.add("savepointRollback"); }
            @Override public void beforeCommit(boolean readOnly) { assertThat(readOnly).isFalse(); events.add("resource.beforeCommit"); }
            @Override public void beforeCompletion() { events.add("resource.beforeCompletion"); }
            @Override public void afterCommit() { events.add("resource.afterCommit"); }
            @Override public void afterCompletion(int status) { assertThat(status).isEqualTo(STATUS_COMMITTED); events.add("resource.afterCompletion"); }
        };
        tx("READ_COMMITTED").executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(resource);
            service.execute(first, () -> {}, id -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { events.add("observer.beforeCommit"); }
                    @Override public void beforeCompletion() { events.add("observer.beforeCompletion"); }
                    @Override public void afterCommit() { events.add("observer.afterCommit"); }
                    @Override public void afterCompletion(int completion) { events.add("observer.afterCompletion"); }
                });
                return start(user);
            });
            // Repeated commands/re-registration must not double-wrap or invoke the resource twice.
            TransactionSynchronizationManager.registerSynchronization(resource);
            execute(second);
            status.flush();
            var point = status.createSavepoint(); status.rollbackToSavepoint(point); status.releaseSavepoint(point);
            var suspended = new TransactionTemplate(manager);
            suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            suspended.executeWithoutResult(ignored -> assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse());
        });
        assertThat(events).containsExactly("flush", "savepoint", "savepointRollback", "suspend", "resume",
                "resource.beforeCommit", "observer.beforeCommit", "resource.beforeCompletion", "observer.beforeCompletion",
                "resource.afterCommit", "observer.afterCommit", "resource.afterCompletion", "observer.afterCompletion");
        assertThat(count("attempt_command_receipt")).isEqualTo(2); assertThat(count("attempt_lifecycle_state")).isEqualTo(2);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void d08ExpiryUsesAuthoritativeTerminalTimeAndCleanupIsBoundedTransactionalAndSeparateFromFacts() throws Exception {
        var request = request("retention"); var first = execute(request); UUID attempt = first.receipt().acknowledgement().attemptId();
        var muchLater = service(manager, NOW.plus(Duration.ofDays(90)));
        assertThat(muchLater.execute(request, () -> {}, id -> fail("Active replay executed")).replayed()).isTrue();
        assertThat(tx("READ_COMMITTED").<Integer>execute(status -> muchLater.purgeExpired(attempt, 1, 100))).isZero();
        // Four additional receipts bind real lifecycle writes. This exercises the reusable receipt
        // boundary with #528; accepted-response and navigation writers remain separate consumers.
        for (int i = 1; i <= 4; i++) {
            long version = i;
            var original = request("retention-" + i);
            var another = new Request(1, user, user, AttemptCommands.Type.SUBMIT_ANSWER, attempt,
                    version, original.key(), original.input());
            service.execute(another, () -> {}, id -> {
                lifecycle.save(attempt, version, new AttemptLifecycle.State(1,
                        AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.IN_PROGRESS));
                return new Acknowledgement(1, attempt, attempt, version + 1);
            });
        }
        lifecycle.save(attempt, 5, TERMINAL);
        var atBoundary = service(manager, NOW.plus(CommandReceipt.RETENTION));
        var beforeBoundary = service(manager, NOW.plus(CommandReceipt.RETENTION).minusNanos(1000));
        assertThat(beforeBoundary.execute(request, () -> {}, id -> fail("Replay executed")).replayed()).isTrue();
        rejected(() -> atBoundary.execute(request, () -> {}, id -> fail("Expired replay executed")), EXPIRED);
        rejected(() -> atBoundary.purgeExpired(attempt, 6, 1), TRANSACTION_REQUIRED);
        rejected(() -> tx("READ_COMMITTED").execute(status -> atBoundary.purgeExpired(attempt, 1, 1)), STALE_VERSION);
        assertThat(tx("READ_COMMITTED").<Integer>execute(status -> beforeBoundary.purgeExpired(attempt, 6, 100))).isZero();
        tx("READ_COMMITTED").executeWithoutResult(status -> { assertThat(atBoundary.purgeExpired(attempt, 6, 2)).isEqualTo(2); status.setRollbackOnly(); });
        assertThat(count("attempt_command_receipt")).isEqualTo(5);
        assertThat(tx("READ_COMMITTED").<Integer>execute(status -> atBoundary.purgeExpired(attempt, 6, 2))).isEqualTo(2);
        assertThat(count("attempt_command_receipt")).isEqualTo(3);
        assertThat(tx("READ_COMMITTED").<Integer>execute(status -> atBoundary.purgeExpired(attempt, 6, 100))).isEqualTo(3);
        assertThat(count("attempt_command_receipt")).isZero();
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1); assertThat(count("attempt_participant_subject")).isEqualTo(1);
        // Natural terminal-state guard still rejects a new-key attempt to repeat the effect after cleanup.
        rejected(() -> service.execute(request("post-cleanup"), () -> {}, id -> {
            lifecycle.save(attempt, 6, TERMINAL); return first.receipt().acknowledgement();
        }), COMMAND_DENIED);
        assertThat(count("attempt_command_receipt")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,identical", "READ_COMMITTED,conflicting", "READ_COMMITTED,rollback"})
    void concurrentFirstRequestsWaitForTheRealWinnerOrTakeOverOnlyAfterItsRollback(String isolation, String kind) throws Exception {
        var request = request("concurrent");
        var secondRequest = kind.equals("conflicting") ? new Request(1, user, request.contextId(), request.operation(),
                UUID.randomUUID(), 0, request.key(), request.input()) : request;
        var firstEntered = new CountDownLatch(1); var secondEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstConnection = new AtomicLong(); var secondConnection = new AtomicLong(); var secondEffects = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> tx(isolation).execute(status -> {
                firstConnection.set(connectionId());
                var result = service.execute(request, () -> {}, id -> {
                    firstEntered.countDown(); await(release); return start(user);
                });
                if (kind.equals("rollback")) status.setRollbackOnly();
                return result;
            }));
            await(firstEntered);
            var second = workers.submit(() -> outcome(() -> tx(isolation).execute(status -> {
                secondConnection.set(connectionId()); secondEntered.countDown();
                return service.execute(secondRequest, () -> {}, id -> { secondEffects.incrementAndGet(); return start(user); });
            })));
            await(secondEntered); awaitDatabaseLockWait(secondConnection.get(), firstConnection.get());
            assertThat(second.isDone()).isFalse(); release.countDown();
            Result winner = first.get(25, TimeUnit.SECONDS); Object following = second.get(25, TimeUnit.SECONDS);
            if (kind.equals("conflicting")) assertThat(following).isEqualTo(CONFLICT);
            else {
                assertThat(following).isInstanceOf(Result.class); var result = (Result) following;
                assertThat(result.replayed()).isEqualTo(!kind.equals("rollback"));
                if (kind.equals("identical")) assertThat(result.receipt()).isEqualTo(winner.receipt());
                else assertThat(result.receipt().id()).isNotEqualTo(winner.receipt().id());
            }
            assertThat(secondEffects).hasValue(kind.equals("rollback") ? 1 : 0);
            assertThat(count("attempt_command_receipt")).isEqualTo(1);
            assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
            assertThat(count("attempt_participant_subject")).isEqualTo(1);
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED"})
    void unrelatedKeysDoNotSerializeThroughAbsentKeyGapLocks(String isolation) throws Exception {
        var first = request("independent-a"); var second = request("independent-b"); var barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> tx(isolation).execute(status -> service.execute(first, () -> {}, id -> { barrier(barrier); return start(user); })));
            var b = workers.submit(() -> tx(isolation).execute(status -> service.execute(second, () -> {}, id -> { barrier(barrier); return start(user); })));
            assertThat(a.get(25, TimeUnit.SECONDS).replayed()).isFalse(); assertThat(b.get(25, TimeUnit.SECONDS).replayed()).isFalse();
            assertThat(count("attempt_command_receipt")).isEqualTo(2); assertThat(count("attempt_lifecycle_state")).isEqualTo(2);
        } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void oldRepeatableReadSnapshotIsRejectedBeforeReceiptReplayOrWork() throws Exception {
        var request = request("old-snapshot"); var worker = Executors.newSingleThreadExecutor();
        var original = new AtomicReference<Result>();
        try {
            tx("REPEATABLE_READ").executeWithoutResult(status -> {
                assertThat(count("attempt_command_receipt")).isZero(); assertThat(count("attempt_lifecycle_state")).isZero();
                try { original.set(worker.submit(() -> execute(request)).get(10, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(count("attempt_command_receipt")).isZero();
                assertThatThrownBy(() -> service.execute(request, () -> fail("Incompatible caller reached authorization"), id -> fail("Old snapshot repeated effect")))
                        .isInstanceOf(uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException.class);
            });
            assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
            var replay = service.execute(request, () -> {}, id -> fail("Fresh replay repeated effect"));
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.receipt()).isEqualTo(original.get().receipt());
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED"})
    void lockTimeoutReturnsInProgressWithoutRunningLosingWorkOrRetrying(String isolation) throws Exception {
        var request = request("timeout"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var workers = Executors.newSingleThreadExecutor();
        try {
            var first = workers.submit(() -> service.execute(request, () -> {}, id -> { entered.countDown(); await(release); return start(user); }));
            await(entered);
            rejected(() -> tx(isolation).execute(status -> {
                sql.execute("SET SESSION innodb_lock_wait_timeout=1");
                return service.execute(request, () -> {}, id -> fail("Timed-out duplicate ran"));
            }), IN_PROGRESS);
            assertThat(source.sqlErrorCodes).contains(1205);
            release.countDown(); assertThat(first.get(10, TimeUnit.SECONDS).replayed()).isFalse();
            assertThat(execute(request).replayed()).isTrue(); assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED"})
    void actualDeadlockRollsBackVictimReceiptsAndEffectsWithoutHiddenRetry(String isolation) throws Exception {
        var a = request("deadlock-a"); var b = request("deadlock-b"); var barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2); var outerCalls = new AtomicInteger();
        try {
            var first = workers.submit(() -> deadlock(a, b, isolation, barrier, outerCalls));
            var second = workers.submit(() -> deadlock(b, a, isolation, barrier, outerCalls));
            var results = List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Result.class::isInstance).count()).isEqualTo(1);
            assertThat(results).contains(RETRYABLE); assertThat(outerCalls).hasValue(2);
            assertThat(source.sqlErrorCodes).contains(1213);
            assertThat(count("attempt_command_receipt")).isEqualTo(2); assertThat(count("attempt_lifecycle_state")).isEqualTo(2);
            assertThat(execute(a).replayed()).isTrue(); assertThat(execute(b).replayed()).isTrue();
        } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void corruptOrphanAndUnknownVersionNeverTriggerTakeover() throws Exception {
        var request = request("orphan");
        // Deliberately corrupt fixture, impossible through execute(): commit an unfinished storage reservation.
        tx("READ_COMMITTED").executeWithoutResult(status -> repository.reserve(request, UUID.randomUUID(), NOW));
        rejected(() -> service.execute(request, () -> {}, id -> fail("Orphan was taken over")), IN_PROGRESS);
        assertThat(count("attempt_lifecycle_state")).isZero();
        sql.execute("ALTER TABLE attempt_command_receipt ALTER CHECK chk_receipt_request NOT ENFORCED");
        try {
            sql.update("UPDATE attempt_command_receipt SET schema_version=2");
            rejected(() -> execute(request), UNSUPPORTED_VERSION);
        } finally {
            sql.update("UPDATE attempt_command_receipt SET schema_version=1");
            sql.execute("ALTER TABLE attempt_command_receipt ALTER CHECK chk_receipt_request ENFORCED");
        }
        assertThat(count("attempt_lifecycle_state")).isZero();
    }

    @Test
    void replayQueriesStayConstantAcrossMultipleParentsAndUseUniqueIndex() throws Exception {
        for (int size : List.of(3, 30)) {
            Result last = null; Request request = null;
            for (int i = 0; i < size; i++) { request = request("queries-" + size + "-" + i); last = execute(request); }
            source.selects.clear();
            var replay = service.execute(request, () -> {}, id -> fail("Replay executed"));
            assertThat(replay.receipt()).isEqualTo(last.receipt());
            assertThat(source.selects).hasSize(2).allSatisfy(statement -> assertThat(statement)
                    .doesNotContain("question_revision", "content", "attempt_form_item", "JOIN answers"));
            var plan = sql.queryForMap("EXPLAIN SELECT * FROM attempt_command_receipt WHERE user_id=? AND key_hash=? FOR UPDATE",
                    bytes(user), request.key().digest());
            assertThat(plan.get("key")).isEqualTo("uq_attempt_receipt_key");
            assertThat(((Number) plan.get("rows")).longValue()).isEqualTo(1);
            var cleanup = sql.queryForMap("EXPLAIN DELETE FROM attempt_command_receipt WHERE attempt_id=? AND status='COMMITTED' ORDER BY id LIMIT 100",
                    bytes(last.receipt().acknowledgement().attemptId()));
            assertThat(cleanup.get("key")).isEqualTo("ix_attempt_receipt_cleanup");
        }
    }

    @Test
    void telemetryIsBoundedAndItsFailureCannotReplaceSuccess() throws Exception {
        var request = request("metrics"); execute(request); execute(request);
        for (var meter : metrics.getMeters()) if (meter.getId().getName().equals("attempt.command.receipt")) {
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("version", "operation", "outcome");
                assertThat(tag.getValue()).doesNotContain(user.toString(), request.key().digest(), request.input().digest());
            });
        }
        var broken = mock(MeterRegistry.class); when(broken.config()).thenThrow(new IllegalStateException("Synthetic metric failure"));
        var writer = new CommandReceiptServiceImpl(repository, manager, Clock.fixed(NOW, ZoneOffset.UTC), broken);
        assertThat(writer.execute(request, () -> {}, id -> fail("Replay executed")).replayed()).isTrue();
    }

    @Test
    void receiptAndRealLifecycleWritesJoinJpaCommitAndRollback() throws Exception {
        var request = request("jpa");
        var factory = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.attempt.receipt");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); factory.afterPropertiesSet();
        try {
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject()); jpa.setDataSource(source);
            var template = new TransactionTemplate(jpa); template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); var writer = service(jpa, NOW); var states = lifecycle(jpa, NOW);
            var bindings = new AttemptSubjectServiceImpl(proxy(new JdbcAttemptSubjectRepository(named()), jpa), jpa, metrics);
            var requestId = new AtomicReference<UUID>();
            CommandReceiptService.Work work = id -> {
                UUID attempt = UUID.randomUUID(); requestId.set(attempt);
                bindings.bindOrReplay(user, AttemptSubject.authenticated(attempt, user));
                states.initialize(attempt); states.save(attempt, 0, STARTED);
                // Real EntityManager write uses the same connection and rolls back with both foundations.
                org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(factory.getObject())
                        .createNativeQuery("UPDATE users SET email_verified=TRUE WHERE user_id=:user")
                        .setParameter("user", bytes(user)).executeUpdate();
                return new Acknowledgement(1, attempt, attempt, 1);
            };
            template.executeWithoutResult(status -> { writer.execute(request, () -> {}, work); status.setRollbackOnly(); });
            assertThat(count("attempt_command_receipt")).isZero(); assertThat(count("attempt_lifecycle_state")).isZero();
            assertThat(sql.queryForObject("SELECT email_verified FROM users WHERE user_id=?", Boolean.class, bytes(user))).isFalse();
            Result original = template.execute(status -> writer.execute(request, () -> {}, work));
            assertThat(count("attempt_command_receipt")).isEqualTo(1); assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT email_verified FROM users WHERE user_id=?", Boolean.class, bytes(user))).isTrue();
            assertThat(writer.execute(request, () -> {}, id -> fail("Jpa replay executed")).receipt()).isEqualTo(original.receipt());
        } finally { factory.destroy(); }
    }

    @Test
    void invalidArgumentsUnavailableDatabaseAndImpossibleResultVersionNeverClaimSuccess() throws Exception {
        var request = request("invalid");
        rejected(() -> service.execute(null, () -> {}, id -> start(user)), INVALID_INPUT);
        rejected(() -> service.execute(request, null, id -> start(user)), INVALID_INPUT);
        rejected(() -> service.execute(request, () -> {}, null), INVALID_INPUT);
        rejected(() -> service.execute(request, () -> {}, id -> {
            var ack = start(user); return new Acknowledgement(1, ack.attemptId(), ack.resultId(), 2);
        }), CORRUPT_RECEIPT);
        assertThat(count("attempt_lifecycle_state")).isZero(); assertThat(count("attempt_command_receipt")).isZero();
        source.failConnection = true;
        try { rejected(() -> execute(request), PERSISTENCE_UNAVAILABLE); }
        finally { source.reset(); }
        Result result = execute(request); assertThat(result.replayed()).isFalse();
        for (int limit : List.of(-1, 0, 101)) rejected(() -> tx("READ_COMMITTED").execute(status ->
                service.purgeExpired(result.receipt().acknowledgement().attemptId(), 1, limit)), INVALID_INPUT);
        rejected(() -> tx("READ_COMMITTED").execute(status -> service.purgeExpired(UUID.randomUUID(), 1, 100)), CONTEXT_UNAVAILABLE);
    }

    @Test
    void schemaRejectsUnknownStatusOperationAndIncompleteSuccess() throws Exception {
        var request = request("schema"); execute(request);
        for (String assignment : List.of("schema_version=2", "operation='VIEW_ATTEMPT'", "status='FAILED'",
                "input_hash='INVALID'", "state_version=0", "acknowledgement_version=NULL", "result_id=NULL")) {
            assertThatThrownBy(() -> sql.update("UPDATE attempt_command_receipt SET " + assignment))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(execute(request).replayed()).isTrue();
        assertThat(count("attempt_lifecycle_state")).isEqualTo(1);
    }

    private Object deadlock(Request first, Request next, String isolation, CyclicBarrier barrier, AtomicInteger calls) {
        return outcome(() -> tx(isolation).execute(status -> service.execute(first, () -> {}, id -> {
            calls.incrementAndGet(); var ack = start(user); barrier(barrier);
            service.execute(next, () -> {}, nested -> start(user)); return ack;
        })));
    }
    private static Object outcome(java.util.function.Supplier<?> operation) {
        try { return operation.get(); } catch (ReceiptException failure) { return failure.reason(); }
    }
    private static long connectionId() { return sql.queryForObject("SELECT CONNECTION_ID()", Long.class); }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void barrier(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void awaitDatabaseLockWait(long requester, long blocker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int waits = admin.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID
                    WHERE r.PROCESSLIST_ID=? AND b.PROCESSLIST_ID=?
                    """, Integer.class, requester, blocker);
            if (waits > 0) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        fail("Expected a real MySQL receipt lock wait");
    }

    private Result execute(Request request) { return service.execute(request, () -> {}, id -> start(request.userId())); }
    private Acknowledgement start(UUID actor) {
        UUID attempt = UUID.randomUUID();
        subjects.bindOrReplay(actor, AttemptSubject.authenticated(attempt, actor));
        lifecycle.initialize(attempt); lifecycle.save(attempt, 0, STARTED);
        return new Acknowledgement(1, attempt, attempt, 1);
    }
    private Request request(String key) throws Exception {
        return new Request(1, user, user, AttemptCommands.Type.START, user, 0, Key.from(key), fingerprint("{\"selection\":\"ALL\"}"));
    }
    private static UUID user() {
        UUID user = UUID.randomUUID(); var timestamp = java.sql.Timestamp.from(NOW);
        sql.update("INSERT INTO users (user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version) VALUES (?,?,?,'unused',TRUE,FALSE,FALSE,?,?,0)",
                bytes(user), "receipt-" + user, user + "@example.invalid", timestamp, timestamp);
        return user;
    }
    private CommandReceiptService service(PlatformTransactionManager transactions, Instant now) {
        return new CommandReceiptServiceImpl(proxy(new JdbcCommandReceiptRepository(named()), transactions), transactions, Clock.fixed(now, ZoneOffset.UTC), metrics);
    }
    private AttemptLifecycleService lifecycle(PlatformTransactionManager transactions, Instant now) {
        return new AttemptLifecycleServiceImpl(proxy(new JdbcLifecycleStateRepository(named()), transactions), transactions, Clock.fixed(now, ZoneOffset.UTC), metrics);
    }
    private static TransactionTemplate tx(String isolation) {
        var result = new TransactionTemplate(manager); result.setIsolationLevelName("ISOLATION_" + isolation); result.setTimeout(20); return result;
    }
    private static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(source); }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, PlatformTransactionManager transactions) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
