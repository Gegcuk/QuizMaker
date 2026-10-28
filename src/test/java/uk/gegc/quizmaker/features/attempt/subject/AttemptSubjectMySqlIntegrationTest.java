package uk.gegc.quizmaker.features.attempt.subject;

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
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubjectService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptSubjectServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubjectRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAttemptSubjectRepository;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException.Reason.*;

/** Real Flyway, SQL and transactions. No SecurityContext/start/access integration is claimed. */
@Tag("db-serial")
class AttemptSubjectMySqlIntegrationTest {
    private static final String HISTORY = "flyway_attempt_subject_history";
    private static SubjectDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager manager;
    private static boolean createdUsers;
    private final List<UUID> users = new ArrayList<>();
    private SimpleMeterRegistry metrics;
    private AttemptSubjectService service;

    @BeforeAll
    static void connect() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        source = new SubjectDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser")));
        sql = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        createdUsers = sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='users'", Integer.class) == 0;
        if (createdUsers) {
            // Minimal current user contract for isolated storage tests. Full-suite execution uses
            // the existing real schema instead; neither path relies on user-column defaults.
            // Full user migrations/schema integration are verified separately, not claimed here.
            sql.execute("""
                    CREATE TABLE users (
                        user_id BINARY(16) PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE,
                        email VARCHAR(254) NOT NULL, password VARCHAR(255) NOT NULL,
                        is_active BOOLEAN NOT NULL, is_deleted BOOLEAN NOT NULL,
                        email_verified BOOLEAN NOT NULL, version BIGINT NOT NULL,
                        created_at DATETIME(6) NOT NULL, password_changed_at DATETIME(6) NOT NULL,
                        deleted_at DATETIME(6) NULL
                    ) ENGINE=InnoDB
                    """);
        }
    }

    @BeforeEach
    void migrate() {
        dropOwnedTables();
        // A historical-source fixture includes null identity; the current attempts schema forbids it.
        // This asserts source preservation, not that current APIs can create a null-user attempt.
        sql.execute("CREATE TABLE subject_legacy_fixture (id BINARY(16) PRIMARY KEY,user_id BINARY(16) NULL,marker VARCHAR(30))");
        sql.update("INSERT INTO subject_legacy_fixture VALUES (?,NULL,'unchanged')", bytes(id("legacy-null")));
        Flyway.configure().dataSource(source).table(HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("85").target("86").load().migrate();
        metrics = new SimpleMeterRegistry();
        service = service(manager);
        source.reset();
    }

    @AfterEach
    void clean() {
        dropOwnedTables();
        for (UUID user : users) sql.update("DELETE FROM users WHERE user_id=?", bytes(user));
        users.clear();
        if (metrics != null) metrics.close();
    }

    @AfterAll
    static void cleanUsersFixture() {
        if (createdUsers) sql.execute("DROP TABLE IF EXISTS users");
    }

    private static void dropOwnedTables() {
        source.reset();
        for (String table : List.of("attempt_trace_identity", "attempt_trace_event", "attempt_trace_stream", "attempt_participant_subject", HISTORY, "subject_legacy_fixture")) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void exactReplayAndRecreatedStorageRetainOneImmutableBinding() {
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("retry"), user);
        assertThat(service.bindOrReplay(user, binding)).isEqualTo(binding);
        assertThat(service(manager).bindOrReplay(user, binding)).isEqualTo(binding);
        assertThat(service(manager).read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(count()).isEqualTo(1);
        UUID other = user(true, false);
        rejected(() -> service.bindOrReplay(other, AttemptSubject.authenticated(binding.attemptId(), other)), SUBJECT_CONFLICT);
        assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void callerSuppliedIdentityCannotReplaceTrustedIdentityAndHasASuccessfulControl() {
        UUID trusted = user(true, false), supplied = user(true, false);
        UUID attempt = id("identity");
        rejected(() -> service.bindOrReplay(trusted, AttemptSubject.authenticated(attempt, supplied)), SUBJECT_UNAVAILABLE);
        rejected(() -> service.bindOrReplay(null, AttemptSubject.authenticated(attempt, supplied)), SUBJECT_UNAVAILABLE);
        assertThat(count()).isZero();
        var correct = AttemptSubject.authenticated(attempt, trusted);
        assertThat(service.bindOrReplay(trusted, correct)).isEqualTo(correct);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true"})
    void inactiveAndSoftDeletedUsersCannotCreateBindings(boolean active, boolean deleted) {
        UUID user = user(active, deleted);
        rejected(() -> service.bindOrReplay(user, AttemptSubject.authenticated(id("ineligible"), user)), SUBJECT_UNAVAILABLE);
        assertThat(count()).isZero();
        sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE WHERE user_id=?", bytes(user));
        assertThat(service.bindOrReplay(user, AttemptSubject.authenticated(id("ineligible"), user)).userId()).isEqualTo(user);
    }

    @Test
    void missingUserIsRejectedAndRetainedEvidenceDoesNotGrantRenewedAuthentication() {
        UUID missing = UUID.randomUUID();
        rejected(() -> service.bindOrReplay(missing, AttemptSubject.authenticated(id("missing-user"), missing)), SUBJECT_UNAVAILABLE);
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("retained"), user);
        service.bindOrReplay(user, binding);
        sql.update("UPDATE users SET is_deleted=TRUE,is_active=FALSE WHERE user_id=?", bytes(user));
        rejected(() -> service.bindOrReplay(user, binding), SUBJECT_UNAVAILABLE);
        assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void migrationAndReadsNeverInferBindingsFromLegacyUserSentinelOrNullIdentity() {
        UUID user = user(true, false);
        UUID sentinel = sentinel();
        sql.update("INSERT INTO subject_legacy_fixture VALUES (?,?,'unchanged'), (?,?,'unchanged')",
                bytes(id("legacy-user")), bytes(user), bytes(id("legacy-sentinel")), bytes(sentinel));
        // Re-running the real migration must not backfill any source shape.
        sql.execute("DROP TABLE attempt_participant_subject");
        sql.execute("DROP TABLE " + HISTORY);
        Flyway.configure().dataSource(source).table(HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("85").target("86").load().migrate();
        source.selects.clear();
        for (String key : List.of("legacy-user", "legacy-sentinel", "legacy-null", "absent")) {
            assertThat(service.read(id(key))).isEqualTo(new AttemptSubjectLookup.LegacyOrUnbound());
        }
        assertThat(source.selects).hasSize(4).allSatisfy(statement ->
                assertThat(statement).doesNotContain("subject_legacy_fixture", "FROM users", "FROM attempts"));
        rejected(() -> service.bindOrReplay(sentinel, AttemptSubject.authenticated(id("sentinel-retype"), sentinel)), SUBJECT_UNAVAILABLE);
        assertThat(count()).isZero();
        assertThat(sql.queryForList("SELECT marker FROM subject_legacy_fixture", String.class)).containsExactly("unchanged", "unchanged", "unchanged");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM subject_legacy_fixture WHERE user_id IS NULL", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,true", "READ_COMMITTED,false", "REPEATABLE_READ,true", "REPEATABLE_READ,false"})
    void independentConcurrentWritersHaveARealWinnerAndImmutableReplay(String isolation, boolean identical) throws Exception {
        UUID first = user(true, false), second = identical ? first : user(true, false);
        var a = AttemptSubject.authenticated(id("race"), first);
        var b = AttemptSubject.authenticated(id("race"), second);
        var barrier = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Integer>newKeySet();
        source.beforeSql = (statement, connection) -> {
            if (statement.stripLeading().startsWith("INSERT INTO attempt_participant_subject")) {
                connections.add(connection);
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        List<Outcome> outcomes;
        try {
            var one = pool.submit(() -> bindOutcome(a, isolation));
            var two = pool.submit(() -> bindOutcome(b, isolation));
            outcomes = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        } finally {
            source.beforeSql = null; pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(connections).hasSize(2);
        assertThat(source.sqlErrorCodes).isEmpty();
        var successes = outcomes.stream().filter(outcome -> outcome.failure() == null).toList();
        assertThat(successes).hasSize(identical ? 2 : 1);
        if (!identical) assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null).toList())
                .singleElement().extracting(Outcome::failure).isEqualTo(SUBJECT_CONFLICT);
        var winner = successes.get(0).subject();
        assertThat(count()).isEqualTo(1);
        assertThat(service(manager).read(a.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(winner));
        assertThat(service.bindOrReplay(winner.userId(), winner)).isEqualTo(winner);
        if (!identical) {
            var loser = winner.equals(a) ? b : a;
            rejected(() -> service.bindOrReplay(loser.userId(), loser), SUBJECT_CONFLICT);
        }
    }

    @Test
    void reachedInsertFailureMarksAnOuterTransactionRollbackOnlyAndAllowsCleanRetry() {
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("rollback"), user);
        var reached = new AtomicInteger();
        metrics.counter("quizmaker.attempt.subject.operations", "operation", "bind_or_replay", "outcome", "persistence_unavailable");
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT attempt_id")) {
                reached.set(count());
                throw new SQLException("Synthetic post-insert failure", "08006");
            }
        };
        assertThatThrownBy(() -> transaction("READ_COMMITTED").executeWithoutResult(status -> {
            sql.update("UPDATE subject_legacy_fixture SET marker='partial'");
            rejected(() -> service.bindOrReplay(user, binding), PERSISTENCE_UNAVAILABLE);
        })).isInstanceOf(UnexpectedRollbackException.class);
        source.reset();
        assertThat(reached).as("the actual insert reached the fault checkpoint").hasValue(1);
        assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.LegacyOrUnbound());
        assertThat(count()).isZero();
        assertThat(sql.queryForObject("SELECT marker FROM subject_legacy_fixture", String.class)).isEqualTo("unchanged");
        assertThat(service.bindOrReplay(user, binding)).isEqualTo(binding);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailureAndLostAcknowledgementCanBeRetriedAfterRecreatingTheService(boolean committed) {
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("commit"), user);
        var once = new AtomicBoolean(true);
        var reached = new AtomicInteger();
        SubjectDataSource.CommitHook hook = connection -> {
            if (!once.getAndSet(false)) return;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM attempt_participant_subject")) {
                assertThat(rows.next()).isTrue(); reached.set(rows.getInt(1));
            }
            if (!committed) connection.rollback();
            throw new SQLException("Synthetic commit acknowledgement failure", "08006");
        };
        if (committed) source.afterCommit = hook; else source.beforeCommit = hook;
        rejected(() -> service.bindOrReplay(user, binding), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(reached).hasValue(1);
        assertThat(count()).isEqualTo(committed ? 1 : 0);
        var restarted = service(new DataSourceTransactionManager(source));
        assertThat(restarted.bindOrReplay(user, binding)).isEqualTo(binding);
        assertThat(restarted.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void replayUsesCurrentCommittedBindingEvenWhenOrdinaryReadHasAnOlderSnapshot(String isolation) throws Exception {
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("snapshot"), user);
        var pool = Executors.newSingleThreadExecutor();
        try {
            transaction(isolation).executeWithoutResult(status -> {
                assertThat(service.read(binding.attemptId())).isInstanceOf(AttemptSubjectLookup.LegacyOrUnbound.class);
                try { assertThat(pool.submit(() -> service.bindOrReplay(user, binding)).get(10, TimeUnit.SECONDS)).isEqualTo(binding); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(service.bindOrReplay(user, binding)).isEqualTo(binding);
                assertThat(service.read(binding.attemptId())).isEqualTo(isolation.equals("REPEATABLE_READ")
                        ? new AttemptSubjectLookup.LegacyOrUnbound() : new AttemptSubjectLookup.Bound(binding));
            });
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema_version", "subject_type"})
    void unknownStoredVersionsAndTypesFailClosedOnReadAndReplay(String column) {
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("corrupt"), user);
        service.bindOrReplay(user, binding);
        boolean version = column.equals("schema_version");
        sql.execute("ALTER TABLE attempt_participant_subject DROP CHECK " + (version ? "chk_attempt_subject_version" : "chk_attempt_subject_type"));
        sql.update("UPDATE attempt_participant_subject SET " + column + "=?", version ? 2 : "CAPABILITY_GUEST");
        var reason = version ? UNSUPPORTED_VERSION : UNSUPPORTED_TYPE;
        rejected(() -> service.read(binding.attemptId()), reason);
        rejected(() -> service.bindOrReplay(user, binding), reason);
        assertThat(sql.queryForObject("SELECT " + column + " FROM attempt_participant_subject", String.class))
                .isEqualTo(version ? "2" : "CAPABILITY_GUEST");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void databaseRejectsDuplicateBindingsMissingUsersUnknownVocabularyAndUserCascade() {
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("constraints"), user);
        service.bindOrReplay(user, binding);
        assertThatThrownBy(() -> insert(binding.attemptId(), 1, "AUTHENTICATED_USER", user)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), 1, "AUTHENTICATED_USER", UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), 2, "AUTHENTICATED_USER", user)).isInstanceOf(DataAccessException.class);
        for (String type : List.of("CAPABILITY_GUEST", "authenticated_user", "AUTHENTICATED_USER ")) {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), 1, type, user)).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> sql.update("DELETE FROM users WHERE user_id=?", bytes(user))).isInstanceOf(DataAccessException.class);
        assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void readHasOneIndexedQueryRegardlessOfOtherSubjectsAndDoesNotLoadUserGraphs() {
        var bindings = new ArrayList<AttemptSubject>();
        for (int i = 0; i < 6; i++) {
            UUID user = user(true, false);
            var binding = AttemptSubject.authenticated(UUID.randomUUID(), user);
            service.bindOrReplay(user, binding); bindings.add(binding);
        }
        source.selects.clear();
        for (var binding : bindings) assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(source.selects).hasSize(6).allSatisfy(statement -> assertThat(statement)
                .contains("FROM attempt_participant_subject").doesNotContain("JOIN", "FROM users", "FROM attempts"));
        var plan = sql.queryForMap("EXPLAIN SELECT attempt_id,schema_version,subject_type,user_id FROM attempt_participant_subject WHERE attempt_id=?", bytes(bindings.get(0).attemptId()));
        assertThat(plan.get("key")).isEqualTo("PRIMARY");
        assertThat(plan.get("type")).isEqualTo("const");
    }

    @Test
    void jdbcStorageJoinsActualJpaCommitAndRollback() {
        UUID user = user(true, false);
        var committed = AttemptSubject.authenticated(id("jpa-commit"), user);
        var rollback = AttemptSubject.authenticated(id("jpa-rollback"), user);
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.attempt.subject");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); factory.afterPropertiesSet();
        try {
            var em = factory.getObject();
            var jpaManager = new JpaTransactionManager(em); jpaManager.setDataSource(source);
            var jpaService = service(jpaManager);
            var tx = new TransactionTemplate(jpaManager);
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(em).createNativeQuery("UPDATE subject_legacy_fixture SET marker='committed'").executeUpdate();
                assertThat(jpaService.bindOrReplay(user, committed)).isEqualTo(committed);
            });
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(em).createNativeQuery("UPDATE subject_legacy_fixture SET marker='rolled back'").executeUpdate();
                assertThat(jpaService.bindOrReplay(user, rollback)).isEqualTo(rollback);
                assertThat(jpaService.read(rollback.attemptId())).isInstanceOf(AttemptSubjectLookup.Bound.class);
                status.setRollbackOnly();
            });
            assertThat(service.read(committed.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(committed));
            assertThat(service.read(rollback.attemptId())).isInstanceOf(AttemptSubjectLookup.LegacyOrUnbound.class);
            assertThat(sql.queryForObject("SELECT marker FROM subject_legacy_fixture", String.class)).isEqualTo("committed");
        } finally { factory.destroy(); }
    }

    @Test
    void failuresAndMetricsStayBoundedAndDoNotLeakIdentityOrSql() {
        UUID user = user(true, false), other = user(true, false);
        var binding = AttemptSubject.authenticated(id("metrics"), user);
        service.read(binding.attemptId()); service.bindOrReplay(user, binding); service.bindOrReplay(user, binding);
        rejected(() -> service.bindOrReplay(other, AttemptSubject.authenticated(binding.attemptId(), other)), SUBJECT_CONFLICT);
        source.failConnection = true;
        rejected(() -> service.read(binding.attemptId()), PERSISTENCE_UNAVAILABLE);
        rejected(() -> service.bindOrReplay(user, binding), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(metrics.get("quizmaker.attempt.subject.operations").tag("outcome", "success").timer().count()).isEqualTo(2);
        assertThat(metrics.get("quizmaker.attempt.subject.operations").tag("outcome", "legacy_or_unbound").timer().count()).isEqualTo(1);
        assertThat(metrics.get("quizmaker.attempt.subject.operations").tag("outcome", "subject_conflict").timer().count()).isEqualTo(1);
        metrics.getMeters().forEach(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("operation", "outcome");
            assertThat(tag.getValue()).doesNotContain(user.toString(), binding.attemptId().toString());
        }));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metricTypeCollisionCannotTurnACommittedBindingIntoAnException(boolean outerTransaction) {
        UUID user = user(true, false);
        var binding = AttemptSubject.authenticated(id("metric-success"), user);
        metrics.counter("quizmaker.attempt.subject.operations", "operation", "bind_or_replay", "outcome", "success");
        if (outerTransaction) {
            var returned = transaction("READ_COMMITTED").execute(status -> {
                var result = service.bindOrReplay(user, binding);
                assertThat(count()).as("actual write reached before outer commit").isEqualTo(1);
                assertThat(status.isRollbackOnly()).isFalse();
                return result;
            });
            assertThat(returned).isEqualTo(binding);
        } else assertThat(service.bindOrReplay(user, binding)).isEqualTo(binding);
        assertThat(service(manager).read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(service.bindOrReplay(user, binding)).isEqualTo(binding);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void metricTypeCollisionPreservesConflictAndOuterRollbackSemantics() {
        UUID user = user(true, false), other = user(true, false);
        var binding = AttemptSubject.authenticated(id("metric-conflict"), user);
        service.bindOrReplay(user, binding);
        metrics.counter("quizmaker.attempt.subject.operations", "operation", "bind_or_replay", "outcome", "subject_conflict");
        assertThatThrownBy(() -> transaction("READ_COMMITTED").executeWithoutResult(status -> {
            sql.update("UPDATE subject_legacy_fixture SET marker='partial'");
            rejected(() -> service.bindOrReplay(other, AttemptSubject.authenticated(binding.attemptId(), other)), SUBJECT_CONFLICT);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
        assertThat(sql.queryForObject("SELECT marker FROM subject_legacy_fixture", String.class)).isEqualTo("unchanged");
        assertThat(count()).isEqualTo(1);
    }

    private UUID user(boolean active, boolean deleted) {
        UUID id = UUID.randomUUID(); users.add(id);
        var time = java.sql.Timestamp.valueOf("2026-01-02 12:00:00");
        sql.update("""
                INSERT INTO users (user_id,username,email,password,is_active,is_deleted,
                    email_verified,created_at,password_changed_at,version,deleted_at)
                VALUES (?,?,?,?,?,?,FALSE,?,?,0,?)
                """, bytes(id), "subject-" + id, id + "@example.invalid", "unused", active, deleted,
                time, time, deleted ? time : null);
        return id;
    }

    private UUID sentinel() {
        var existing = sql.query("SELECT user_id FROM users WHERE username='anonymous'", (row, index) -> uuid(row.getBytes(1)));
        if (!existing.isEmpty()) return existing.get(0);
        UUID id = user(true, false);
        sql.update("UPDATE users SET username='anonymous' WHERE user_id=?", bytes(id));
        return id;
    }

    private static void insert(UUID attempt, int version, String type, UUID user) {
        sql.update("INSERT INTO attempt_participant_subject VALUES (?,?,?,?)", bytes(attempt), version, type, bytes(user));
    }

    private Outcome bindOutcome(AttemptSubject subject, String isolation) {
        try { return new Outcome(transaction(isolation).execute(status -> service.bindOrReplay(subject.userId(), subject)), null); }
        catch (AttemptSubjectException failure) { return new Outcome(subject, failure.reason()); }
    }

    private AttemptSubjectService service(PlatformTransactionManager transactions) {
        var proxy = new ProxyFactory(new JdbcAttemptSubjectRepository(new NamedParameterJdbcTemplate(source)));
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return new AttemptSubjectServiceImpl((AttemptSubjectRepository) proxy.getProxy(), transactions, metrics);
    }

    private static TransactionTemplate transaction(String isolation) {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(isolation.equals("READ_COMMITTED") ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(20); return tx;
    }

    private static int count() { return sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class); }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { var buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong()); }
    private static void rejected(Runnable action, AttemptSubjectException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AttemptSubjectException.class, failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessage("Attempt subject rejected: " + reason).hasNoCause();
    }
    private record Outcome(AttemptSubject subject, AttemptSubjectException.Reason failure) { }
}
