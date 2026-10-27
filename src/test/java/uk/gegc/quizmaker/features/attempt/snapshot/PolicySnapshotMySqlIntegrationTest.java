package uk.gegc.quizmaker.features.attempt.snapshot;

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
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.PolicySnapshotService;
import uk.gegc.quizmaker.features.attempt.application.impl.PolicySnapshotServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.PolicySnapshotRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcPolicySnapshotRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.snapshot.SnapshotFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException.Reason.*;

/** Real Flyway, repository and application boundary, with committed fixtures and separate transactions. */
@Tag("db-serial")
class PolicySnapshotMySqlIntegrationTest {
    private static final String IDENTITY_HISTORY = "flyway_snapshot_identity_history";
    private static final String SNAPSHOT_HISTORY = "flyway_policy_snapshot_history";
    private static SnapshotDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager transactions;
    private static QuizRevisionRepository revisions;
    private static List<Map<String, Object>> snapshotChecks;
    private SimpleMeterRegistry metrics;
    private PolicySnapshotService service;

    @BeforeAll
    static void connect() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        source = new SnapshotDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser")));
        sql = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        revisions = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)), transactions);
        dropSchema();
        sql.execute("CREATE TABLE snapshot_legacy_fixture (id INT PRIMARY KEY, marker VARCHAR(50))");
        sql.update("INSERT INTO snapshot_legacy_fixture VALUES (1,'legacy')");
        Flyway.configure().dataSource(source).table(IDENTITY_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("80").target("82").load().migrate();
        Flyway.configure().dataSource(source).table(SNAPSHOT_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("86").target("87").load().migrate();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_policy_snapshot", Integer.class)).isZero();
        snapshotChecks = checkConstraints();
        // Verify the migration preserved legacy data before any per-scenario reset can repair it.
        assertThat(sql.queryForObject("SELECT marker FROM snapshot_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
        source.reset();
    }

    @BeforeEach
    void prepareScenario() {
        resetData();
        metrics = new SimpleMeterRegistry();
        service = service(transactions);
    }

    @AfterEach
    void cleanupScenario() {
        try { resetData(); }
        finally { if (metrics != null) metrics.close(); }
    }

    private static void resetData() {
        source.reset(); // Clear fault hooks before cleanup opens a connection or executes SQL.
        for (String table : List.of("attempt_policy_snapshot", "quiz_revision_items", "question_option_revisions",
                "quiz_revisions", "question_revisions")) {
            sql.update("DELETE FROM " + table);
        }
        sql.update("UPDATE snapshot_legacy_fixture SET marker='legacy' WHERE id=1");
        assertThat(sql.queryForObject("SELECT marker FROM snapshot_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
        assertThat(checkConstraints())
                .as("Corruption scenarios must restore every migrated CHECK constraint and its enforcement")
                .isEqualTo(snapshotChecks);
        source.reset(); // Keep fixture work outside the measured SQL/transaction interval.
    }

    private static List<Map<String, Object>> checkConstraints() {
        // ALTER CHECK can normalize SHOW CREATE TABLE text; compare actual enforcement metadata.
        return sql.queryForList("""
                SELECT CONSTRAINT_NAME, ENFORCED FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attempt_policy_snapshot'
                  AND CONSTRAINT_TYPE = 'CHECK' ORDER BY CONSTRAINT_NAME
                """);
    }

    @AfterAll
    static void dropSchema() {
        if (source == null) return;
        source.reset();
        for (String table : List.of("attempt_policy_snapshot", "attempt_form_item", "attempt_form", "question_revision_contract_bindings", "quiz_revision_items", "question_option_revisions",
                "quiz_revisions", "question_revisions", IDENTITY_HISTORY, SNAPSHOT_HISTORY, "snapshot_legacy_fixture")) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void fixtureCleanupRemovesCommittedSnapshotAndRestoresLegacyMarkerAndHooks() {
        var history = sql.queryForList("SELECT * FROM " + SNAPSHOT_HISTORY + " ORDER BY installed_rank");
        var definition = sql.queryForMap("SHOW CREATE TABLE attempt_policy_snapshot");
        var revision = savedRevision("cleanup", OWNER);
        var value = snapshot("cleanup-snapshot", revision, true);
        service.createOrReplay(OWNER, value);
        sql.update("UPDATE snapshot_legacy_fixture SET marker='committed' WHERE id=1");
        source.failConnection = true;
        source.beforeSql = (statement, connection) -> { throw new SQLException("Stale fixture hook"); };

        resetData();

        assertThat(source.selects).isEmpty();
        assertThat(source.sqlErrorCodes).isEmpty();
        rejected(() -> service.read(OWNER, new PolicySnapshotReference.Stored(value.id())), SNAPSHOT_UNAVAILABLE);
        assertThat(revisions.findById(OWNER, revision)).isEmpty();
        assertThat(count()).isZero();
        assertThat(sql.queryForObject("SELECT marker FROM snapshot_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
        assertThat(sql.queryForList("SELECT * FROM " + SNAPSHOT_HISTORY + " ORDER BY installed_rank")).isEqualTo(history);
        assertThat(sql.queryForMap("SHOW CREATE TABLE attempt_policy_snapshot")).isEqualTo(definition);
        var next = savedRevision("cleanup", OWNER);
        var replacement = snapshot("cleanup-snapshot", next, false);
        assertThat(service.createOrReplay(OWNER, replacement)).isEqualTo(replacement);
    }

    @Test
    void additiveMigrationAndExplicitLegacyReadNeverFabricateHistory() {
        assertThat(count()).isZero();
        assertThat(sql.queryForObject("SELECT marker FROM snapshot_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + SNAPSHOT_HISTORY + " WHERE version='87' AND success=1", Integer.class)).isEqualTo(1);
        source.selects.clear();
        assertThat(service.read(OWNER, PolicySnapshotReference.Legacy.NO_SNAPSHOT)).isEqualTo(PolicySnapshotRead.Legacy.NO_SNAPSHOT);
        assertThat(source.selects).isEmpty();
        rejected(() -> service.read(OWNER, new PolicySnapshotReference.Stored(id("missing"))), SNAPSHOT_UNAVAILABLE);
        assertThat(count()).isZero();
    }

    @Test
    void committedSnapshotAndProvenanceSurviveExactReplayFreshTransactionsAndRestart() {
        var revision = savedRevision("retained", OWNER);
        var value = snapshot("retained-snapshot", revision, false);
        assertThat(service.createOrReplay(OWNER, value)).isEqualTo(value);
        assertThat(service.createOrReplay(OWNER, value)).isEqualTo(value);
        assertThat(service(transactions).read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
        assertThat(sql.queryForObject("SELECT canonical_json FROM attempt_policy_snapshot", String.class)).isEqualTo(value.resolution().canonicalJson());
        assertThat(count()).isEqualTo(1);
        rejected(() -> service.createOrReplay(OWNER, snapshot("retained-snapshot", revision, true)), SNAPSHOT_CONFLICT);
        assertThat(service.read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
    }

    @Test
    void revisionScopeAndMissingIdentityDenyWithoutDisclosureOrWrites() {
        var revision = savedRevision("scope", OWNER);
        var value = snapshot("scope-snapshot", revision, true);
        rejected(() -> service.createOrReplay(OTHER_OWNER, value), SNAPSHOT_UNAVAILABLE);
        rejected(() -> service.createOrReplay(null, value), SNAPSHOT_UNAVAILABLE);
        rejected(() -> service.createOrReplay(OWNER, snapshot("missing-revision", id("absent"), true)), SNAPSHOT_UNAVAILABLE);
        assertThat(count()).isZero();
        service.createOrReplay(OWNER, value);
        rejected(() -> service.read(OTHER_OWNER, new PolicySnapshotReference.Stored(value.id())), SNAPSHOT_UNAVAILABLE);
        rejected(() -> service.read(null, new PolicySnapshotReference.Stored(value.id())), SNAPSHOT_UNAVAILABLE);
        var otherRevision = savedRevision("foreign", OTHER_OWNER);
        rejected(() -> service.createOrReplay(OTHER_OWNER, snapshot("scope-snapshot", otherRevision, false)), SNAPSHOT_UNAVAILABLE);
        rejected(() -> service.createOrReplay(OWNER, null), INVALID_INPUT);
        rejected(() -> service.read(OWNER, null), INVALID_INPUT);
        assertThat(service.read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void concurrentIdenticalFirstWritesReturnOneDurableSnapshot(String isolation) throws Exception {
        var revision = savedRevision("identical", OWNER);
        var value = snapshot("identical-snapshot", revision, true);
        var outcomes = race(value, value, isolation);
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.failure()).isNull(); assertThat(outcome.value()).isEqualTo(value);
        });
        assertThat(count()).isEqualTo(1);
        assertThat(service(transactions).read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void competingDifferentPayloadsKeepOneImmutableWinnerAndTypedConflict(String isolation) throws Exception {
        var revision = savedRevision("different", OWNER);
        var first = snapshot("different-snapshot", revision, true);
        var second = snapshot("different-snapshot", revision, false);
        var outcomes = race(first, second, isolation);
        var winners = outcomes.stream().filter(outcome -> outcome.failure() == null).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null)).extracting(Outcome::failure).containsExactly(SNAPSHOT_CONFLICT);
        var winner = winners.get(0).value();
        var loser = winner.equals(first) ? second : first;
        assertThat(service.createOrReplay(OWNER, winner)).isEqualTo(winner);
        rejected(() -> service.createOrReplay(OWNER, loser), SNAPSHOT_CONFLICT);
        assertThat(service(transactions).read(OWNER, new PolicySnapshotReference.Stored(first.id()))).isEqualTo(winner);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,true", "READ_COMMITTED,false", "REPEATABLE_READ,true", "REPEATABLE_READ,false"})
    void independentSnapshotIdsBothCommitWithoutUpgradingTheSharedRevisionLock(String isolation, boolean sameRevision) throws Exception {
        var revision = savedRevision("independent-first-revision", OWNER);
        var otherRevision = sameRevision ? revision : savedRevision("independent-second-revision", OWNER);
        var first = snapshot("independent-first-snapshot", revision, true);
        var second = snapshot("independent-second-snapshot", otherRevision, false);
        assertThat(first.id()).isNotEqualTo(second.id());
        var bothInserted = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Integer>newKeySet();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT s.*") && statement.contains("FOR UPDATE")) {
                // Each real INSERT has returned, retaining its FK shared lock until commit.
                // Release both transactions together immediately before the locking read.
                connections.add(connection);
                try { bothInserted.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Both real inserts must finish before locking reads", failure); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        List<Outcome> outcomes;
        List<Integer> sqlErrors;
        try {
            var one = pool.submit(() -> createIndependent(first, isolation));
            var two = pool.submit(() -> createIndependent(second, isolation));
            outcomes = List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
            sqlErrors = List.copyOf(source.sqlErrorCodes);
            assertThat(connections).as("two independently inserted snapshots reached the barrier").hasSize(2);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); source.reset(); }
        assertThat(outcomes).extracting(Outcome::failure)
                .as("independent snapshot creation; SQL error codes: %s", sqlErrors).containsExactly(null, null);
        assertThat(outcomes).extracting(Outcome::value).containsExactly(first, second);
        assertThat(sqlErrors).as("no deadlock or other SQL error").isEmpty();
        assertThat(count()).isEqualTo(2);
        var restarted = service(transactions);
        assertThat(restarted.read(OWNER, new PolicySnapshotReference.Stored(first.id()))).isEqualTo(first);
        assertThat(restarted.read(OWNER, new PolicySnapshotReference.Stored(second.id()))).isEqualTo(second);
        assertThat(restarted.createOrReplay(OWNER, first)).isEqualTo(first);
        assertThat(restarted.createOrReplay(OWNER, second)).isEqualTo(second);
        assertThat(count()).isEqualTo(2);
    }

    private Outcome createIndependent(PolicySnapshot value, String isolation) {
        try { return new Outcome(transaction(isolation).execute(status -> service.createOrReplay(OWNER, value)), null); }
        catch (PolicySnapshotException failure) { return new Outcome(value, failure.reason()); }
    }

    private List<Outcome> race(PolicySnapshot first, PolicySnapshot second, String isolation) throws Exception {
        var bothRead = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Integer>newKeySet();
        source.beforeSql = (statement, connection) -> { if (statement.startsWith("SELECT s.*")) connections.add(connection); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> compete(first, isolation, bothRead));
            var two = pool.submit(() -> compete(second, isolation, bothRead));
            var result = List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
            assertThat(connections).hasSize(2);
            assertThat(source.sqlErrorCodes).isEmpty();
            return result;
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); source.reset(); }
    }

    private Outcome compete(PolicySnapshot value, String isolation, CyclicBarrier barrier) {
        try {
            return new Outcome(transaction(isolation).execute(status -> {
                // Establish an old consistent snapshot without marking the real transaction rollback-only.
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_policy_snapshot", Integer.class)).isZero();
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Both transactions must read before writing", failure); }
                return service.createOrReplay(OWNER, value);
            }), null);
        } catch (PolicySnapshotException failure) { return new Outcome(value, failure.reason()); }
    }

    private record Outcome(PolicySnapshot value, PolicySnapshotException.Reason failure) { }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void ownWriteIsVisibleButOuterRollbackRemovesIt(String isolation) {
        var value = snapshot("outer", savedRevision("outer-revision", OWNER), true);
        transaction(isolation).executeWithoutResult(status -> {
            assertThat(service.createOrReplay(OWNER, value)).isEqualTo(value);
            assertThat(service.read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
        rejected(() -> service.read(OWNER, new PolicySnapshotReference.Stored(value.id())), SNAPSHOT_UNAVAILABLE);
    }

    @Test
    void failureAfterRealInsertRollsBackAndSuccessfulRetryUsesTheSameIdentity() {
        var value = snapshot("rollback", savedRevision("rollback-revision", OWNER), true);
        var reached = new AtomicInteger();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT s.*") && statement.contains("FOR UPDATE")) {
                reached.incrementAndGet();
                throw new SQLException("Synthetic post-insert fault with private-content-must-not-leak", "08006");
            }
        };
        rejected(() -> service.createOrReplay(OWNER, value), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(reached).hasValue(1);
        assertThat(count()).isZero();
        assertThat(service(transactions).createOrReplay(OWNER, value)).isEqualTo(value);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailureAndLostAcknowledgementReconcileBeforeRetry(boolean committed) {
        var value = snapshot("commit", savedRevision("commit-revision", OWNER), true);
        var first = new AtomicBoolean(true);
        var reached = new AtomicInteger();
        SnapshotDataSource.CommitHook hook = connection -> {
            if (first.getAndSet(false)) {
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM attempt_policy_snapshot"); var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue(); reached.set(rows.getInt(1));
                }
                if (!committed) connection.rollback();
                throw new SQLException("Synthetic commit acknowledgement failure", "08006");
            }
        };
        if (committed) source.afterCommit = hook; else source.beforeCommit = hook;
        rejected(() -> service.createOrReplay(OWNER, value), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(reached).hasValue(1);
        assertThat(count()).isEqualTo(committed ? 1 : 0);
        assertThat(service(transactions).createOrReplay(OWNER, value)).isEqualTo(value);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void corruptHashAndMismatchedRevisionFailClosedOnReadAndReplay() {
        var revision = savedRevision("corrupt", OWNER);
        var value = snapshot("corrupt-snapshot", revision, true);
        service.createOrReplay(OWNER, value);
        sql.update("UPDATE attempt_policy_snapshot SET policy_hash=? WHERE id=?", "0".repeat(64), bytes(value.id()));
        rejected(() -> service.read(OWNER, new PolicySnapshotReference.Stored(value.id())), CORRUPT_SNAPSHOT);
        rejected(() -> service.createOrReplay(OWNER, value), CORRUPT_SNAPSHOT);
        sql.update("UPDATE attempt_policy_snapshot SET policy_hash=?,quiz_revision_id=? WHERE id=?",
                value.resolution().hash(), bytes(savedRevision("other-revision", OWNER)), bytes(value.id()));
        rejected(() -> service.read(OWNER, new PolicySnapshotReference.Stored(value.id())), CORRUPT_SNAPSHOT);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void migrationConstraintsProtectVersionsAlgorithmsHashShapeAndRetainedRevision() {
        var revision = savedRevision("constraints", OWNER);
        var value = snapshot("constraints-snapshot", revision, true);
        service.createOrReplay(OWNER, value);
        for (String assignment : List.of("schema_version=2", "resolver_version=2", "canonical_version=2", "hash_algorithm='sha-256'",
                "policy_hash='invalid'", "canonical_json='[]'", "canonical_json='invalid'")) {
            assertThatThrownBy(() -> sql.update("UPDATE attempt_policy_snapshot SET " + assignment + " WHERE id=?", bytes(value.id())))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> sql.update("DELETE FROM quiz_revisions WHERE id=?", bytes(revision))).isInstanceOf(DataAccessException.class);
        assertThat(service.read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
        var plan = sql.queryForMap("EXPLAIN SELECT * FROM attempt_policy_snapshot WHERE id=?", bytes(value.id()));
        assertThat(plan.get("key")).isEqualTo("PRIMARY");
        assertThat(plan.get("type")).isEqualTo("const");
        var revisionPlan = sql.queryForMap("EXPLAIN SELECT * FROM attempt_policy_snapshot WHERE quiz_revision_id=?", bytes(revision));
        assertThat(revisionPlan.get("possible_keys").toString()).contains("ix_policy_snapshot_revision");
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema_version", "resolver_version", "canonical_version", "hash_algorithm"})
    void corruptedStoredVersionFailsWithoutTryingToRepairIt(String column) {
        var value = snapshot("unknown", savedRevision("unknown-revision", OWNER), true);
        service.createOrReplay(OWNER, value);
        // Temporarily admit corrupt storage without replacing the migration's CHECK definition.
        boolean algorithm = column.equals("hash_algorithm");
        String constraint = algorithm ? "chk_policy_snapshot_algorithm" : "chk_policy_snapshot_version";
        Object original = sql.queryForObject("SELECT " + column + " FROM attempt_policy_snapshot WHERE id=?", Object.class, bytes(value.id()));
        sql.execute("ALTER TABLE attempt_policy_snapshot ALTER CHECK " + constraint + " NOT ENFORCED");
        try {
            Object invalid = algorithm ? "MD5" : 2;
            sql.update("UPDATE attempt_policy_snapshot SET " + column + "=? WHERE id=?", invalid, bytes(value.id()));
            var reason = algorithm ? UNSUPPORTED_HASH_ALGORITHM : UNSUPPORTED_VERSION;
            rejected(() -> service(transactions).read(OWNER, new PolicySnapshotReference.Stored(value.id())), reason);
            rejected(() -> service.createOrReplay(OWNER, value), reason);
            assertThat(sql.queryForObject("SELECT " + column + " FROM attempt_policy_snapshot", Object.class).toString()).isEqualTo(invalid.toString());
        } finally {
            try { sql.update("UPDATE attempt_policy_snapshot SET " + column + "=? WHERE id=?", original, bytes(value.id())); }
            finally { sql.execute("ALTER TABLE attempt_policy_snapshot ALTER CHECK " + constraint + " ENFORCED"); }
        }
    }

    @Test
    void jdbcSnapshotAndJpaWritesShareCommitAndRollback() {
        var revision = savedRevision("jpa", OWNER);
        var committed = snapshot("jpa-committed", revision, true);
        var rolledBack = snapshot("jpa-rollback", revision, true);
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.attempt.snapshot");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); factory.afterPropertiesSet();
        try {
            var entityManagers = factory.getObject();
            var manager = new JpaTransactionManager(entityManagers); manager.setDataSource(source);
            var jpaService = service(manager);
            var tx = new TransactionTemplate(manager);
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers)
                        .createNativeQuery("UPDATE snapshot_legacy_fixture SET marker='committed' WHERE id=1").executeUpdate();
                jpaService.createOrReplay(OWNER, committed);
            });
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers)
                        .createNativeQuery("UPDATE snapshot_legacy_fixture SET marker='rolled back' WHERE id=1").executeUpdate();
                jpaService.createOrReplay(OWNER, rolledBack); status.setRollbackOnly();
            });
            assertThat(service.read(OWNER, new PolicySnapshotReference.Stored(committed.id()))).isEqualTo(committed);
            rejected(() -> service.read(OWNER, new PolicySnapshotReference.Stored(rolledBack.id())), SNAPSHOT_UNAVAILABLE);
            assertThat(sql.queryForObject("SELECT marker FROM snapshot_legacy_fixture WHERE id=1", String.class)).isEqualTo("committed");
        } finally { factory.destroy(); }
    }

    @Test
    void readUsesOneBoundedMetadataQueryAcrossSeveralRevisionsAndPolicies() {
        var values = new ArrayList<PolicySnapshot>();
        for (int i = 0; i < 6; i++) {
            var value = snapshot("bounded-" + i, savedRevision("bounded-revision-" + i, OWNER), i % 2 == 0);
            service.createOrReplay(OWNER, value); values.add(value);
        }
        for (var value : values) {
            source.selects.clear();
            assertThat(service.read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
            assertThat(source.selects).hasSize(1).allSatisfy(query -> {
                assertThat(query).contains("WHERE s.id=:id".replace(":id", "?"));
                assertThat(query).doesNotContain("question_revisions", "documents", "users");
            });
        }
    }

    @Test
    void outagesAndTelemetryFailuresCannotLeakPayloadOrChangeEvidence() {
        var value = snapshot("outage", savedRevision("outage-revision", OWNER), true);
        source.failConnection = true;
        rejected(() -> service.createOrReplay(OWNER, value), PERSISTENCE_UNAVAILABLE);
        rejected(() -> service.read(OWNER, new PolicySnapshotReference.Stored(value.id())), PERSISTENCE_UNAVAILABLE);
        source.reset();
        service.createOrReplay(OWNER, value);
        source.beforeSql = (statement, connection) -> { throw new SQLException("private-content-must-not-leak", "08006"); };
        assertThatThrownBy(() -> service.read(OWNER, new PolicySnapshotReference.Stored(value.id())))
                .isInstanceOf(PolicySnapshotException.class).hasMessageNotContaining("private-content-must-not-leak").hasNoCause();
        source.reset();
        assertThat(service.read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
        service.read(OWNER, PolicySnapshotReference.Legacy.NO_SNAPSHOT);
        assertThat(metrics.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getName()).isEqualTo("attempt.policy.snapshots");
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactlyInAnyOrder("operation", "outcome", "version");
            assertThat(meter.getId().toString()).doesNotContain(value.id().toString(), value.resolution().hash(), OWNER.toString());
        });
        var brokenMetrics = org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
        org.mockito.Mockito.when(brokenMetrics.counter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<String[]>any()))
                .thenThrow(new IllegalStateException("Synthetic telemetry failure"));
        PolicySnapshotRepository repository = proxy(new JdbcPolicySnapshotRepository(new NamedParameterJdbcTemplate(source)), transactions);
        var withoutTelemetry = new PolicySnapshotServiceImpl(repository, transactions, brokenMetrics);
        assertThat(withoutTelemetry.createOrReplay(OWNER, value)).isEqualTo(value);
        assertThat(withoutTelemetry.read(OWNER, new PolicySnapshotReference.Stored(value.id()))).isEqualTo(value);
        rejected(() -> withoutTelemetry.read(OWNER, new PolicySnapshotReference.Stored(id("absent"))), SNAPSHOT_UNAVAILABLE);
    }

    private static UUID savedRevision(String key, UUID owner) {
        var revision = new QuizRevision(id(key), id(key + "-draft"), owner, 1, "Quiz", null,
                Instant.parse("2026-10-26T12:00:00Z"), List.of());
        new TransactionTemplate(transactions).executeWithoutResult(status -> revisions.insert(revision));
        return revision.id();
    }

    private PolicySnapshotService service(PlatformTransactionManager manager) {
        PolicySnapshotRepository repository = proxy(new JdbcPolicySnapshotRepository(new NamedParameterJdbcTemplate(source)), manager);
        return new PolicySnapshotServiceImpl(repository, manager, metrics);
    }

    private static <T> T proxy(T target, PlatformTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T result = (T) factory.getProxy();
        return result;
    }

    private static TransactionTemplate transaction(String isolation) {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(isolation.equals("REPEATABLE_READ") ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private static int count() { return sql.queryForObject("SELECT COUNT(*) FROM attempt_policy_snapshot", Integer.class); }
}
