package uk.gegc.quizmaker.features.attempt.trace;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.ExecutionTraceService;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException;
import uk.gegc.quizmaker.features.attempt.domain.repository.ExecutionTraceRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.trace.ExecutionTraceTest.*;
import static uk.gegc.quizmaker.features.attempt.trace.TraceFixtures.*;

/** Real prerequisites and trace writer/transactions. No start command, remote provider or query authorization is simulated. */
@Tag("db-serial")
class ExecutionTraceMySqlIntegrationTest {
    private static JdbcTemplate admin;
    private static String catalog;
    private static TraceDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager manager;
    private static Map<String, Object> legacyDefinition;
    private SimpleMeterRegistry metrics;
    private ExecutionTraceRepository repository;
    private ExecutionTraceService service;

    @BeforeAll
    static void migrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        String name = "quizmaker_538_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        catalog = name;
        source = new TraceDataSource(new DriverManagerDataSource(base + name + options, user, password));
        sql = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("89").load().migrate();
        legacyDefinition = sql.queryForMap("SHOW CREATE TABLE attempts");
        sql.execute("CREATE TABLE trace_business_fixture (id INT PRIMARY KEY, marker VARCHAR(20))");
        sql.update("INSERT INTO trace_business_fixture VALUES (1,'original'),(2,'original')");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("90").load().migrate();
    }

    @BeforeEach
    void prepare() {
        source.reset();
        sql.update("DELETE FROM attempt_trace_identity");
        sql.update("DELETE FROM attempt_trace_event");
        sql.update("DELETE FROM attempt_trace_stream");
        sql.update("UPDATE trace_business_fixture SET marker='original'");
        metrics = new SimpleMeterRegistry();
        repository = proxy(new JdbcExecutionTraceRepository(named()), manager);
        service = service(repository, manager, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void reset() { source.reset(); metrics.close(); }

    @AfterAll
    static void dropOnlyOwnedCatalog() {
        if (source != null) source.reset();
        if (catalog != null) admin.execute("DROP DATABASE " + catalog);
    }

    @Test
    void migrationIsAdditiveAndContainsOnlyTheAllowListedColumnsAndConstraints() {
        assertThat(sql.queryForMap("SHOW CREATE TABLE attempts")).isEqualTo(legacyDefinition);
        assertThat(count("attempt_trace_event")).isZero();
        assertThat(count("attempt_trace_stream")).isZero();
        assertThat(sql.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("90");
        assertThat(sql.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attempt_trace_event' ORDER BY ORDINAL_POSITION", String.class))
                .containsExactly("attempt_id", "sequence_number", "event_id", "schema_version", "event_type", "actor_type", "recorded_at", "command_id", "form_id", "item_id", "from_execution", "from_answers", "to_execution", "to_answers", "reason_code");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('attempt_trace_event','attempt_trace_stream','attempt_trace_identity') AND CONSTRAINT_TYPE='CHECK' AND ENFORCED='YES'", Integer.class)).isEqualTo(7);
        assertThat(sql.queryForList("SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('attempt_trace_event','attempt_trace_stream')", String.class))
                .doesNotContain("CASCADE");
    }

    @Test
    void realPrerequisiteWritesAndTraceCommitTogetherAndReplayAfterRestartKeepsOriginalTime() {
        var captured = new AtomicReference<Scope>();
        var first = tx("READ_COMMITTED").execute(status -> {
            var scope = fixture(); captured.set(scope);
            sql.update("UPDATE trace_business_fixture SET marker='committed' WHERE id=1");
            return service.append(scope, event("restart"));
        });
        assertThat(first.replayed()).isFalse();
        assertThat(first.event().sequence()).isEqualTo(1);
        assertThat(marker(1)).isEqualTo("committed");
        var restarted = service(proxy(new JdbcExecutionTraceRepository(named()), manager), manager, metrics,
                Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC));
        var replay = tx("REPEATABLE_READ").execute(status -> restarted.append(captured.get(), event("restart")));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.event()).isEqualTo(first.event());
        assertThat(page(captured.get())).containsExactly(first.event());
        assertThat(count("attempt_trace_event")).isEqualTo(1);
    }

    @Test
    void everySemanticFieldAndScopeIsPartOfReplayIdentity() {
        var scope = fixture(); var original = event("identity");
        append(scope, original);
        var changed = List.of(
                new Event(1, original.id(), Type.ANSWER_LOCKED, original.actor(), original.data()),
                new Event(1, original.id(), original.type(), Actor.SYSTEM, original.data()),
                new Event(1, original.id(), original.type(), original.actor(), new Data(null, null, null, null, ReasonCode.ACCEPTED)),
                new Event(1, original.id(), original.type(), original.actor(), new Data(original.data().commandId(), null, null, null, ReasonCode.LOCKED)),
                new Event(1, original.id(), original.type(), original.actor(), new Data(original.data().commandId(), null,
                        new State(1, Execution.CREATED, AnswerCollection.NOT_STARTED), new State(1, Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED), ReasonCode.ACCEPTED)));
        for (var value : changed) rejected(() -> append(scope, value), EVENT_CONFLICT);
        var other = fixture();
        rejected(() -> append(other, original), EVENT_CONFLICT);
        rejected(() -> append(new Scope(scope.attemptId(), other.policySnapshotId(), other.formId()), original), EVENT_CONFLICT);
        assertThat(page(scope)).hasSize(1);
        assertThat(page(other)).isEmpty();
        assertThat(append(scope, event("identity-next")).event().sequence()).isEqualTo(2);
    }

    @Test
    void missingLegacyOrIncompatibleContextAndForeignItemNeverLeavePartialStreams() {
        var scope = fixture(); var other = fixture();
        rejected(() -> append(new Scope(id("legacy-with-no-target"), scope.policySnapshotId(), scope.formId()), event("legacy")), CONTEXT_UNAVAILABLE);
        rejected(() -> append(new Scope(scope.attemptId(), scope.policySnapshotId(), other.formId()), event("foreign-revision")), CONTEXT_UNAVAILABLE);
        var wrongItem = new Event(1, id("foreign-item-event"), Type.QUESTION_DELIVERED, Actor.SYSTEM,
                new Data(null, id("not-in-form"), null, null, ReasonCode.DELIVERED));
        rejected(() -> append(scope, wrongItem), EVENT_CONFLICT);
        assertThat(count("attempt_trace_stream")).isZero();
        assertThat(count("attempt_trace_event")).isZero();
        assertThat(append(scope, event("valid-control")).event().sequence()).isEqualTo(1);
    }

    @Test
    void appendWithoutCallerTransactionOrWithInvalidInputCannotCommitEvidence() {
        var scope = fixture();
        rejected(() -> service.append(scope, event("outside")), TRANSACTION_REQUIRED);
        rejected(() -> tx("READ_COMMITTED").execute(status -> service.append(null, event("null"))), INVALID_INPUT);
        rejected(() -> tx("READ_COMMITTED").execute(status -> service.append(scope, null)), INVALID_INPUT);
        assertThat(count("attempt_trace_stream")).isZero();
    }

    @Test
    void faultAfterRealInsertMarksCaughtFailureRollbackOnlyAndUndoesCounterAndBusinessWrites() {
        var scope = fixture(); var reached = new AtomicBoolean();
        source.afterSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO attempt_trace_event")) {
                assertThat(count("attempt_trace_event")).isEqualTo(1);
                reached.set(true); throw new SQLException("Synthetic post-insert failure", "45000");
            }
        };
        assertThatThrownBy(() -> tx("READ_COMMITTED").executeWithoutResult(status -> {
            sql.update("UPDATE trace_business_fixture SET marker='partial' WHERE id=1");
            rejected(() -> service.append(scope, event("reached-insert")), PERSISTENCE_UNAVAILABLE);
            assertThat(status.isRollbackOnly()).isTrue();
            sql.update("UPDATE trace_business_fixture SET marker='after-catch' WHERE id=2");
        })).isInstanceOf(UnexpectedRollbackException.class);
        source.reset();
        assertThat(reached).isTrue();
        assertThat(count("attempt_trace_stream")).isZero();
        assertThat(count("attempt_trace_event")).isZero();
        assertThat(marker(1)).isEqualTo("original"); assertThat(marker(2)).isEqualTo("original");
        assertThat(append(scope, event("reached-insert")).event().sequence()).isEqualTo(1);
    }

    @Test
    void failureAfterActualCounterUpdateAndOuterRollbackNeverConsumeSequence() {
        var scope = fixture(); append(scope, event("before-rollback"));
        var reached = new AtomicBoolean();
        source.afterSql = (statement, connection) -> {
            if (statement.startsWith("UPDATE attempt_trace_stream SET")) {
                assertThat(tail(scope)).isEqualTo(2); reached.set(true);
                throw new SQLException("Synthetic counter failure", "45000");
            }
        };
        rejected(() -> append(scope, event("rolled-back")), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(reached).isTrue();
        assertThat(tail(scope)).isEqualTo(1); assertThat(page(scope)).hasSize(1);
        tx("READ_COMMITTED").executeWithoutResult(status -> {
            assertThat(service.append(scope, event("outer-rollback")).event().sequence()).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(page(scope)).hasSize(1);
        assertThat(append(scope, event("after-rollback")).event().sequence()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailureIsReconciledFromDurableIdentityInAFreshTransaction(boolean committed) {
        var scope = fixture(); var hit = new AtomicBoolean();
        if (committed) source.afterCommit = connection -> { hit.set(true); throw new SQLException("Lost commit acknowledgement", "08006"); };
        else source.beforeCommit = connection -> { connection.rollback(); hit.set(true); throw new SQLException("Commit rejected", "08006"); };
        assertThatThrownBy(() -> append(scope, event("uncertain"))).isInstanceOf(TransactionException.class);
        source.reset();
        assertThat(hit).isTrue();
        assertThat(page(scope)).hasSize(committed ? 1 : 0);
        var restarted = service(repository, manager, metrics, Clock.fixed(NOW.plusSeconds(500), ZoneOffset.UTC));
        var result = tx("READ_COMMITTED").execute(status -> restarted.append(scope, event("uncertain")));
        assertThat(result.replayed()).isEqualTo(committed);
        assertThat(result.event().sequence()).isEqualTo(1);
        assertThat(result.event().recordedAt()).isEqualTo(committed ? NOW : NOW.plusSeconds(500));
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,distinct", "REPEATABLE_READ,distinct", "READ_COMMITTED,identical", "REPEATABLE_READ,identical", "READ_COMMITTED,conflicting", "REPEATABLE_READ,conflicting"})
    void concurrentAppendsHaveSuccessfulControlsStableIdentityAndGapFreeSequence(String isolation, String kind) throws Exception {
        var scope = fixture(); var first = event("race-a");
        var second = switch (kind) {
            case "identical" -> first;
            case "conflicting" -> new Event(1, first.id(), Type.ANSWER_LOCKED, first.actor(), first.data());
            default -> event("race-b");
        };
        var barrier = new CyclicBarrier(2); var connections = ConcurrentHashMap.<Integer>newKeySet();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO attempt_trace_stream")) {
                connections.add(connection); barrier(barrier);
            }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> result(scope, first, isolation));
            var b = workers.submit(() -> result(scope, second, isolation));
            var outcomes = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            source.reset();
            assertThat(connections).hasSize(2);
            var successes = outcomes.stream().filter(Appended.class::isInstance).map(Appended.class::cast).toList();
            assertThat(successes).hasSize(kind.equals("conflicting") ? 1 : 2);
            if (kind.equals("conflicting")) assertThat(outcomes).contains(EVENT_CONFLICT);
            if (kind.equals("identical")) {
                assertThat(successes).extracting(Appended::replayed).containsExactlyInAnyOrder(false, true);
                assertThat(successes.get(0).event()).isEqualTo(successes.get(1).event());
            }
            var saved = page(scope);
            assertThat(saved).hasSize(kind.equals("distinct") ? 2 : 1);
            assertThat(saved).extracting(Stored::sequence).containsExactlyElementsOf(kind.equals("distinct") ? List.of(1L, 2L) : List.of(1L));
            for (var success : successes) assertThat(saved).contains(success.event());
            assertThat(tail(scope)).isEqualTo(saved.size());
        } finally { source.reset(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oldRepeatableReadSnapshotUsesCommittedWinnerForReplayAndNextSequence(boolean replay) throws Exception {
        var scope = fixture(); var worker = Executors.newSingleThreadExecutor();
        try {
            tx("REPEATABLE_READ").executeWithoutResult(status -> {
                assertThat(pageInTransaction(scope)).isEmpty();
                try { worker.submit(() -> append(scope, event("fresh-winner"))).get(15, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(pageInTransaction(scope)).isEmpty(); // The original snapshot really is stale.
                var next = service.append(scope, event(replay ? "fresh-winner" : "new-after-winner"));
                assertThat(next.replayed()).isEqualTo(replay);
                assertThat(next.event().sequence()).isEqualTo(replay ? 1 : 2);
            });
            assertThat(page(scope)).hasSize(replay ? 1 : 2);
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void differentStreamsCanAppendTheirFirstEventsWithoutSharedAbsentKeyGapLocks(String isolation) throws Exception {
        var a = fixture(); var b = fixture();
        var roots = new CyclicBarrier(2); var missingLookups = new CyclicBarrier(2);
        source.afterSql = (statement, connection) -> {
            if (statement.startsWith("SELECT * FROM attempt_trace_stream")) barrier(roots);
            // Regression probe: an implementation querying an absent event before reserving its
            // identity reaches both shared gap locks before either insert. Fresh writes skip it.
            if (statement.contains("WHERE e.event_id=")) barrier(missingLookups);
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> result(a, event("independent-a"), isolation));
            var second = workers.submit(() -> result(b, event("independent-b"), isolation));
            assertThat(List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS)))
                    .allSatisfy(value -> assertThat(value).isInstanceOf(Appended.class));
            source.reset();
            assertThat(page(a)).extracting(Stored::sequence).containsExactly(1L);
            assertThat(page(b)).extracting(Stored::sequence).containsExactly(1L);
        } finally { source.reset(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void traceAndRealLifecycleChangeJoinJpaCommitAndRollback() {
        var scope = fixture();
        var factory = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.attempt.trace");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); factory.afterPropertiesSet();
        try {
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject()); jpa.setDataSource(source);
            var template = new TransactionTemplate(jpa);
            var writer = service(proxy(new JdbcExecutionTraceRepository(named()), jpa), jpa, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
            var lifecycle = new AttemptLifecycleServiceImpl(proxy(new JdbcLifecycleStateRepository(named()), jpa), jpa, Clock.fixed(NOW, ZoneOffset.UTC), metrics);
            var started = new State(1, Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED);
            var created = new State(1, Execution.CREATED, AnswerCollection.NOT_STARTED);
            UUID item = sql.queryForObject("SELECT id FROM attempt_form_item WHERE form_id=?", (row, index) -> {
                var value = java.nio.ByteBuffer.wrap(row.getBytes(1)); return new UUID(value.getLong(), value.getLong());
            }, bytes(scope.formId()));
            var event = new Event(1, id("jpa-start"), Type.ATTEMPT_STARTED, Actor.PARTICIPANT,
                    new Data(id("jpa-command"), item, created, started, ReasonCode.ACCEPTED));
            template.executeWithoutResult(status -> {
                org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(factory.getObject())
                        .createNativeQuery("UPDATE trace_business_fixture SET marker='jpa' WHERE id=1").executeUpdate();
                lifecycle.save(scope.attemptId(), 0, started);
                assertThat(writer.append(scope, event).event().sequence()).isEqualTo(1);
                status.setRollbackOnly();
            });
            assertThat(marker(1)).isEqualTo("original");
            assertThat(page(scope)).isEmpty();
            assertThat(sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state WHERE attempt_id=?", Long.class, bytes(scope.attemptId()))).isZero();
            template.executeWithoutResult(status -> {
                lifecycle.save(scope.attemptId(), 0, started);
                writer.append(scope, event);
            });
            assertThat(page(scope)).hasSize(1);
            assertThat(page(scope).get(0).event()).isEqualTo(event);
            assertThat(sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state WHERE attempt_id=?", Long.class, bytes(scope.attemptId()))).isEqualTo(1);
        } finally { factory.destroy(); }
    }

    @Test
    void unknownPersistedVersionAndCorruptMetadataFailClosedAndCannotExtendTheStream() {
        var scope = fixture(); append(scope, event("corrupt"));
        sql.execute("ALTER TABLE attempt_trace_event ALTER CHECK chk_trace_event_version NOT ENFORCED");
        try {
            sql.update("UPDATE attempt_trace_event SET schema_version=2 WHERE attempt_id=?", bytes(scope.attemptId()));
            rejected(() -> append(scope, event("corrupt-next")), UNSUPPORTED_VERSION);
            rejected(() -> page(scope), UNSUPPORTED_VERSION);
        } finally {
            sql.update("UPDATE attempt_trace_event SET schema_version=1 WHERE attempt_id=?", bytes(scope.attemptId()));
            sql.execute("ALTER TABLE attempt_trace_event ALTER CHECK chk_trace_event_version ENFORCED");
        }
        sql.execute("ALTER TABLE attempt_trace_event ALTER CHECK chk_trace_event_type NOT ENFORCED");
        try {
            sql.update("UPDATE attempt_trace_event SET event_type='private-secret' WHERE attempt_id=?", bytes(scope.attemptId()));
            rejected(() -> append(scope, event("corrupt-next")), CORRUPT_TRACE);
            rejected(() -> page(scope), CORRUPT_TRACE);
        } finally {
            sql.update("UPDATE attempt_trace_event SET event_type='ANSWER_SUBMITTED' WHERE attempt_id=?", bytes(scope.attemptId()));
            sql.execute("ALTER TABLE attempt_trace_event ALTER CHECK chk_trace_event_type ENFORCED");
        }
        assertThat(tail(scope)).isEqualTo(1);
        assertThat(count("attempt_trace_identity")).isEqualTo(1);
        assertThat(append(scope, event("valid-after-corruption-repair")).event().sequence()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void realDeadlockRollsBackLosingBusinessAndTraceWritesWithoutHiddenRetry(String isolation) throws Exception {
        var a = fixture(); var b = fixture();
        append(a, event("initial-a")); append(b, event("initial-b"));
        var barrier = new CyclicBarrier(2); var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> deadlockWorker(a, b, 1, isolation, barrier));
            var second = workers.submit(() -> deadlockWorker(b, a, 2, isolation, barrier));
            var outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Appended.class::isInstance)).hasSize(1);
            assertThat(outcomes).contains(PERSISTENCE_UNAVAILABLE);
            assertThat(source.sqlErrorCodes).contains(1213);
            for (int i = 0; i < 2; i++) assertThat(marker(i + 1)).isEqualTo(outcomes.get(i) instanceof Appended ? "worker" : "original");
            assertThat(page(a).size() + page(b).size()).isEqualTo(3);
            assertThat(tail(a) + tail(b)).isEqualTo(3);
        } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void boundedPagesUseOneIndexedQueryWithMultipleParentsAndNeverLoadContentOrRelationships() {
        var scopes = List.of(fixture(), fixture(), fixture());
        for (int p = 0; p < scopes.size(); p++) for (int i = 0; i < 5; i++) append(scopes.get(p), event("page-" + p + "-" + i));
        source.reset();
        for (var scope : scopes) {
            int before = source.selects.size();
            var first = tx("READ_COMMITTED").execute(status -> repository.page(scope.attemptId(), 0, 2));
            assertThat(first).extracting(Stored::sequence).containsExactly(1L, 2L);
            assertThat(source.selects.size() - before).isEqualTo(1);
            assertThat(tx("READ_COMMITTED").<List<Stored>>execute(status -> repository.page(scope.attemptId(), 2, 100)))
                    .extracting(Stored::sequence).containsExactly(3L, 4L, 5L);
        }
        assertThat(source.selects).allSatisfy(statement -> assertThat(statement).doesNotContain("question_revisions", "content", "users", "documents"));
        var plan = sql.queryForList("EXPLAIN SELECT e.*, s.policy_snapshot_id FROM attempt_trace_event e FORCE INDEX (PRIMARY) JOIN attempt_trace_stream s ON s.attempt_id=e.attempt_id WHERE e.attempt_id=? AND e.sequence_number>? ORDER BY e.sequence_number LIMIT 2", bytes(scopes.get(0).attemptId()), 1);
        var eventsPlan = plan.stream().filter(row -> row.get("table").equals("e")).findFirst().orElseThrow();
        assertThat(eventsPlan.get("key")).isEqualTo("PRIMARY");
        assertThat(plan).allSatisfy(row -> assertThat(String.valueOf(row.get("Extra"))).doesNotContain("filesort"));
        rejected(() -> tx("READ_COMMITTED").execute(status -> repository.page(scopes.get(0).attemptId(), 0, 101)), INVALID_INPUT);
        rejected(() -> tx("READ_COMMITTED").execute(status -> repository.page(null, 0, 1)), INVALID_INPUT);
    }

    @Test
    void databaseRejectsUnknownVocabularyInvalidTransitionsAndDuplicateSequence() {
        var scope = fixture(); var original = append(scope, event("constraints")).event();
        for (String column : List.of("event_type", "actor_type", "reason_code")) {
            assertThatThrownBy(() -> sql.update("UPDATE attempt_trace_event SET " + column + "='private-secret' WHERE attempt_id=?", bytes(scope.attemptId())))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> sql.update("UPDATE attempt_trace_event SET schema_version=2 WHERE attempt_id=?", bytes(scope.attemptId()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE attempt_trace_event SET from_execution='CREATED' WHERE attempt_id=?", bytes(scope.attemptId()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx("READ_COMMITTED").executeWithoutResult(status -> repository.insert(new Stored(scope, 1, NOW, event("same-sequence")))))
                .isInstanceOf(DataAccessException.class);
        assertThat(page(scope)).containsExactly(original);
    }

    @ParameterizedTest
    @ValueSource(longs = {2, Long.MAX_VALUE})
    void corruptOrExhaustedCounterFailsWithoutWriting(long invalid) {
        var scope = fixture(); append(scope, event("counter"));
        sql.update("UPDATE attempt_trace_stream SET last_sequence=? WHERE attempt_id=?", invalid, bytes(scope.attemptId()));
        rejected(() -> append(scope, event("cannot-append")), CORRUPT_TRACE);
        assertThat(page(scope)).hasSize(1);
    }

    @Test
    void telemetryFailureDoesNotChangeBusinessOutcomeAndLabelsContainNoIdentifiers() {
        var scope = fixture(); append(scope, event("metrics")); append(scope, event("metrics"));
        var traceMeters = metrics.getMeters().stream().filter(meter -> meter.getId().getName().equals("attempt.trace.append")).toList();
        assertThat(traceMeters).hasSize(2);
        for (var meter : traceMeters) {
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("version", "type", "outcome");
                assertThat(tag.getValue()).doesNotContain(scope.attemptId().toString(), "private", "@", "answer-content");
            });
        }
        var broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("Synthetic telemetry failure"));
        var writer = service(repository, manager, broken, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(tx("READ_COMMITTED").execute(status -> writer.append(scope, event("broken-metrics"))).event().sequence()).isEqualTo(2);
    }

    private Object deadlockWorker(Scope locked, Scope next, int marker, String isolation, CyclicBarrier barrier) {
        try {
            return tx(isolation).execute(status -> {
                repository.lockStream(locked.attemptId()).orElseThrow();
                sql.update("UPDATE trace_business_fixture SET marker='worker' WHERE id=?", marker);
                barrier(barrier);
                return service.append(next, event("deadlock-" + marker));
            });
        } catch (TraceException failure) { return failure.reason(); }
    }

    private Object result(Scope scope, Event event, String isolation) {
        try { return tx(isolation).execute(status -> service.append(scope, event)); }
        catch (TraceException failure) { return failure.reason(); }
    }

    private Scope fixture() {
        String key = UUID.randomUUID().toString();
        var revision = revision(key, 1); var form = form(key + "-form", revision, 1); var attempt = id(key + "-attempt");
        return tx("READ_COMMITTED").execute(status -> {
            uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository revisions = proxy(new JdbcQuizRevisionRepository(named()), manager);
            revisions.insert(revision);
            new FixedFormServiceImpl(proxy(new JdbcFixedFormRepository(named()), manager), manager, metrics).createOrReplay(OWNER, form);
            var content = new ContentPolicy(revision.id(), 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
            var policy = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), metrics)
                    .resolve(new PolicyResolutionRequest(1, 1, "SHA-256", BuiltInPracticePreset.REFERENCE, content, List.of()));
            UUID snapshot = id(key + "-policy");
            new PolicySnapshotServiceImpl(proxy(new JdbcPolicySnapshotRepository(named()), manager), manager, metrics)
                    .createOrReplay(OWNER, new PolicySnapshot(snapshot, policy));
            var time = java.sql.Timestamp.from(NOW);
            UUID user = id(key + "-user");
            sql.update("INSERT INTO users (user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version) VALUES (?,?,?,'unused',TRUE,FALSE,FALSE,?,?,0)",
                    bytes(user), "trace-" + user, user + "@example.invalid", time, time);
            new AttemptSubjectServiceImpl(proxy(new JdbcAttemptSubjectRepository(named()), manager), manager, metrics)
                    .bindOrReplay(user, AttemptSubject.authenticated(attempt, user));
            new AttemptLifecycleServiceImpl(proxy(new JdbcLifecycleStateRepository(named()), manager), manager, Clock.fixed(NOW, ZoneOffset.UTC), metrics).initialize(attempt);
            return new Scope(attempt, snapshot, form.id());
        });
    }

    private static void barrier(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private Appended append(Scope scope, Event event) { return tx("READ_COMMITTED").execute(status -> service.append(scope, event)); }
    private List<Stored> page(Scope scope) { return tx("READ_COMMITTED").execute(status -> pageInTransaction(scope)); }
    private List<Stored> pageInTransaction(Scope scope) { return repository.page(scope.attemptId(), 0, 100); }
    private long tail(Scope scope) { return sql.queryForObject("SELECT last_sequence FROM attempt_trace_stream WHERE attempt_id=?", Long.class, bytes(scope.attemptId())); }
    private String marker(int id) { return sql.queryForObject("SELECT marker FROM trace_business_fixture WHERE id=?", String.class, id); }
    private static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(source); }
    private static TransactionTemplate tx(String isolation) {
        var template = new TransactionTemplate(manager); template.setIsolationLevelName("ISOLATION_" + isolation); template.setTimeout(20); return template;
    }
    private static ExecutionTraceService service(ExecutionTraceRepository repository, PlatformTransactionManager transactions, MeterRegistry metrics, Clock clock) {
        return new ExecutionTraceServiceImpl(repository, transactions, clock, metrics);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, PlatformTransactionManager transactions) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
