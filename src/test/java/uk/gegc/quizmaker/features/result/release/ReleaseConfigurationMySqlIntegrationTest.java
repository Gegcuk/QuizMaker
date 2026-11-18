package uk.gegc.quizmaker.features.result.release;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository;
import uk.gegc.quizmaker.features.result.domain.repository.ReleaseConfigurationRepository;
import uk.gegc.quizmaker.features.attempt.application.transaction.*;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizPublicationRepository;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService.*;
import uk.gegc.quizmaker.features.result.application.impl.*;
import uk.gegc.quizmaker.features.result.domain.model.release.*;
import uk.gegc.quizmaker.features.result.infra.release.*;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcReleaseConfigurationRepository;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.result.release.ReleaseConfigurationTest.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

/** Real configuration writer, current SQL author checks and MySQL. Source revision metadata is
 * a bounded committed fixture; no real start, scheduled release or notification is claimed. */
@Tag("db-serial")
@org.junit.jupiter.api.parallel.Isolated
class ReleaseConfigurationMySqlIntegrationTest {
    static final String DATABASE = "release_configuration_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin, sql;
    static DataSourceTransactionManager manager;
    static NamedParameterJdbcTemplate named;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);
    UUID owner, stranger, quiz, revision;
    String principal;
    long role;
    HookRepository repository;
    ReleaseConfigurationServiceImpl service;
    enum Fault { INSERT, POINTER, RECEIPT }

    @BeforeAll static void database() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var source = new DriverManagerDataSource(base + DATABASE + options, user, password);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        sql = new JdbcTemplate(source); named = new NamedParameterJdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
    }
    @AfterAll static void cleanup() { if (admin != null) admin.execute("DROP DATABASE IF EXISTS " + DATABASE); }
    @BeforeEach void fixture() {
        owner = user(); stranger = user(); principal = owner.toString(); quiz = UUID.randomUUID(); revision = UUID.randomUUID();
        String name = "REL_" + UUID.randomUUID();
        sql.update("INSERT INTO roles(role_name,is_default) VALUES(?,false)", name);
        role = sql.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Long.class, name);
        sql.update("INSERT INTO user_roles(user_id,role_id) VALUES(UUID_TO_BIN(?),?)", owner.toString(), role);
        sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT ?,permission_id FROM permissions WHERE permission_name='QUIZ_UPDATE'", role);
        source(quiz, revision, owner);
        repository = new HookRepository(); service = instance(repository); login(principal);
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    ReleaseConfigurationServiceImpl instance(JdbcReleaseConfigurationRepository repo) {
        return new ReleaseConfigurationServiceImpl(new QuizReleaseConfigurationSource(
                transactional(QuizPublicationRepository.class, new JdbcQuizPublicationRepository(named), manager)),
                repo, CLOCK, new SimpleMeterRegistry(), manager);
    }
    static <T> T transactional(Class<T> contract, Object target, DataSourceTransactionManager transactions) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return contract.cast(proxy.getProxy());
    }
    static UUID user() {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO users(user_id,username,email,password) VALUES(UUID_TO_BIN(?),?,?,?)", id.toString(), id.toString(), id + "@example.invalid", "test-only");
        return id;
    }
    static void source(UUID quiz, UUID revision, UUID owner) {
        UUID category = UUID.randomUUID();
        sql.update("INSERT INTO categories(category_id,category_name) VALUES(UUID_TO_BIN(?),?)", category.toString(), category.toString());
        sql.update("INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,'PRIVATE','MEDIUM','DRAFT',10,false,false)", quiz.toString(), owner.toString(), category.toString(), quiz.toString());
        var question = new uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, uk.gegc.quizmaker.features.question.domain.model.Difficulty.EASY,
                "Fixture question", "{\"options\":[{\"id\":\"a\",\"text\":\"A\",\"correct\":true},{\"id\":\"b\",\"text\":\"B\",\"correct\":false}]}",
                null, null, null, null, CLOCK.instant(), List.of(new uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision.Option(UUID.randomUUID(), "a", 0),
                new uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision.Option(UUID.randomUUID(), "b", 1)));
        AttemptTransactions.readCommitted(manager).executeWithoutResult(s -> new uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository(named).insert(
                new uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision(revision, quiz, owner, 1, "Reference fixture", null, CLOCK.instant(),
                        List.of(new uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision.Item(UUID.randomUUID(), 0, question)))));
        sql.update("INSERT INTO quiz_publications(revision_id,quiz_id,published_by,canonical_version,content_hash,published_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?)", revision.toString(), quiz.toString(), owner.toString(), "a".repeat(64), java.sql.Timestamp.from(CLOCK.instant()));
    }
    static void login(String principal) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())); }
    Command command(long expected, ReleaseConfiguration rule) { return new Command(quiz, revision, UUID.randomUUID(), expected, rule); }
    <T> T rc(Supplier<T> action) { return AttemptTransactions.readCommitted(manager).execute(s -> action.get()); }
    int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE quiz_id=UUID_TO_BIN(?)", Integer.class, quiz.toString()); }

    @Test void publishReplaySupersedeAndReadCapturedReferenceAfterReconstruction() {
        var command = command(0, scheduled("2026-12-04T17:00:00", "Europe/London", null));
        assertThat(service.preview(quiz, revision, command.rule())).isEqualTo(command.rule());
        assertThat(count("result_release_configurations")).isZero();
        var a = service.publish(command);
        var b = service.publish(command(1, scheduled("2026-12-04T18:00:00", "Europe/London", null)));
        assertThat(service.current(quiz, revision).id()).isEqualTo(b.id());
        var previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            var restarted = instance(new JdbcReleaseConfigurationRepository(named));
            assertThat(restarted.read(quiz, revision, a.id())).isEqualTo(a);
            assertThat(restarted.publish(command).id()).isEqualTo(a.id());
            assertThat(restarted.publish(command).replayed()).isTrue();
        } finally { TimeZone.setDefault(previous); }
        var reader = new ReleaseConfigurationReaderImpl(repository, manager);
        assertThat(rc(() -> reader.readBound(owner, quiz, revision, a.id()))).isEqualTo(a);
        rejected(() -> rc(() -> reader.readBound(stranger, quiz, revision, a.id())), NOT_FOUND);
        rejected(() -> rc(() -> reader.readBound(owner, quiz, UUID.randomUUID(), a.id())), NOT_FOUND);
        rejected(() -> service.publish(new Command(quiz, revision, command.commandId(), 0, manual())), CONFLICT);
        rejected(() -> service.publish(command(0, manual())), STALE);
        assertThat(count("result_release_configurations")).isEqualTo(2);
        assertThat(count("result_release_configuration_receipts")).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_submission", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM result_release_configurations WHERE id=UUID_TO_BIN(?)", Integer.class, a.id().toString())).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(Fault.class)
    void reachedWriteFailureRollsBackVersionHeadAndReceiptThenRetrySucceeds(Fault fault) {
        var a = service.publish(command(0, manual()));
        var b = command(1, scheduled("2026-12-04T17:00:00", "Europe/London", null));
        repository.reached.set(false);
        repository.fault = fault;
        rejected(() -> service.publish(b), UNAVAILABLE);
        assertThat(repository.reached.get()).isTrue();
        assertThat(repository.injected.get()).isEqualTo(fault);
        assertThat(service.current(quiz, revision)).isEqualTo(a);
        assertThat(count("result_release_configurations")).isEqualTo(1);
        assertThat(count("result_release_configuration_receipts")).isEqualTo(1);
        repository.fault = null;
        assertThat(service.publish(b).publicationVersion()).isEqualTo(2);
        assertThat(service.publish(b).replayed()).isTrue();
    }
    @Test void outerRcRollbackRemovesPublicationAndCaughtFailureRemainsRollbackOnly() {
        var a = service.publish(command(0, manual()));
        var b = command(1, scheduled("2026-12-04T17:00:00", "Europe/London", null));
        repository.reached.set(false);
        assertThatThrownBy(() -> rc(() -> {
            var provisional = service.publish(b);
            assertThat(provisional.id()).isNotEqualTo(a.id());
            assertThat(provisional.publicationVersion()).isEqualTo(2);
            assertThat(count("result_release_configurations")).isEqualTo(2);
            throw new IllegalStateException("test-only rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.reached.get()).isTrue();
        assertThat(service.current(quiz, revision)).isEqualTo(a);
        repository.fault = Fault.RECEIPT;
        assertThatThrownBy(() -> rc(() -> { rejected(() -> service.publish(b), UNAVAILABLE); return true; }))
                .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(repository.injected.get()).isEqualTo(Fault.RECEIPT);
        assertThat(service.current(quiz, revision)).isEqualTo(a);
        repository.fault = null;
        assertThat(service.publish(b).publicationVersion()).isEqualTo(2);
    }
    @Test void currentAuthorityAndExactRevisionAreCheckedForReadsPreviewAndReplay() {
        var command = command(0, manual()); var saved = service.publish(command);
        sql.update("INSERT INTO user_roles(user_id,role_id) VALUES(UUID_TO_BIN(?),?)", stranger.toString(), role);
        login(stranger.toString());
        rejected(() -> service.publish(command), NOT_FOUND);
        rejected(() -> service.preview(quiz, revision, manual()), NOT_FOUND);
        rejected(() -> service.read(quiz, revision, saved.id()), NOT_FOUND);
        login(principal);
        rejected(() -> service.publish(new Command(quiz, UUID.randomUUID(), UUID.randomUUID(), 0, manual())), NOT_FOUND);
        sql.update("DELETE FROM role_permissions WHERE role_id=?", role);
        rejected(() -> service.publish(command), NOT_FOUND);
        sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT ?,permission_id FROM permissions WHERE permission_name='QUIZ_UPDATE'", role);
        assertThat(service.publish(command).replayed()).isTrue();
        sql.update("UPDATE users SET is_active=false WHERE user_id=UUID_TO_BIN(?)", owner.toString());
        rejected(() -> service.current(quiz, revision), NOT_FOUND);
        SecurityContextHolder.clearContext(); rejected(() -> service.publish(command), UNAUTHENTICATED);
        assertThat(count("result_release_configurations")).isEqualTo(1);
    }
    @Test void publicPublicationRetainsModerationAndDoesNotGrantManualReleaseAuthority() {
        sql.update("UPDATE quizzes SET visibility='PUBLIC' WHERE quiz_id=UUID_TO_BIN(?)", quiz.toString());
        rejected(() -> service.publish(command(0, manual())), NOT_FOUND);
        sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT ?,permission_id FROM permissions WHERE permission_name='QUIZ_MODERATE'", role);
        var saved = service.publish(command(0, manual()));
        assertThat(saved.rule().manualAuthority()).isEqualTo(ReleaseConfiguration.ManualAuthority.DEDICATED_ASSIGNMENT_AND_ORGANIZATION_PERMISSION);
    }
    @Test void incompatibleCallerIsRejectedBeforeAnyPublication() {
        var rr = new TransactionTemplate(manager); rr.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> rr.execute(s -> service.publish(command(0, manual())))).isInstanceOf(AttemptTransactionException.class);
        var ro = AttemptTransactions.readCommitted(manager); ro.setReadOnly(true);
        assertThatThrownBy(() -> ro.execute(s -> service.publish(command(0, manual())))).isInstanceOf(AttemptTransactionException.class);
        assertThat(count("result_release_configurations")).isZero();
    }
    @Test void sameCommandRaceReturnsOneDurableVersionAndDifferentStaleCommandsHaveOneWinner() throws Exception {
        var command = command(0, manual());
        var results = race(command, command);
        assertThat(results).allMatch(Publication.class::isInstance);
        assertThat(((Publication) results.get(0)).id()).isEqualTo(((Publication) results.get(1)).id());
        assertThat(results.stream().map(Publication.class::cast).filter(Publication::replayed).count()).isEqualTo(1);
        var different = race(command(1, scheduled("2026-12-04T17:00:00", "Europe/London", null)), command(1, scheduled("2026-12-04T18:00:00", "Europe/London", null)));
        assertThat(different.stream().filter(Publication.class::isInstance).count()).isEqualTo(1);
        assertThat(different.stream().filter(x -> x == STALE).count()).isEqualTo(1);
        assertThat(count("result_release_configurations")).isEqualTo(2);
    }
    List<Object> race(Command a, Command b) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Object>>();
            for (var command : List.of(a, b)) futures.add(pool.submit(() -> {
                login(principal); ready.countDown();
                try { assertThat(start.await(15, TimeUnit.SECONDS)).isTrue(); return service.publish(command); }
                catch (ReleaseConfigurationException failure) { return failure.reason(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void anotherQuizWithTheSameAuthorCompletesWhileFirstPublicationRemainsOpen() throws Exception {
        UUID peerQuiz = UUID.randomUUID(), peerRevision = UUID.randomUUID(); source(peerQuiz, peerRevision, owner);
        repository.hold = true; var pool = Executors.newFixedThreadPool(2);
        try {
            var held = pool.submit(() -> { login(principal); try { return service.publish(command(0, manual())); } finally { SecurityContextHolder.clearContext(); } });
            assertThat(repository.inserted.await(20, TimeUnit.SECONDS)).isTrue();
            var peer = pool.submit(() -> { login(principal); try { return service.publish(new Command(peerQuiz, peerRevision, UUID.randomUUID(), 0, manual())); } finally { SecurityContextHolder.clearContext(); } });
            var committed = peer.get(20, TimeUnit.SECONDS);
            assertThat(service.read(peerQuiz, peerRevision, committed.id()).id()).isEqualTo(committed.id());
            assertThat(held.isDone()).isFalse();
            repository.release.countDown(); assertThat(held.get(20, TimeUnit.SECONDS).publicationVersion()).isEqualTo(1);
        } finally { repository.release.countDown(); pool.shutdownNow(); }
    }
    @Test void databaseGuardsRetainImmutableEvidenceAndExactCrossScopeBindings() {
        var a = service.publish(command(0, manual()));
        assertThatThrownBy(() -> sql.update("UPDATE result_release_configurations SET canonical_configuration='{}' WHERE id=UUID_TO_BIN(?)", a.id().toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE result_release_configuration_receipts SET request_hash=? WHERE quiz_id=UUID_TO_BIN(?)", "b".repeat(64), quiz.toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM result_release_configurations WHERE id=UUID_TO_BIN(?)", a.id().toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.current(quiz, revision)).isEqualTo(a);
    }
    @Test void committedPublicationWithLostAcknowledgementReconcilesOriginalIdentityAfterRestart() {
        var lose = new AtomicBoolean(true);
        org.springframework.transaction.PlatformTransactionManager uncertain = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                manager.commit(status);
                if (lose.getAndSet(false)) throw new org.springframework.transaction.TransactionSystemException("test-only lost commit acknowledgement");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { manager.rollback(status); }
        };
        var uncertainService = new ReleaseConfigurationServiceImpl(new QuizReleaseConfigurationSource(
                transactional(QuizPublicationRepository.class, new JdbcQuizPublicationRepository(named), manager)),
                repository, CLOCK, new SimpleMeterRegistry(), uncertain);
        var command = command(0, manual());
        rejected(() -> uncertainService.publish(command), UNKNOWN_OUTCOME);
        assertThat(repository.reached.get()).isTrue();
        var committed = service.current(quiz, revision);
        var restarted = instance(new JdbcReleaseConfigurationRepository(named));
        var replayed = restarted.publish(command);
        assertThat(replayed.id()).isEqualTo(committed.id());
        assertThat(replayed.replayed()).isTrue();
        assertThat(count("result_release_configurations")).isEqualTo(1);
        assertThat(count("result_release_configuration_receipts")).isEqualTo(1);
    }
    @Test void coherentlyStoredFutureVersionFailsClosedWithoutRewritingTheCurrentBinding() {
        var a = service.publish(command(0, manual()));
        String future = new ReleaseConfigurationFormat().encode(manual()).replace("\"version\":1", "\"version\":2");
        UUID futureId = UUID.randomUUID();
        sql.update("INSERT INTO result_release_configurations(id,quiz_id,revision_id,publication_version,configuration_hash,canonical_configuration,published_by,published_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),2,?,?,UUID_TO_BIN(?),?)",
                futureId.toString(), quiz.toString(), revision.toString(), ReleaseConfigurationFormat.hash(future), future, owner.toString(), java.sql.Timestamp.from(CLOCK.instant()));
        rejected(() -> service.read(quiz, revision, futureId), UNSUPPORTED_VERSION);
        assertThat(service.current(quiz, revision)).isEqualTo(a);
        assertThat(count("result_release_configuration_receipts")).isEqualTo(1);
    }
    enum AuthorizationRead {
        USER("SELECT BIN_TO_UUID(user_id) id FROM users", "actor"),
        PERMISSIONS("SELECT p.permission_name FROM user_roles", "actor"),
        QUIZ("visibility, estimated_time_min FROM quizzes", "draft"),
        REVISION("FROM quiz_publications p WHERE", "revision");
        final String sqlFragment, method;
        AuthorizationRead(String sqlFragment, String method) { this.sqlFragment = sqlFragment; this.method = method; }
    }
    @ParameterizedTest @EnumSource(AuthorizationRead.class)
    void authorizationReadFailureIsSanitizedBeforeNestedTransactionLogsAndRollsBack(AuthorizationRead read) {
        var faults = new AuthorizationFaultSource(manager.getDataSource());
        var transactions = new DataSourceTransactionManager(faults);
        var jdbc = new JdbcTemplate(faults);
        var parameters = new NamedParameterJdbcTemplate(faults);
        var authors = transactional(QuizPublicationRepository.class, new JdbcQuizPublicationRepository(parameters), transactions);
        var store = transactional(ReleaseConfigurationRepository.class, new JdbcReleaseConfigurationRepository(parameters), transactions);
        var proxied = new ReleaseConfigurationServiceImpl(new QuizReleaseConfigurationSource(authors), store, CLOCK, new SimpleMeterRegistry(), transactions);
        var initial = command(0, manual());
        var saved = proxied.publish(initial); // Successful control through the same real proxies and SQL.
        var proposed = command(1, scheduled("2026-12-04T17:00:00", "Europe/London", null));
        String originalTitle = sql.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quiz.toString());
        var requestThread = Thread.currentThread();
        var captured = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (Thread.currentThread() == requestThread) { event.prepareForDeferredProcessing(); super.append(event); }
            }
        };
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var loggers = List.of((Logger) LoggerFactory.getLogger(TransactionInterceptor.class),
                (Logger) LoggerFactory.getLogger(TransactionTemplate.class),
                (Logger) LoggerFactory.getLogger(DataSourceTransactionManager.class));
        var levels = new HashMap<Logger, Level>();
        captured.setContext(root.getLoggerContext()); captured.start(); root.addAppender(captured);
        loggers.forEach(logger -> { levels.put(logger, logger.getLevel()); logger.setLevel(Level.TRACE); });
        try {
            faults.read = read;
            assertThatThrownBy(() -> AttemptTransactions.readCommitted(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE quizzes SET title='before caught authorization failure' WHERE quiz_id=UUID_TO_BIN(?)", quiz.toString());
                rejected(() -> proxied.publish(proposed), UNAVAILABLE);
                assertThat(faults.reached).hasValue(1);
                assertThat(status.isRollbackOnly()).isTrue();
                jdbc.update("UPDATE quizzes SET title='after caught authorization failure' WHERE quiz_id=UUID_TO_BIN(?)", quiz.toString());
                assertThat(jdbc.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quiz.toString()))
                        .isEqualTo("after caught authorization failure");
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            faults.read = null;
            assertThat(faults.reached).hasValue(1);
            assertThat(sql.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quiz.toString())).isEqualTo(originalTitle);
            assertThat(service.current(quiz, revision)).isEqualTo(saved);
            assertThat(count("result_release_configurations")).isEqualTo(1);
            assertThat(count("result_release_configuration_receipts")).isEqualTo(1);
            assertThat(captured.list).anySatisfy(event -> {
                assertThat(event.getLoggerName()).isEqualTo(TransactionInterceptor.class.getName());
                assertThat(event.getLevel()).isEqualTo(Level.TRACE);
                assertThat(event.getFormattedMessage()).contains("JdbcQuizPublicationRepository." + read.method + "]", "after exception");
            });
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(AuthorizationFaultSource.PRIVATE, "SQL [", read.sqlFragment);
                if (event.getThrowableProxy() != null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain(AuthorizationFaultSource.PRIVATE, "SQL [", read.sqlFragment);
            });
            assertThat(proxied.publish(initial).replayed()).isTrue();
            assertThat(proxied.publish(proposed).publicationVersion()).isEqualTo(2);
            assertThat(proxied.read(quiz, revision, saved.id())).isEqualTo(saved);
        } finally {
            faults.read = null;
            loggers.forEach(logger -> logger.setLevel(levels.get(logger)));
            root.detachAppender(captured); captured.stop();
        }
    }
    static class AuthorizationFaultSource extends DelegatingDataSource {
        static final String PRIVATE = "release-authorization-private-driver-diagnostic";
        AuthorizationRead read;
        final AtomicInteger reached = new AtomicInteger();
        AuthorizationFaultSource(javax.sql.DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object value = method.invoke(connection, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String statement && value instanceof PreparedStatement prepared)
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, operation, arguments) -> {
                            try {
                                Object result = operation.invoke(prepared, arguments);
                                if (operation.getName().equals("executeQuery") && read != null && statement.contains(read.sqlFragment)) {
                                    if (result instanceof ResultSet rows) rows.close();
                                    reached.incrementAndGet();
                                    var failure = new SQLException(PRIVATE, "08006", new IllegalStateException(PRIVATE + "-cause"));
                                    failure.addSuppressed(new IllegalStateException(PRIVATE + "-suppressed"));
                                    failure.setNextException(new SQLException(PRIVATE + "-next", "08006"));
                                    throw failure;
                                }
                                return result;
                            } catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    return value;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
    class HookRepository extends JdbcReleaseConfigurationRepository {
        Fault fault; final AtomicBoolean reached = new AtomicBoolean(); boolean hold;
        final AtomicReference<Fault> injected = new AtomicReference<>();
        final CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        HookRepository() { super(named); }
        @Override public void insert(Publication p, UUID actor) {
            super.insert(p, actor); reached.set(true);
            if (hold && p.quizId().equals(quiz)) {
                inserted.countDown();
                try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Test interrupted"); }
            }
            fail(Fault.INSERT);
        }
        @Override public void pointTo(Publication p) { super.pointTo(p); fail(Fault.POINTER); }
        @Override public void record(Command c, UUID actor, String hash, Publication p, Instant at) { super.record(c, actor, hash, p, at); fail(Fault.RECEIPT); }
        void fail(Fault checkpoint) {
            if (fault == checkpoint) { injected.set(checkpoint); throw new IllegalStateException("test-only private failure"); }
        }
    }
}
