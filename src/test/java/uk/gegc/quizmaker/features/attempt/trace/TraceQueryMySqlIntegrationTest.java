package uk.gegc.quizmaker.features.attempt.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.trace.TraceFixtures.*;
import static uk.gegc.quizmaker.features.attempt.trace.TraceQueryTest.denied;

/**
 * Real prerequisites, writer, query SQL, Spring method security and RC transactions.
 * The SQL-backed context fixture proves query enforcement, not production operator grants,
 * #698 access audit or #699 retention/hold/erasure execution.
 */
@Tag("db-serial")
class TraceQueryMySqlIntegrationTest {
    static JdbcTemplate admin;
    static String catalog;
    static HikariDataSource pool;
    static TraceDataSource faults;
    static VolumeDataSource source;
    static JdbcTemplate sql;
    static DataSourceTransactionManager manager;
    AnnotationConfigApplicationContext application;
    SimpleMeterRegistry metrics;
    TraceQueryService reader;
    ExecutionTraceService writer;
    TraceQueryRepository repository;
    final List<Integer> contextIsolations = new ArrayList<>();

    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity
    static class Security { }

    @BeforeAll static void migrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        catalog = "quizmaker_540_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + catalog + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var config = new HikariConfig(); config.setJdbcUrl(base + catalog + options);
        config.setUsername(user); config.setPassword(password); config.setMaximumPoolSize(5);
        config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        pool = new HikariDataSource(config); faults = new TraceDataSource(pool); source = new VolumeDataSource(faults);
        sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        sql.execute("""
                CREATE TABLE fixture_trace_query_context (
                  username VARCHAR(64) NOT NULL, attempt_id BINARY(16) NOT NULL, purpose VARCHAR(16) NOT NULL,
                  policy_id BINARY(16) NOT NULL, form_id BINARY(16) NOT NULL, availability VARCHAR(32) NOT NULL,
                  PRIMARY KEY(username,attempt_id,purpose))
                """);
        sql.execute("CREATE TABLE fixture_query_business (id INT PRIMARY KEY, marker INT NOT NULL)");
        sql.update("INSERT INTO fixture_query_business VALUES (1,0)");
    }

    @BeforeEach void setup() {
        faults.reset(); source.clear(); contextIsolations.clear();
        sql.update("DELETE FROM fixture_trace_query_context"); sql.update("UPDATE fixture_query_business SET marker=0");
        metrics = new SimpleMeterRegistry();
        repository = proxy(new JdbcTraceQueryRepository(named()));
        writer = new ExecutionTraceServiceImpl(proxy(new JdbcExecutionTraceRepository(named())), manager,
                Clock.fixed(NOW, ZoneOffset.UTC), metrics);
        application = application(List.of(this::context)); reader = application.getBean(TraceQueryService.class);
        login("operator");
    }
    @AfterEach void close() {
        faults.reset(); SecurityContextHolder.clearContext();
        if (application != null) application.close(); metrics.close();
    }
    @AfterAll static void cleanup() {
        if (pool != null) pool.close();
        if (catalog != null) admin.execute("DROP DATABASE " + catalog);
    }

    AnnotationConfigApplicationContext application(List<TraceQueryContextReader> contexts) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(TraceQueryRepository.class, () -> repository);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.registerBean(MeterRegistry.class, () -> metrics);
        for (int i = 0; i < contexts.size(); i++) {
            var value = contexts.get(i); context.registerBean("context-" + i, TraceQueryContextReader.class, () -> value);
        }
        context.register(Security.class, TraceQueryServiceImpl.class); context.refresh(); return context;
    }

    Optional<Context> context(UUID attempt, Purpose purpose, Operation operation) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(operation).isEqualTo(Operation.READ_PAGE);
        var holder = (org.springframework.jdbc.datasource.ConnectionHolder) TransactionSynchronizationManager.getResource(source);
        try { contextIsolations.add(holder.getConnection().getTransactionIsolation()); }
        catch (SQLException failure) { throw new AssertionError(failure); }
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return sql.query("""
                SELECT attempt_id,policy_id,form_id,availability FROM fixture_trace_query_context
                WHERE username=? AND attempt_id=? AND purpose=? FOR SHARE
                """, (row, index) -> new Context(new Scope(uuid(row, "attempt_id"), uuid(row, "policy_id"), uuid(row, "form_id")),
                purpose, operation, Availability.valueOf(row.getString("availability"))), username, bytes(attempt), purpose.name())
                .stream().findFirst();
    }

    @Test void stablePagesUseCommittedEvidenceAndOriginalCutAcrossAppendAndServiceReconstruction() {
        var scope = fixture(); append(scope, 5); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        var first = read(scope, null, 2);
        assertThat(first.events()).extracting(Entry::sequence).containsExactly(1L, 2L);
        append(scope, 2);
        application.close(); application = application(List.of(this::context)); reader = application.getBean(TraceQueryService.class);
        var second = read(scope, first.nextCursor(), 1);
        var third = read(scope, second.nextCursor(), 100);
        assertThat(second.events()).extracting(Entry::sequence).containsExactly(3L);
        assertThat(third.events()).extracting(Entry::sequence).containsExactly(4L, 5L);
        assertThat(third.nextCursor()).isNull();
        assertThat(read(scope, first.nextCursor(), 2).events()).extracting(Entry::sequence).containsExactly(3L, 4L);
        assertThat(read(scope, null, 100).events()).extracting(Entry::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }

    @Test void methodSecurityAndCurrentExactScopeAndPurposeAreRequiredOnEveryPage() {
        var scope = fixture(); var other = fixture(); append(scope, 3); append(other, 3);
        grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        var first = read(scope, null, 1);
        for (String username : List.of("foreign-owner", "other-organization", "no-permission")) {
            login(username); faults.selects.clear();
            denied(() -> read(scope, first.nextCursor(), 1), ACCESS_UNAVAILABLE);
            assertThat(faults.selects).noneMatch(s -> s.contains("attempt_trace_"));
        }
        SecurityContextHolder.clearContext(); faults.selects.clear();
        assertThatThrownBy(() -> read(scope, null, 1)).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        assertThat(faults.selects).isEmpty();
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("fixture", "anonymous", List.of(() -> "ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> read(scope, null, 1)).isInstanceOf(AuthorizationDeniedException.class);
        login("operator");
        denied(() -> read(other, null, 1), ACCESS_UNAVAILABLE);
        grant(other, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        denied(() -> read(other, first.nextCursor(), 1), INVALID_CURSOR);
        denied(() -> reader.read(new Request(1, scope.attemptId(), Purpose.OPERATIONS, first.nextCursor(), 1)), ACCESS_UNAVAILABLE);
        grant(scope, "operator", Purpose.OPERATIONS, Availability.AVAILABLE);
        denied(() -> reader.read(new Request(1, scope.attemptId(), Purpose.OPERATIONS, first.nextCursor(), 1)), INVALID_CURSOR);
        sql.update("DELETE FROM fixture_trace_query_context WHERE attempt_id=? AND purpose='DISPUTE'", bytes(scope.attemptId()));
        denied(() -> read(scope, first.nextCursor(), 1), ACCESS_UNAVAILABLE);
        grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        assertThat(read(scope, first.nextCursor(), 1).events()).extracting(Entry::sequence).containsExactly(2L);
    }

    @ParameterizedTest @EnumSource(value = Availability.class, names = "AVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void knownAvailabilityStatesRemainExplicitWhileUnknownAndMissingEvidenceAreNeverInvented(Availability availability) {
        var scope = fixture(); append(scope, 2); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        String cursor = read(scope, null, 1).nextCursor();
        grant(scope, "operator", Purpose.DISPUTE, availability); faults.selects.clear();
        var expected = switch (availability) {
            case EXPIRED -> EVIDENCE_EXPIRED; case RETAINED_AWAY -> EVIDENCE_RETAINED_AWAY;
            default -> EVIDENCE_UNAVAILABLE;
        };
        denied(() -> read(scope, cursor, 1), expected);
        assertThat(faults.selects).noneMatch(s -> s.contains("attempt_trace_"));
        grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        assertThat(read(scope, cursor, 1).events()).hasSize(1);
        sql.update("DELETE FROM attempt_trace_identity WHERE attempt_id=?", bytes(scope.attemptId()));
        sql.update("DELETE FROM attempt_trace_event WHERE attempt_id=?", bytes(scope.attemptId()));
        sql.update("DELETE FROM attempt_trace_stream WHERE attempt_id=?", bytes(scope.attemptId()));
        denied(() -> read(scope, null, 1), CORRUPT_EVIDENCE);
        // This is context-consumer proof; no production erasure or retention writer is claimed.
        grant(scope, "operator", Purpose.DISPUTE, Availability.RETAINED_AWAY);
        denied(() -> read(scope, null, 1), EVIDENCE_RETAINED_AWAY);
    }

    @Test void onlyAProvenEmptyStreamReturnsAnEmptyPage() {
        var scope = fixture();
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status ->
                proxy(new JdbcExecutionTraceRepository(named())).ensureStream(scope));
        grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        var empty = read(scope, null, 100);
        assertThat(empty.events()).isEmpty(); assertThat(empty.nextCursor()).isNull();
        append(scope, 1); assertThat(read(scope, null, 100).events()).hasSize(1);
    }

    @ParameterizedTest @CsvSource({"event_type,private-answer,chk_trace_event_type", "actor_type,private-actor,chk_trace_event_actor",
            "reason_code,private-reason,chk_trace_event_reason", "schema_version,2,chk_trace_event_version",
            "from_execution,private-state,chk_trace_event_transition"})
    void corruptOrUnknownStoredFieldsEscalateWithoutReturningAPartialPage(String column, String value, String check) {
        var scope = fixture(); append(scope, 2); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        var original = sql.queryForMap("SELECT * FROM attempt_trace_event WHERE attempt_id=? AND sequence_number=2", bytes(scope.attemptId()));
        sql.execute("ALTER TABLE attempt_trace_event ALTER CHECK " + check + " NOT ENFORCED");
        try {
            sql.update("UPDATE attempt_trace_event SET " + column + "=? WHERE attempt_id=? AND sequence_number=2", value, bytes(scope.attemptId()));
            denied(() -> read(scope, null, 100), column.equals("schema_version") ? UNSUPPORTED_VERSION : CORRUPT_EVIDENCE);
        } finally {
            sql.update("UPDATE attempt_trace_event SET " + column + "=? WHERE attempt_id=? AND sequence_number=2", original.get(column), bytes(scope.attemptId()));
            sql.execute("ALTER TABLE attempt_trace_event ALTER CHECK " + check + " ENFORCED");
        }
        assertThat(read(scope, null, 100).events()).hasSize(2);
    }

    @Test void rootTailAndInteriorGapsFailClosedInsteadOfInventingHistory() {
        var scope = fixture(); append(scope, 3); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        for (long wrong : List.of(0L, 2L, 4L, Long.MAX_VALUE)) {
            sql.update("UPDATE attempt_trace_stream SET last_sequence=? WHERE attempt_id=?", wrong, bytes(scope.attemptId()));
            denied(() -> read(scope, null, 1), CORRUPT_EVIDENCE);
        }
        sql.update("UPDATE attempt_trace_stream SET last_sequence=3 WHERE attempt_id=?", bytes(scope.attemptId()));
        assertThat(read(scope, null, 100).events()).hasSize(3);
        sql.update("DELETE FROM attempt_trace_event WHERE attempt_id=? AND sequence_number=2", bytes(scope.attemptId()));
        denied(() -> read(scope, null, 100), CORRUPT_EVIDENCE);
    }

    @Test void unknownStreamVersionIsNotInterpretedAsTheCurrentVocabulary() {
        var scope = fixture(); append(scope, 1); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        sql.execute("ALTER TABLE attempt_trace_stream ALTER CHECK chk_trace_stream NOT ENFORCED");
        try {
            sql.update("UPDATE attempt_trace_stream SET schema_version=2 WHERE attempt_id=?", bytes(scope.attemptId()));
            denied(() -> read(scope, null, 100), UNSUPPORTED_VERSION);
        } finally {
            sql.update("UPDATE attempt_trace_stream SET schema_version=1 WHERE attempt_id=?", bytes(scope.attemptId()));
            sql.execute("ALTER TABLE attempt_trace_stream ALTER CHECK chk_trace_stream ENFORCED");
        }
        assertThat(read(scope, null, 100).events()).hasSize(1);
    }

    @Test void appendCompletesWhileTheQueryHoldsItsCutWithoutReadingTheLaterEvent() throws Exception {
        var scope = fixture(); append(scope, 3); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1); var held = new AtomicBoolean();
        faults.afterSql = (statement, connection) -> {
            if (statement.contains("AS tail") && held.compareAndSet(false, true)) { reached.countDown(); await(release); }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var query = workers.submit(() -> { login("operator"); try { return read(scope, null, 100); } finally { SecurityContextHolder.clearContext(); } });
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            var peer = workers.submit(() -> append(scope, 1));
            peer.get(10, TimeUnit.SECONDS); // Actual successful commit before releasing the reader.
            assertThat(query.isDone()).isFalse(); release.countDown();
            assertThat(query.get(10, TimeUnit.SECONDS).events()).extracting(Entry::sequence).containsExactly(1L, 2L, 3L);
            faults.reset(); assertThat(read(scope, null, 100).events()).hasSize(4);
        } finally {
            release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(ints = {Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_READ_UNCOMMITTED})
    void incompatibleCallerIsRejectedBeforeContextAndDomainSql(int isolation) {
        var scope = fixture(); append(scope, 1); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        faults.selects.clear(); contextIsolations.clear();
        assertThatThrownBy(() -> tx(isolation).execute(status -> read(scope, null, 1)))
                .isInstanceOfSatisfying(AttemptTransactionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AttemptTransactionException.Reason.INCOMPATIBLE_ISOLATION));
        assertThat(faults.selects).isEmpty(); assertThat(contextIsolations).isEmpty();
        assertThat(read(scope, null, 1).events()).hasSize(1); restoredPool();
    }

    @Test void standaloneAndJoinedRcReadsPreserveCallerRollbackAndConnectionCharacteristics() {
        var scope = fixture(); append(scope, 2); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        read(scope, null, 1); restoredPool();
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            sql.update("UPDATE fixture_query_business SET marker=1 WHERE id=1");
            assertThat(read(scope, null, 100).events()).hasSize(2); status.setRollbackOnly();
        });
        assertThat(sql.queryForObject("SELECT marker FROM fixture_query_business WHERE id=1", Integer.class)).isZero();
        var readonly = tx(TransactionDefinition.ISOLATION_READ_COMMITTED); readonly.setReadOnly(true);
        assertThat(readonly.execute(status -> read(scope, null, 1)).events()).hasSize(1);
        assertThat(contextIsolations).containsOnly(Connection.TRANSACTION_READ_COMMITTED); restoredPool();
    }

    @Test void queryFailuresAreReachedSanitizedAndDoNotMutateAuthoritativeEvidence() {
        var scope = fixture(); append(scope, 2); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        var checkpoint = new AtomicInteger();
        faults.beforeSql = (statement, connection) -> {
            if (statement.contains("FROM attempt_trace_event e FORCE INDEX" ) && !statement.contains("AS tail")) {
                checkpoint.incrementAndGet(); throw new SQLTimeoutException("SELECT private answer credential", "HYT00");
            }
        };
        denied(() -> read(scope, null, 100), PERSISTENCE_UNAVAILABLE);
        assertThat(checkpoint).hasValue(1); faults.reset();
        assertThat(read(scope, null, 100).events()).hasSize(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_trace_event WHERE attempt_id=?", Integer.class, bytes(scope.attemptId()))).isEqualTo(2);
        restoredPool();
    }

    @Test void multiParentSerializationUsesFixedScalarQueriesRowsAndBytesWithThePrimaryRangePlan() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var measured = new ArrayList<Long>();
        for (int size : List.of(3, 30, 250)) {
            var scope = fixture(); append(scope, size); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
            faults.selects.clear(); source.clear();
            var page = read(scope, null, 2); String json = mapper.writeValueAsString(page);
            assertThat(page.events()).hasSize(2); assertThat(faults.selects).hasSize(3);
            assertThat(faults.selects).noneMatch(s -> s.contains("question_revision") || s.contains("documents") || s.contains("FOR UPDATE"));
            var pageSql = faults.selects.stream().filter(s -> s.contains("WHERE e.attempt_id=? AND e.sequence_number>?")).findFirst().orElseThrow();
            assertThat(pageSql).doesNotContain("e.*", "command_id", "item_id", "event_id", "form_id");
            var volume = source.volumes.stream().filter(v -> v.sql.equals(pageSql)).findFirst().orElseThrow();
            assertThat(volume.rows).hasValue(2); measured.add(volume.bytes.get());
            assertThat(source.volumes).extracting(v -> v.rows.get()).containsExactly(1, 1, 2);
            assertThat(json).doesNotContain(scope.attemptId().toString(), scope.formId().toString(), scope.policySnapshotId().toString(), "commandId", "itemId", "eventId");
            var plan = sql.queryForList("EXPLAIN " + pageSql, bytes(scope.attemptId()), 0, size, 2);
            assertThat(plan).anySatisfy(row -> { assertThat(row.get("table")).isEqualTo("e"); assertThat(row.get("key")).isEqualTo("PRIMARY"); });
            assertThat(plan).allSatisfy(row -> assertThat(String.valueOf(row.get("Extra"))).doesNotContain("filesort"));
            var headSql = faults.selects.stream().filter(s -> s.contains("AS tail")).findFirst().orElseThrow();
            assertThat(sql.queryForList("EXPLAIN " + headSql, bytes(scope.attemptId())))
                    .allSatisfy(row -> assertThat(String.valueOf(row.get("Extra"))).doesNotContain("filesort"));
        }
        assertThat(measured).hasSize(3).allMatch(n -> n > 0).containsOnly(measured.get(0));
    }

    @Test void missingOrAmbiguousProductionIntegrationDeniesEvenAnAuthenticatedOperator() {
        var scope = fixture(); append(scope, 1); grant(scope, "operator", Purpose.DISPUTE, Availability.AVAILABLE);
        assertThat(read(scope, null, 1).events()).hasSize(1);
        for (var contexts : List.of(List.<TraceQueryContextReader>of(), List.<TraceQueryContextReader>of(this::context, this::context))) {
            try (var empty = application(contexts)) {
                faults.selects.clear();
                denied(() -> empty.getBean(TraceQueryService.class).read(new Request(1, scope.attemptId(), Purpose.DISPUTE, null, 1)), ACCESS_UNAVAILABLE);
                assertThat(faults.selects).isEmpty();
            }
        }
    }

    Scope fixture() {
        String key = UUID.randomUUID().toString();
        var revision = revision(key, 1); var form = form(key + "-form", revision, 1); UUID attempt = id(key + "-attempt");
        return tx(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
            proxy(new JdbcQuizRevisionRepository(named())).insert(revision);
            new FixedFormServiceImpl(proxy(new JdbcFixedFormRepository(named())), manager, metrics).createOrReplay(OWNER, form);
            var content = new ContentPolicy(revision.id(), 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
            var policy = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), metrics)
                    .resolve(new PolicyResolutionRequest(1, 1, "SHA-256", BuiltInPracticePreset.REFERENCE, content, List.of()));
            UUID snapshot = id(key + "-policy");
            new PolicySnapshotServiceImpl(proxy(new JdbcPolicySnapshotRepository(named())), manager, metrics)
                    .createOrReplay(OWNER, new PolicySnapshot(snapshot, policy));
            UUID user = id(key + "-user");
            sql.update("INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version) VALUES(?,?,?,'unused',TRUE,FALSE,FALSE,?,?,0)",
                    bytes(user), "query-" + user, user + "@example.invalid", Timestamp.from(NOW), Timestamp.from(NOW));
            new AttemptSubjectServiceImpl(proxy(new JdbcAttemptSubjectRepository(named())), manager, metrics)
                    .bindOrReplay(user, AttemptSubject.authenticated(attempt, user));
            new AttemptLifecycleServiceImpl(proxy(new JdbcLifecycleStateRepository(named())), manager, Clock.fixed(NOW, ZoneOffset.UTC), metrics).initialize(attempt);
            return new Scope(attempt, snapshot, form.id());
        });
    }

    void append(Scope scope, int count) {
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) writer.append(scope, new Event(1, UUID.randomUUID(), Type.ANSWER_SUBMITTED,
                    Actor.PARTICIPANT, new Data(UUID.randomUUID(), null,
                    new State(1, Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED),
                    new State(1, Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS), ReasonCode.ACCEPTED)));
        });
    }
    void grant(Scope scope, String username, Purpose purpose, Availability availability) {
        sql.update("""
                INSERT INTO fixture_trace_query_context VALUES(?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE availability=VALUES(availability)
                """, username, bytes(scope.attemptId()), purpose.name(), bytes(scope.policySnapshotId()), bytes(scope.formId()), availability.name());
    }
    Page read(Scope scope, String cursor, int limit) { return reader.read(new Request(1, scope.attemptId(), Purpose.DISPUTE, cursor, limit)); }
    static void login(String username) {
        var principal = User.withUsername(username).password("not-a-login").authorities("ROLE_OPERATOR").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    static void restoredPool() {
        var connections = new ArrayList<Connection>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                var connection = pool.getConnection(); connections.add(connection);
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                assertThat(connection.isReadOnly()).isFalse();
            }
        } catch (SQLException failure) { throw new AssertionError(failure); }
        finally { for (var connection : connections) try { connection.close(); } catch (SQLException failure) { throw new AssertionError(failure); } }
    }
    static UUID uuid(ResultSet row, String column) throws SQLException { var value = java.nio.ByteBuffer.wrap(row.getBytes(column)); return new UUID(value.getLong(), value.getLong()); }
    static TransactionTemplate tx(int isolation) { var value = new TransactionTemplate(manager); value.setIsolationLevel(isolation); value.setTimeout(20); return value; }
    static NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(source); }
    @SuppressWarnings("unchecked") static <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** Count actual returned SQL rows/bytes, including serialization, rather than relying on LIMIT alone. */
    static final class VolumeDataSource extends DelegatingDataSource {
        final List<Volume> volumes = new CopyOnWriteArrayList<>();
        record Volume(String sql, AtomicInteger rows, AtomicLong bytes) { }
        VolumeDataSource(DataSource target) { super(target); }
        void clear() { volumes.clear(); }
        @Override public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
                try {
                    var result = method.invoke(connection, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String statement)
                        return statement((PreparedStatement) result, statement);
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        Object statement(PreparedStatement target, String statement) {
            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, method, args) -> {
                try {
                    var result = method.invoke(target, args);
                    if (result instanceof ResultSet rows && method.getName().equals("executeQuery")) {
                        var volume = new Volume(statement, new AtomicInteger(), new AtomicLong()); volumes.add(volume);
                        return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (r, operation, values) -> {
                            try {
                                var value = operation.invoke(rows, values);
                                if (operation.getName().equals("next") && Boolean.TRUE.equals(value)) {
                                    volume.rows.incrementAndGet();
                                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                                        byte[] bytes = rows.getBytes(i); if (bytes != null) volume.bytes.addAndGet(bytes.length);
                                    }
                                }
                                return value;
                            } catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}
