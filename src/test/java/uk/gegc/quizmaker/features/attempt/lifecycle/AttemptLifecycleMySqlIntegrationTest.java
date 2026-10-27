package uk.gegc.quizmaker.features.attempt.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptLifecycleService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptLifecycleServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.LifecycleStateRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcLifecycleStateRepository;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.lifecycle.LifecycleStateTest.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleStateException.Reason.*;

/** Real application service, repository, migrations and MySQL; the generated catalog is test-owned. */
@Tag("db-serial")
class AttemptLifecycleMySqlIntegrationTest {
    private static final List<String> CHECKS = List.of("chk_attempt_lifecycle_version", "chk_attempt_lifecycle_tuple", "chk_attempt_lifecycle_times");
    private static JdbcTemplate admin;
    private static String catalog;
    private static LifecycleDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager transactions;
    private static Map<String, Object> legacyDefinition;
    private SimpleMeterRegistry metrics;
    private AttemptLifecycleService service;
    private LifecycleStateRepository repository;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeAll
    static void migrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        String name = "quizmaker_528_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        catalog = name; // Set only after exclusive creation; never drop a pre-existing catalog.
        source = new LifecycleDataSource(new DriverManagerDataSource(base + name + options, user, password));
        sql = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("87").load().migrate();
        legacyDefinition = sql.queryForMap("SHOW CREATE TABLE attempts");
        sql.execute("CREATE TABLE lifecycle_legacy_fixture (id INT PRIMARY KEY, marker VARCHAR(50))");
        sql.update("INSERT INTO lifecycle_legacy_fixture VALUES (1,'legacy')");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='attempt_lifecycle_state'", Integer.class)).isZero();
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("88").load().migrate();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_lifecycle_state", Integer.class)).isZero();
        assertThat(sql.queryForMap("SHOW CREATE TABLE attempts")).isEqualTo(legacyDefinition);
        assertThat(sql.queryForObject("SELECT marker FROM lifecycle_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
    }

    @BeforeEach
    void prepare() {
        source.reset();
        sql.update("DELETE FROM attempt_lifecycle_state");
        sql.update("UPDATE lifecycle_legacy_fixture SET marker='legacy' WHERE id=1");
        assertThat(sql.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attempt_lifecycle_state' AND CONSTRAINT_TYPE='CHECK' AND ENFORCED='YES'", String.class))
                .containsExactlyInAnyOrderElementsOf(CHECKS);
        metrics = new SimpleMeterRegistry();
        repository = repository(transactions);
        service = service(transactions, metrics);
        source.reset();
    }

    @AfterEach
    void cleanup() {
        if (source != null) {
            source.reset();
            sql.update("DELETE FROM attempt_lifecycle_state");
        }
        if (metrics != null) metrics.close();
    }

    @AfterAll
    static void dropOwnedCatalog() {
        if (source != null) source.reset();
        if (catalog != null) admin.execute("DROP DATABASE " + catalog);
    }

    @Test
    void fullMigrationAddsOnlyAnEmptyTargetAuthorityAndPreservesLegacyStorage() {
        assertThat(sql.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("88");
        assertThat(sql.queryForMap("SHOW CREATE TABLE attempts")).isEqualTo(legacyDefinition);
        assertThat(count()).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attempt_lifecycle_state' AND REFERENCED_TABLE_NAME IS NOT NULL", Integer.class)).isZero();
        assertThat(sql.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attempt_lifecycle_state' ORDER BY ORDINAL_POSITION", String.class))
                .containsExactly("attempt_id", "lifecycle_version", "row_version", "execution_state", "answer_collection_state", "created_at", "updated_at", "started_at", "submitted_at");
    }

    @Test
    void exactStatesVersionsAndUtcMicrosecondsSurviveNewConnectionsAndServiceInstances() {
        now.set(NOW.plusNanos(789));
        assertThat(service.initialize(ID)).isEqualTo(LifecycleState.initial(ID, NOW));
        now.set(NOW.plusSeconds(1));
        var started = service.save(ID, 0, STARTED);
        now.set(NOW.plusSeconds(2));
        var collecting = service.save(ID, 1, COLLECTING);
        now.set(NOW.plusSeconds(3));
        var secondResponseActivity = service.save(ID, 2, COLLECTING);
        now.set(NOW.plusSeconds(4));
        var terminal = service.save(ID, 3, SUBMITTED);
        assertThat(terminal).isEqualTo(new LifecycleState(ID, 4, SUBMITTED, NOW, NOW.plusSeconds(4),
                NOW.plusSeconds(1), NOW.plusSeconds(4)));
        assertThat(service(new DataSourceTransactionManager(source), metrics).read(new LifecycleReference.Target(ID))).isEqualTo(terminal);
        assertThat(started.rowVersion()).isEqualTo(1);
        assertThat(collecting.rowVersion()).isEqualTo(2);
        assertThat(secondResponseActivity.rowVersion()).isEqualTo(3);
        rejected(() -> service.save(ID, 3, SUBMITTED), STATE_CONFLICT);
        rejected(() -> service.save(ID, 4, STARTED), INVALID_TRANSITION);
        rejected(() -> service.save(ID, 4, SUBMITTED), INVALID_TRANSITION);
        assertThat(read(ID)).isEqualTo(terminal);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void explicitLegacyUsesNoDatabaseAndMissingTargetNeverBecomesLegacyOrGetsCreated() {
        source.failConnection = true;
        assertThat(service.read(LifecycleReference.Legacy.NO_TARGET_STATE)).isEqualTo(LifecycleRead.Legacy.NO_TARGET_STATE);
        rejected(() -> service.read(new LifecycleReference.Target(ID)), PERSISTENCE_UNAVAILABLE);
        source.reset();
        rejected(() -> service.read(new LifecycleReference.Target(ID)), STATE_UNAVAILABLE);
        rejected(() -> service.save(ID, 0, STARTED), STATE_UNAVAILABLE);
        assertThat(count()).isZero();
        var initial = service.initialize(ID);
        assertThat(read(ID)).isEqualTo(initial);
        assertThat(service.read(LifecycleReference.Legacy.NO_TARGET_STATE)).isEqualTo(LifecycleRead.Legacy.NO_TARGET_STATE);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void invalidMissingAndDuplicateWritesRetainTheFirstRecordAndExposeNoRawDatabaseDetails() {
        rejected(() -> service.initialize(null), INVALID_INPUT);
        rejected(() -> service.read(null), INVALID_INPUT);
        rejected(() -> service.save(null, 0, STARTED), INVALID_INPUT);
        rejected(() -> service.save(ID, -1, STARTED), INVALID_INPUT);
        rejected(() -> service.save(ID, 0, null), INVALID_INPUT);
        assertThat(count()).isZero();
        var initial = service.initialize(ID);
        now.set(NOW.plusSeconds(100));
        rejected(() -> service.initialize(ID), STATE_CONFLICT);
        rejected(() -> service.save(ID, 0, SUBMITTED), INVALID_TRANSITION);
        rejected(() -> service.save(ID, 1, STARTED), STATE_CONFLICT);
        assertThat(read(ID)).isEqualTo(initial);
        now.set(NOW.minusSeconds(1));
        rejected(() -> service.save(ID, 0, STARTED), INVALID_INPUT);
        assertThat(read(ID)).isEqualTo(initial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"contract", "counter", "execution", "case", "trailing", "answers", "tuple", "time", "missing-start"})
    void corruptStoredMetadataFailsClosedForOrdinaryAndLockingReadsWithoutWrites(String corruption) {
        service.initialize(ID);
        var original = service.save(ID, 0, STARTED);
        for (String check : CHECKS) sql.execute("ALTER TABLE attempt_lifecycle_state ALTER CHECK " + check + " NOT ENFORCED");
        try {
            String assignment = switch (corruption) {
                case "contract" -> "lifecycle_version=2";
                case "counter" -> "row_version=-1";
                case "execution" -> "execution_state='PRIVATE_UNKNOWN'";
                case "case" -> "execution_state='in_progress'";
                case "trailing" -> "execution_state='IN_PROGRESS '";
                case "answers" -> "answer_collection_state='PRIVATE_UNKNOWN'";
                case "tuple" -> "answer_collection_state='COMPLETE'";
                case "time" -> "updated_at='1000-01-01 00:00:00'";
                case "missing-start" -> "started_at=NULL";
                default -> throw new AssertionError(corruption);
            };
            sql.update("UPDATE attempt_lifecycle_state SET " + assignment + " WHERE attempt_id=?", bytes(ID));
            var before = sql.queryForMap("SELECT * FROM attempt_lifecycle_state WHERE attempt_id=?", bytes(ID));
            var reason = corruption.equals("contract") ? UNSUPPORTED_VERSION : CORRUPT_STATE;
            rejected(() -> read(ID), reason);
            rejected(() -> service.save(ID, 1, COLLECTING), reason);
            rejected(() -> tx(() -> service.lock(ID)), reason);
            assertThat(sql.queryForMap("SELECT * FROM attempt_lifecycle_state WHERE attempt_id=?", bytes(ID)))
                    .usingRecursiveComparison().isEqualTo(before);
        } finally {
            sql.update("DELETE FROM attempt_lifecycle_state WHERE attempt_id=?", bytes(ID));
            for (String check : CHECKS) sql.execute("ALTER TABLE attempt_lifecycle_state ALTER CHECK " + check + " ENFORCED");
            tx(() -> { repository.insert(original); return null; });
        }
        assertThat(read(ID)).isEqualTo(original);
    }

    @Test
    void mysqlRejectsUnknownVersionsInvalidTuplesTimesAndCaseOrWhitespaceVariants() {
        var initial = service.initialize(ID);
        for (String assignment : List.of("lifecycle_version=2", "row_version=-1", "execution_state='created'",
                "execution_state='CREATED '", "answer_collection_state='NOT_STARTED '",
                "execution_state='SUBMITTED'", "answer_collection_state='COMPLETE'", "started_at=created_at",
                "submitted_at=created_at", "updated_at='1000-01-01 00:00:00'")) {
            violatesCheck(() -> sql.update("UPDATE attempt_lifecycle_state SET " + assignment + " WHERE attempt_id=?", bytes(ID)));
            assertThat(read(ID)).isEqualTo(initial);
        }
        var started = service.save(ID, 0, STARTED);
        for (String assignment : List.of("started_at=NULL", "started_at='9999-12-31 00:00:00'", "submitted_at=created_at")) {
            violatesCheck(() -> sql.update("UPDATE attempt_lifecycle_state SET " + assignment + " WHERE attempt_id=?", bytes(ID)));
            assertThat(read(ID)).isEqualTo(started);
        }
        assertThat(service.save(ID, 1, SUBMITTED).state()).isEqualTo(SUBMITTED);
    }

    @Test
    void repositoryCompareAndSetRejectsStaleVersionsAndCannotCreateMissingRows() {
        var initial = service.initialize(ID);
        var next = initial.advance(STARTED, NOW);
        assertThat(tx(() -> repository.compareAndSet(0, next))).isTrue();
        assertThat(tx(() -> repository.compareAndSet(0, next))).isFalse();
        var missing = LifecycleState.initial(UUID.randomUUID(), NOW).advance(STARTED, NOW);
        assertThat(tx(() -> repository.compareAndSet(0, missing))).isFalse();
        assertThat(read(ID)).isEqualTo(next);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void concurrentStaleSavesHaveOneSuccessfulWinnerEvenAfterBothReadTheOldVersion(String isolation) throws Exception {
        service.initialize(ID);
        var gate = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> command = () -> outcome(ID, () -> transaction(isolation).execute(status -> {
                assertThat(read(ID).rowVersion()).isZero();
                await(gate);
                return service.save(ID, 0, STARTED);
            }));
            var first = pool.submit(command);
            var second = pool.submit(command);
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).filteredOn(result -> result.failure() == null).hasSize(1);
            assertThat(results).as("outcomes=%s, SQL codes=%s", results, source.sqlErrorCodes)
                    .filteredOn(result -> result.failure() == STATE_CONFLICT).hasSize(1);
            assertThat(read(ID).rowVersion()).isEqualTo(1);
            assertThat(read(ID).state()).isEqualTo(STARTED);
        } finally { shutdown(pool); }
    }

    @RepeatedTest(20)
    void concurrentInitializationHasOneSuccessfulCreatorAndNeverOverwritesItsMetadata() throws Exception {
        var gate = new CyclicBarrier(2);
        var inserts = new AtomicInteger();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO attempt_lifecycle_state")) {
                inserts.incrementAndGet();
                await(gate);
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> outcome(ID, () -> service.initialize(ID)));
            var second = pool.submit(() -> outcome(ID, () -> service.initialize(ID)));
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).filteredOn(result -> result.failure() == null).hasSize(1);
            // InnoDB may choose a deadlock victim during competing first inserts. It remains
            // a typed database failure, not a duplicate-key conflict or an automatic retry.
            var expected = source.sqlErrorCodes.contains(1213) ? PERSISTENCE_UNAVAILABLE : STATE_CONFLICT;
            assertThat(results).as("outcomes=%s, SQL codes=%s", results, source.sqlErrorCodes)
                    .filteredOn(result -> result.failure() == expected).hasSize(1);
            assertThat(source.sqlErrorCodes).containsExactly(expected == STATE_CONFLICT ? 1062 : 1213);
            assertThat(inserts).hasValue(2); // Neither worker silently retries.
            assertThat(read(ID)).isEqualTo(LifecycleState.initial(ID, NOW));
            assertThat(count()).isEqualTo(1);
            source.reset();
            var restarted = service(new DataSourceTransactionManager(source), metrics);
            assertThat(restarted.read(new LifecycleReference.Target(ID))).isEqualTo(LifecycleState.initial(ID, NOW));
            rejected(() -> restarted.initialize(ID), STATE_CONFLICT);
            assertThat(read(ID)).isEqualTo(LifecycleState.initial(ID, NOW));
            assertThat(count()).isEqualTo(1);
        } finally { shutdown(pool); source.reset(); }
    }

    @Test
    void lockRequiresACallerTransactionAndHoldsItsRowUntilTheCallerCommits() throws Exception {
        service.initialize(ID);
        rejected(() -> service.lock(ID), PERSISTENCE_UNAVAILABLE);
        rejected(() -> tx(() -> service.lock(null)), INVALID_INPUT);
        rejected(() -> tx(() -> service.lock(UUID.randomUUID())), STATE_UNAVAILABLE);
        var attempting = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<LifecycleState> competing = tx(() -> {
                assertThat(service.lock(ID).rowVersion()).isZero();
                source.beforeSql = (statement, connection) -> {
                    if (Thread.currentThread().getName().startsWith("pool-") && statement.endsWith("FOR UPDATE")) attempting.countDown();
                };
                var future = pool.submit(() -> service.save(ID, 0, STARTED));
                await(attempting);
                assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                return future;
            });
            assertThat(competing.get(15, TimeUnit.SECONDS).rowVersion()).isEqualTo(1);
        } finally { shutdown(pool); source.reset(); }
    }

    @Test
    void currentLockingReadAllowsAValidSaveAfterAnOlderRepeatableReadSnapshot() throws Exception {
        service.initialize(ID);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var updated = transaction("REPEATABLE_READ").execute(status -> {
                assertThat(read(ID).rowVersion()).isZero();
                try { assertThat(pool.submit(() -> service.save(ID, 0, STARTED)).get(15, TimeUnit.SECONDS).rowVersion()).isEqualTo(1); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(read(ID).rowVersion()).isZero(); // Ordinary read still honors the original snapshot.
                return service.save(ID, 1, COLLECTING); // Current FOR UPDATE must see version one.
            });
            assertThat(updated.rowVersion()).isEqualTo(2);
            assertThat(read(ID)).isEqualTo(updated);
        } finally { shutdown(pool); }
    }

    @Test
    void failureAfterTheActualUpdateAndCaughtServiceFailureRollBackTheWholeCallerTransaction() {
        var original = service.initialize(ID);
        var reached = new AtomicLong(-1);
        source.afterSql = (statement, connection) -> {
            if (statement.startsWith("UPDATE attempt_lifecycle_state")) {
                reached.set(sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state WHERE attempt_id=?", Long.class, bytes(ID)));
                throw new SQLException("Synthetic reached post-update failure", "08006");
            }
        };
        assertThatThrownBy(() -> tx(() -> {
            sql.update("UPDATE lifecycle_legacy_fixture SET marker='partial' WHERE id=1");
            rejected(() -> service.save(ID, 0, STARTED), PERSISTENCE_UNAVAILABLE);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        source.reset();
        assertThat(reached).hasValue(1);
        assertThat(read(ID)).isEqualTo(original);
        assertThat(sql.queryForObject("SELECT marker FROM lifecycle_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
        assertThat(service.save(ID, 0, STARTED).rowVersion()).isEqualTo(1);
    }

    @Test
    void initializationAndSuccessfulSaveStillJoinAnOuterRollback() {
        tx(() -> {
            var value = service.initialize(ID);
            assertThat(read(ID)).isEqualTo(value);
            return null;
        });
        var original = read(ID);
        var other = UUID.randomUUID();
        transaction("READ_COMMITTED").executeWithoutResult(status -> {
            var created = service.initialize(other);
            assertThat(read(other)).isEqualTo(created);
            assertThat(service.save(ID, 0, STARTED).rowVersion()).isEqualTo(1);
            assertThat(read(ID).state()).isEqualTo(STARTED);
            status.setRollbackOnly();
        });
        assertThat(read(ID)).isEqualTo(original);
        rejected(() -> read(other), STATE_UNAVAILABLE);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailureIsReconciledFromFreshStateWithoutBlindReplayOrExtraVersionIncrement(boolean committed) {
        service.initialize(ID);
        var previous = service.save(ID, 0, STARTED);
        var once = new AtomicBoolean(true);
        var reached = new AtomicLong(-1);
        LifecycleDataSource.CommitHook hook = connection -> {
            if (once.getAndSet(false)) {
                try (var query = connection.prepareStatement("SELECT row_version FROM attempt_lifecycle_state"); var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    reached.set(rows.getLong(1));
                }
                if (!committed) connection.rollback();
                throw new SQLException("Synthetic commit acknowledgement failure", "08006");
            }
        };
        if (committed) source.afterCommit = hook; else source.beforeCommit = hook;
        rejected(() -> service.save(ID, 1, COLLECTING), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(reached).hasValue(2);
        var restarted = service(new DataSourceTransactionManager(source), metrics);
        var restored = (LifecycleState) restarted.read(new LifecycleReference.Target(ID));
        if (committed) {
            assertThat(restored.rowVersion()).isEqualTo(2);
            assertThat(restored.state()).isEqualTo(COLLECTING);
            rejected(() -> restarted.save(ID, 1, COLLECTING), STATE_CONFLICT);
        } else {
            assertThat(restored).isEqualTo(previous);
            assertThat(restarted.save(ID, 1, COLLECTING).rowVersion()).isEqualTo(2);
        }
        assertThat(read(ID).rowVersion()).isEqualTo(2);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void realDeadlockRollsBackOneWriterAndExplicitRetryUpdatesOnlyItsUncommittedState() throws Exception {
        UUID other = UUID.randomUUID();
        service.initialize(ID); service.initialize(other);
        var gate = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> deadlockCommand(ID, other, gate));
            var second = pool.submit(() -> deadlockCommand(other, ID, gate));
            var results = List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
            assertThat(results).filteredOn(result -> result.failure() == null).hasSize(1);
            assertThat(results).filteredOn(result -> result.failure() == PERSISTENCE_UNAVAILABLE).hasSize(1);
            assertThat(source.sqlErrorCodes).contains(1213);
            var winner = results.stream().filter(result -> result.failure() == null).findFirst().orElseThrow();
            var loser = results.stream().filter(result -> result.failure() != null).findFirst().orElseThrow();
            assertThat(read(winner.id()).rowVersion()).isEqualTo(1);
            assertThat(read(loser.id())).isEqualTo(LifecycleState.initial(loser.id(), NOW));
            var restarted = service(new DataSourceTransactionManager(source), metrics);
            assertThat(restarted.save(loser.id(), 0, STARTED).rowVersion()).isEqualTo(1);
            assertThat(read(winner.id()).rowVersion()).isEqualTo(1);
            assertThat(read(loser.id()).state()).isEqualTo(STARTED);
            assertThat(count()).isEqualTo(2);
        } finally { shutdown(pool); }
    }

    @Test
    void jdbcWritesAndRootLocksJoinRealJpaTransactions() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.attempt.lifecycle");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); factory.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(factory.getObject()); manager.setDataSource(source);
            var jpaService = service(manager, metrics);
            var tx = new TransactionTemplate(manager);
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(factory.getObject())
                        .createNativeQuery("UPDATE lifecycle_legacy_fixture SET marker='committed' WHERE id=1").executeUpdate();
                jpaService.initialize(ID);
                assertThat(jpaService.lock(ID).rowVersion()).isZero();
                jpaService.save(ID, 0, STARTED);
            });
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(factory.getObject())
                        .createNativeQuery("UPDATE lifecycle_legacy_fixture SET marker='rolled back' WHERE id=1").executeUpdate();
                jpaService.save(ID, 1, COLLECTING);
                status.setRollbackOnly();
            });
            assertThat(read(ID).state()).isEqualTo(STARTED);
            assertThat(read(ID).rowVersion()).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT marker FROM lifecycle_legacy_fixture WHERE id=1", String.class)).isEqualTo("committed");
        } finally { factory.destroy(); }
    }

    @Test
    void singleRootReadsHaveOneIndexedQueryRegardlessOfOtherRootsAndNoRelationships() {
        for (int i = 0; i < 25; i++) service.initialize(UUID.randomUUID());
        service.initialize(ID);
        source.reset();
        assertThat(read(ID).attemptId()).isEqualTo(ID);
        assertThat(source.selects).hasSize(1);
        source.reset();
        tx(() -> service.lock(ID));
        assertThat(source.selects).hasSize(1);
        assertThat(source.selects.get(0)).endsWith("FOR UPDATE");
        var plan = sql.queryForMap("EXPLAIN SELECT * FROM attempt_lifecycle_state WHERE attempt_id=?", bytes(ID));
        assertThat(plan.get("key")).isEqualTo("PRIMARY");
        assertThat(plan.get("type")).isEqualTo("const");
    }

    @Test
    void outagesAndMetricsHaveBoundedErrorsAndTelemetryCannotChangeWrites() {
        source.failConnection = true;
        rejected(() -> service.initialize(ID), PERSISTENCE_UNAVAILABLE);
        source.reset();
        service.initialize(ID);
        rejected(() -> service.initialize(ID), STATE_CONFLICT);
        service.read(LifecycleReference.Legacy.NO_TARGET_STATE);
        read(ID);
        rejected(() -> service.save(ID, 42, STARTED), STATE_CONFLICT);
        for (var meter : metrics.getMeters()) {
            assertThat(meter.getId().getName()).isEqualTo("attempt.lifecycle.storage");
            assertThat(meter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                    .containsExactlyInAnyOrder("version", "operation", "outcome");
            assertThat(meter.getId().getTags().toString()).doesNotContain(ID.toString(), "private", "08006");
        }
        var broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("Synthetic telemetry failure"));
        assertThat(service(transactions, broken).save(ID, 0, STARTED).rowVersion()).isEqualTo(1);
        assertThat(read(ID).state()).isEqualTo(STARTED);
    }

    private Outcome deadlockCommand(UUID own, UUID other, CyclicBarrier gate) {
        return outcome(own, () -> transaction("REPEATABLE_READ").execute(status -> {
            var value = service.save(own, 0, STARTED);
            await(gate);
            service.lock(other);
            return value;
        }));
    }

    private static Outcome outcome(UUID id, Supplier<LifecycleState> action) {
        try { action.get(); return new Outcome(id, null); }
        catch (LifecycleStateException failure) { return new Outcome(id, failure.reason()); }
    }

    private static void violatesCheck(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class,
                failure -> assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getErrorCode()).isEqualTo(3819)));
    }

    private LifecycleState read(UUID id) { return (LifecycleState) service.read(new LifecycleReference.Target(id)); }
    private static int count() { return sql.queryForObject("SELECT COUNT(*) FROM attempt_lifecycle_state", Integer.class); }
    private static <T> T tx(Supplier<T> action) { return transaction("READ_COMMITTED").execute(status -> action.get()); }

    private static TransactionTemplate transaction(String isolation) {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(isolation.equals("READ_COMMITTED") ? TransactionDefinition.ISOLATION_READ_COMMITTED
                : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(20);
        return tx;
    }

    private AttemptLifecycleService service(PlatformTransactionManager manager, MeterRegistry registry) {
        return new AttemptLifecycleServiceImpl(repository(manager), manager, clock, registry);
    }

    private static LifecycleStateRepository repository(PlatformTransactionManager manager) {
        var factory = new ProxyFactory(new JdbcLifecycleStateRepository(new NamedParameterJdbcTemplate(source)));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (LifecycleStateRepository) factory.getProxy();
    }

    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("Timed out waiting for real transaction race", failure); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private record Outcome(UUID id, LifecycleStateException.Reason failure) { }
}
