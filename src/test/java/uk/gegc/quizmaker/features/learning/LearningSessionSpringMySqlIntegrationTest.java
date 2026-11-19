package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.Advised;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningSessionServiceImpl;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.infra.repository.JdbcLearningSessionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.TransactionConfig;
import uk.gegc.quizmaker.shared.transaction.SafeJpaTransactionManager;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.learning.LearningSessionTest.rejected;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Production service, JDBC, UserRepository and JPA transaction proxies. Only content authority
 * is a fixture; real Learning source eligibility/public activation remains #578/#692/#667. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = LearningSessionSpringMySqlIntegrationTest.SchemaCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Import({LearningSessionServiceImpl.class, JdbcLearningSessionRepository.class, AuthenticatedUserLookupImpl.class,
        TransactionConfig.class, LearningSessionSpringMySqlIntegrationTest.Config.class})
class LearningSessionSpringMySqlIntegrationTest {
    private static final String SCHEMA = "learning_session_spring_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PORT = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
    private static final String ADMIN = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final UUID LEARNER = UUID.randomUUID(), QUIZ = UUID.randomUUID(), REVISION = UUID.randomUUID();
    private static final Instant TIME = Instant.parse("2026-11-19T09:00:00.123456Z");

    static String server() {
        if (!PORT.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        return "jdbc:mysql://127.0.0.1:" + PORT + "/";
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> server() + SCHEMA + OPTIONS + "&createDatabaseIfNotExist=true");
        properties.add("spring.datasource.username", () -> ADMIN);
        properties.add("spring.datasource.password", () -> PASSWORD);
    }
    public static class SchemaCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext ignored) {
            new JdbcTemplate(new DriverManagerDataSource(server() + OPTIONS, ADMIN, PASSWORD))
                    .execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean Clock clock() { return Clock.fixed(TIME, ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean LearningSessionSourceVerifier sourceVerifier() {
            return (learner, source) -> learner.equals(LEARNER) && source.quizId().equals(QUIZ);
        }
        @Bean FaultJdbc faultJdbc(JdbcTemplate jdbc) { return new FaultJdbc(jdbc); }
    }

    @Autowired LearningSessionService service;
    @Autowired AuthenticatedUserLookup users;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate sql;
    @Autowired FaultJdbc faultJdbc;
    @MockitoSpyBean JdbcLearningSessionRepository repository;

    private JdbcLearningSessionRepository target() { return AopTestUtils.getUltimateTargetObject(repository); }

    @BeforeEach void fixture() {
        faultJdbc.reset();
        sql.update("""
                INSERT IGNORE INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version)
                VALUES(UUID_TO_BIN(?),'anna','anna@example.invalid','not-a-login',1,0,0,'2026-01-01','2026-01-01',0)
                """, LEARNER.toString());
        sql.update("""
                INSERT IGNORE INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,'Learning source',?)
                """, REVISION.toString(), QUIZ.toString(), LEARNER.toString(), java.sql.Timestamp.from(TIME));
        var principal = User.withUsername("anna").password("unused").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @AfterEach void clean() {
        faultJdbc.reset();
        sql.execute("DROP TRIGGER IF EXISTS learning_receipt_test_failure");
        SecurityContextHolder.clearContext();
        sql.update("DELETE FROM learning_session_create_receipt");
        sql.update("DELETE FROM learning_session");
    }

    @Test void productionProxiesShareActualRcAndRestorePoolIsolation() {
        assertThat(manager).isInstanceOf(SafeJpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(AopUtils.isAopProxy(repository)).isTrue();
        assertThat(AopUtils.isAopProxy(users)).isTrue();
        var isolation = new AtomicBoolean();
        doAnswer(call -> {
            isolation.set("READ-COMMITTED".equals(sql.queryForObject("SELECT @@transaction_isolation", String.class)));
            return call.callRealMethod();
        }).when(target()).recordCreation(any());
        var command = command();
        var saved = service.create(command).session();
        assertThat(isolation).isTrue();
        assertThat(service.read(saved.id())).isEqualTo(saved);
        assertThat(service.create(command).replayed()).isTrue();
        assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
    }

    @Test void participatingJpaRollbackRemovesBothAuthoritativeWrites() {
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            var saved = service.create(command()).session();
            assertThat(service.read(saved.id())).isEqualTo(saved);
            status.setRollbackOnly();
        });
        assertEmpty();
        assertThat(service.create(command()).replayed()).isFalse();
    }

    @Test void reachedRepositoryFailureRollsBackUnderRealJpaManagerThenRetryWorks() {
        var reached = new AtomicBoolean();
        doAnswer(call -> {
            call.callRealMethod();
            reached.set(true);
            throw new IllegalStateException("private-diagnostic");
        }).when(target()).recordCreation(any());
        var command = command();
        rejected(() -> service.create(command), PERSISTENCE_UNAVAILABLE);
        assertThat(reached).isTrue();
        assertEmpty();
        doCallRealMethod().when(target()).recordCreation(any());
        assertThat(service.create(command).replayed()).isFalse();
    }

    enum FailurePath {
        SOURCE("SELECT id FROM quiz_revisions", "sourceExists", false),
        SESSION_INSERT("INSERT INTO learning_session\n", "insertOrRead", false),
        RECEIPT_INSERT("INSERT INTO learning_session_create_receipt", "recordCreation", false),
        SESSION_READ("WHERE s.id=", "find", true),
        RECEIPT_READ("SELECT schema_version,session_id,recorded_at", "hasCreationReceipt", true),
        RECEIPT_REPLAY("SELECT schema_version,session_id,recorded_at", "hasCreationReceipt", true);

        final String sqlPart, method;
        final boolean existing;
        FailurePath(String sqlPart, String method, boolean existing) {
            this.sqlPart = sqlPart; this.method = method; this.existing = existing;
        }
    }

    enum IdentityOperation { CREATE, READ, REPLAY }

    static Stream<Arguments> identityFailures() {
        return Arrays.stream(IdentityOperation.values()).flatMap(operation -> Stream.of(0, 1205, 1213)
                .map(code -> Arguments.of(operation, code)));
    }

    @ParameterizedTest @MethodSource("identityFailures")
    void identityFailuresAreCleanBeforeLookupProxyLogsDuringCreateReadAndReplay(IdentityOperation operation, int code) {
        var command = command();
        var original = operation == IdentityOperation.CREATE ? null : service.create(command).session();
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var fault = new IdentityQueryFault(userRepository); var logs = new TransactionLogs()) {
            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                service.create(command()); // Successful writes in the same caller must also roll back.
                fault.arm("lockActiveAuthenticatedUserId", code);
                Throwable outward = catchThrowable(() -> {
                    if (operation == IdentityOperation.READ) service.read(original.id());
                    else service.create(command);
                });
                assertServiceFailure(outward, code);
                assertThat(fault.reached).isTrue();
                assertThat(status.isRollbackOnly()).isTrue();
                assertCounts(original == null ? 1 : 2); // Identity failed before any target session write.
            })).isInstanceOf(UnexpectedRollbackException.class);
            logs.assertCleanFailure(AuthenticatedUserLookupImpl.class, "lockActiveUserId");
            assertCounts(original == null ? 0 : 1); // Fresh reads after the caller actually rolled back.
            fault.disarm();
            var retry = service.create(command);
            assertThat(retry.replayed()).isEqualTo(original != null);
            if (original != null) assertThat(retry.session()).isEqualTo(original);
            assertThat(service.read(retry.session().id())).isEqualTo(retry.session());
            assertCounts(1);
        }
    }

    static Stream<Arguments> directIdentityFailures() {
        return Stream.of(false, true).flatMap(locking -> Stream.of(0, 1205, 1213).map(code -> Arguments.of(locking, code)));
    }

    @ParameterizedTest @MethodSource("directIdentityFailures")
    void bothLookupProxyBoundariesDiscardDriverGraphsAfterARealQuery(boolean locking, int code) {
        try (var fault = new IdentityQueryFault(userRepository); var logs = new TransactionLogs()) {
            Runnable call = () -> {
                fault.arm(locking ? "lockActiveAuthenticatedUserId" : "findActiveAuthenticatedUserId", code);
                Throwable outward = catchThrowable(() -> {
                    if (locking) users.lockActiveUserId("anna"); else users.findActiveUserId("anna");
                });
                assertThat(outward).isExactlyInstanceOf(DataAccessResourceFailureException.class)
                        .hasMessage("Database operation failed");
                assertThat(outward.getSuppressed()).isEmpty();
                if (code == 0) assertThat(outward).hasNoCause();
                else assertSafeConflict(outward.getCause(), code);
                assertThat(fault.reached).isTrue();
            };
            if (locking) {
                var outer = new TransactionTemplate(manager);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                    call.run(); assertThat(status.isRollbackOnly()).isTrue();
                })).isInstanceOf(UnexpectedRollbackException.class);
            } else call.run();
            logs.assertCleanFailure(AuthenticatedUserLookupImpl.class, locking ? "lockActiveUserId" : "findActiveUserId");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            fault.disarm();
            if (locking) {
                var control = new TransactionTemplate(manager);
                control.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                control.executeWithoutResult(status -> assertThat(users.lockActiveUserId("anna")).contains(LEARNER));
            } else assertThat(users.findActiveUserId("anna")).contains(LEARNER);
            assertEmpty();
        }
    }

    /** Add one outer test advice; proceed through the unchanged real Spring Data/JPA query first. */
    static class IdentityQueryFault implements AutoCloseable {
        final Advised repository;
        final MethodInterceptor advice;
        String query;
        int code;
        boolean reached;
        IdentityQueryFault(UserRepository users) {
            assertThat(AopUtils.isAopProxy(users)).isTrue();
            repository = (Advised) users;
            advice = invocation -> {
                Object result = invocation.proceed();
                if (query == null || reached || !invocation.getMethod().getName().equals(query)) return result;
                assertThat(result).isEqualTo(Optional.of(LEARNER));
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                reached = true;
                var raw = new SQLException("LEARNING_PRIVATE_IDENTITY_DRIVER", code == 1213 ? "40001" : "HY000", code,
                        new IllegalStateException("LEARNING_PRIVATE_IDENTITY_CAUSE"));
                raw.setNextException(new SQLException("LEARNING_PRIVATE_IDENTITY_NEXT"));
                raw.addSuppressed(new IllegalStateException("LEARNING_PRIVATE_IDENTITY_SUPPRESSED"));
                var failure = new DataAccessResourceFailureException("LEARNING_PRIVATE_IDENTITY_OUTER", raw);
                failure.addSuppressed(new IllegalStateException("LEARNING_PRIVATE_IDENTITY_TOP_SUPPRESSED"));
                throw failure;
            };
            repository.addAdvice(0, advice);
        }
        void arm(String query, int code) { this.query = query; this.code = code; reached = false; }
        void disarm() { query = null; }
        @Override public void close() { assertThat(repository.removeAdvice(advice)).isTrue(); }
    }

    static Stream<Arguments> serviceFailures() {
        return Arrays.stream(FailurePath.values()).flatMap(path -> Stream.of(0, 1205, 1213).map(code -> Arguments.of(path, code)));
    }

    @ParameterizedTest @MethodSource("serviceFailures")
    void serviceFailuresAreCleanBeforeNestedProxyLogsAndRollBackCaughtOuterWrites(FailurePath path, int code) {
        var command = command();
        var original = path.existing ? service.create(command).session() : null;
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var logs = new TransactionLogs()) {
            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                service.create(command()); // A reached successful control write must also roll back.
                faultJdbc.arm(path, code);
                Throwable outward = catchThrowable(() -> {
                    if (path == FailurePath.SESSION_READ || path == FailurePath.RECEIPT_READ) service.read(original.id());
                    else service.create(command);
                });
                assertServiceFailure(outward, code);
                assertThat(faultJdbc.reached).isTrue();
                assertThat(status.isRollbackOnly()).isTrue();
                int baseline = path.existing ? 2 : 1;
                assertThat(faultJdbc.sessionsAtFailure).isEqualTo(baseline +
                        (path == FailurePath.SESSION_INSERT || path == FailurePath.RECEIPT_INSERT ? 1 : 0));
                assertThat(faultJdbc.receiptsAtFailure).isEqualTo(baseline + (path == FailurePath.RECEIPT_INSERT ? 1 : 0));
            })).isInstanceOf(UnexpectedRollbackException.class);
            logs.assertCleanFailure(path.method);
        } finally { faultJdbc.reset(); }
        assertCounts(path.existing ? 1 : 0); // Fresh transactions, not the rolled-back caller's context.
        var retry = service.create(command);
        assertThat(retry.replayed()).isEqualTo(path.existing);
        if (original != null) assertThat(retry.session()).isEqualTo(original);
        assertThat(service.read(retry.session().id())).isEqualTo(retry.session());
        assertCounts(1);
    }

    static Stream<Arguments> repositoryFailures() {
        return Arrays.stream(FailurePath.values()).filter(path -> path != FailurePath.RECEIPT_REPLAY)
                .flatMap(path -> Stream.of(0, 1205, 1213).map(code -> Arguments.of(path, code)));
    }

    @ParameterizedTest @MethodSource("repositoryFailures")
    void repositoryFailureGraphsKeepOnlySafeConflictEvidenceAtTheirProxyBoundary(FailurePath path, int code) {
        var saved = service.create(command()).session();
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var logs = new TransactionLogs()) {
            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                faultJdbc.arm(path, code);
                Throwable outward = catchThrowable(() -> {
                    switch (path) {
                        case SOURCE -> repository.sourceExists(saved.source());
                        case SESSION_INSERT -> repository.insertOrRead(saved);
                        case RECEIPT_INSERT -> {
                            sql.update("DELETE FROM learning_session_create_receipt");
                            repository.recordCreation(saved);
                        }
                        case SESSION_READ -> repository.find(LEARNER, saved.id());
                        case RECEIPT_READ -> repository.hasCreationReceipt(saved);
                        default -> throw new AssertionError(path);
                    }
                });
                assertThat(outward).isInstanceOf(DataAccessException.class).hasMessage("Database operation failed");
                assertThat(outward.getSuppressed()).isEmpty();
                if (code == 0) assertThat(outward).hasNoCause();
                else assertSafeConflict(outward.getCause(), code);
                assertThat(faultJdbc.reached).isTrue();
                assertThat(status.isRollbackOnly()).isTrue();
            })).isInstanceOf(UnexpectedRollbackException.class);
            logs.assertCleanFailure(path.method);
        } finally { faultJdbc.reset(); }
        assertCounts(1);
        assertThat(service.read(saved.id())).isEqualTo(saved);
    }

    @Test void realMysqlSignalIsSanitizedBeforeNestedTraceAndSameCommandCanRetry() {
        sql.execute("""
                CREATE TRIGGER learning_receipt_test_failure BEFORE INSERT ON learning_session_create_receipt
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LEARNING_PRIVATE_MYSQL_DIAGNOSTIC'
                """);
        var command = command();
        try (var logs = new TransactionLogs()) {
            assertServiceFailure(catchThrowable(() -> service.create(command)), 0);
            var raw = faultJdbc.nativeFailure;
            assertThat(raw).isNotNull();
            assertThat(raw.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, failure -> {
                assertThat(failure.getSQLState()).isEqualTo("45000");
                assertThat(failure.getMessage()).contains("LEARNING_PRIVATE_MYSQL_DIAGNOSTIC");
            });
            logs.assertCleanFailure("recordCreation");
        } finally { sql.execute("DROP TRIGGER learning_receipt_test_failure"); faultJdbc.reset(); }
        assertEmpty();
        var saved = service.create(command);
        assertThat(saved.replayed()).isFalse();
        assertThat(service.create(command).session()).isEqualTo(saved.session());
        assertCounts(1);
    }

    private static void assertServiceFailure(Throwable outward, int code) {
        if (code == 0) assertThat(outward).isInstanceOfSatisfying(LearningSessionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(PERSISTENCE_UNAVAILABLE));
        else assertSafeConflict(outward, code);
        assertThat(outward).hasNoCause();
        assertThat(outward.getSuppressed()).isEmpty();
    }

    private static void assertSafeConflict(Throwable outward, int code) {
        assertThat(outward).isInstanceOfSatisfying(DatabaseConflictException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(code == 1205 ? DatabaseConflictException.Kind.LOCK_TIMEOUT
                    : DatabaseConflictException.Kind.DEADLOCK);
            assertThat(failure.outcome()).isEqualTo(code == 1205 ? "in_progress" : "retryable");
            assertThat(failure).hasNoCause();
            assertThat(failure.getSuppressed()).isEmpty();
        });
    }

    /** Execute real SQL first, then inject a driver graph beneath the repository interceptor. */
    static class FaultJdbc extends NamedParameterJdbcTemplate {
        FailurePath path;
        int code, sessionsAtFailure, receiptsAtFailure;
        boolean reached;
        DataAccessException nativeFailure;
        FaultJdbc(JdbcTemplate jdbc) { super(jdbc); }
        void arm(FailurePath path, int code) { this.path = path; this.code = code; this.reached = false; }
        void reset() { path = null; reached = false; nativeFailure = null; }
        @Override public int update(String statement, SqlParameterSource arguments) {
            final int count;
            try { count = super.update(statement, arguments); }
            catch (DataAccessException failure) { nativeFailure = failure; throw failure; }
            after(statement); return count;
        }
        @Override public <T> List<T> query(String statement, SqlParameterSource arguments, RowMapper<T> mapper) {
            var rows = super.query(statement, arguments, mapper); after(statement); return rows;
        }
        private void after(String statement) {
            if (path == null || reached || !statement.contains(path.sqlPart)) return;
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            reached = true;
            sessionsAtFailure = getJdbcOperations().queryForObject("SELECT COUNT(*) FROM learning_session", Integer.class);
            receiptsAtFailure = getJdbcOperations().queryForObject("SELECT COUNT(*) FROM learning_session_create_receipt", Integer.class);
            var raw = new SQLException("LEARNING_PRIVATE_DRIVER_DIAGNOSTIC", code == 1213 ? "40001" : "HY000", code,
                    new IllegalStateException("LEARNING_PRIVATE_CAUSE"));
            raw.addSuppressed(new IllegalStateException("LEARNING_PRIVATE_SUPPRESSED"));
            raw.setNextException(new SQLException("LEARNING_PRIVATE_NEXT"));
            var failure = new UncategorizedSQLException("execute", statement + " /* LEARNING_PRIVATE_SQL */", raw);
            failure.addSuppressed(new IllegalStateException("LEARNING_PRIVATE_TOP_SUPPRESSED"));
            throw failure;
        }
    }

    /** Capture only this request thread, including eagerly rendered causes and suppressed errors. */
    static class TransactionLogs implements AutoCloseable {
        final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(TransactionInterceptor.class),
                (Logger) LoggerFactory.getLogger(TransactionTemplate.class),
                (Logger) LoggerFactory.getLogger(SafeJpaTransactionManager.class));
        final Map<Logger, Level> previous = new HashMap<>();
        final ListAppender<ILoggingEvent> captured;
        TransactionLogs() {
            Thread request = Thread.currentThread();
            captured = new ListAppender<>() {
                @Override protected void append(ILoggingEvent event) {
                    if (Thread.currentThread() == request) { event.prepareForDeferredProcessing(); super.append(event); }
                }
            };
            captured.setContext(loggers.get(0).getLoggerContext()); captured.start();
            loggers.forEach(logger -> { previous.put(logger, logger.getLevel()); logger.addAppender(captured); logger.setLevel(Level.TRACE); });
        }
        void assertCleanFailure(String method) {
            assertCleanFailure(JdbcLearningSessionRepository.class, method);
        }
        void assertCleanFailure(Class<?> boundary, String method) {
            assertThat(captured.list).anySatisfy(event -> {
                assertThat(event.getLoggerName()).isEqualTo(TransactionInterceptor.class.getName());
                assertThat(event.getLevel()).isEqualTo(Level.TRACE);
                assertThat(event.getFormattedMessage()).contains(boundary.getSimpleName() + "." + method, "after exception");
            });
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("LEARNING_PRIVATE_");
                if (event.getThrowableProxy() != null)
                    assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain("LEARNING_PRIVATE_");
            });
        }
        @Override public void close() {
            loggers.forEach(logger -> { logger.detachAppender(captured); logger.setLevel(previous.get(logger)); });
            captured.stop();
        }
    }

    private static LearningSessionService.Create command() {
        return new LearningSessionService.Create(1, UUID.randomUUID(), new LearningSession.Source(1, QUIZ, REVISION, 1), FsrsManifest.approved());
    }
    private void assertEmpty() {
        assertCounts(0);
    }
    private void assertCounts(int count) {
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session", Integer.class)).isEqualTo(count);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session_create_receipt", Integer.class)).isEqualTo(count);
    }
}
