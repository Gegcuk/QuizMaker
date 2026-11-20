package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningSessionServiceImpl;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningSessionRepository;
import uk.gegc.quizmaker.features.learning.infra.repository.JdbcLearningSessionRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionSection;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionCaseBlock;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.LearningSessionTest.rejected;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Real full Flyway schema, #597 revision writes and #575 SQL/transactions. Identity lookup and
 * source authority are named SQL/source contract doubles; no public #578/#692/#667 claim. */
@Tag("db-serial")
class LearningSessionMySqlIntegrationTest {
    private static final String SCHEMA = "learning_session_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID ANNA = UUID.randomUUID(), BOB = UUID.randomUUID();
    private static final Instant TIME = Instant.parse("2026-11-19T09:00:00.123456Z");
    private static JdbcTemplate admin, sql;
    private static ObservedSource source;
    private static DataSourceTransactionManager manager;
    private static LearningSession.Source quiz;
    private static Map<String, List<Map<String, Object>>> legacyBefore;
    private static boolean created;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AtomicBoolean access = new AtomicBoolean(true);
    private final Set<LearningSession.Source> permitted = ConcurrentHashMap.newKeySet();
    private HookRepository repository;
    private LearningSessionService service;

    enum Fault { AFTER_SESSION, AFTER_RECEIPT, BEFORE_COMMIT }

    @BeforeAll static void database() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        source = new ObservedSource(new DriverManagerDataSource(base + SCHEMA + options, user, password));
        sql = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        migrate("117");
        for (var entry : Map.of(ANNA, "anna", BOB, "bob").entrySet()) sql.update("""
                INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version)
                VALUES(UUID_TO_BIN(?),?,?,'not-a-login',1,0,0,'2026-01-01','2026-01-01',0)
                """, entry.getKey().toString(), entry.getValue(), entry.getValue() + "@example.invalid");
        quiz = revision(UUID.randomUUID(), 1);
        legacyFixture();
        legacyBefore = legacyRows();
        migrate("118");
    }

    @BeforeEach void setup() {
        repository = new HookRepository();
        permitted.add(quiz);
        service = sessions(repository, manager, Clock.fixed(TIME.plusNanos(789), ZoneOffset.UTC));
        login("anna");
        source.statements.get().clear();
        source.lostCommits.set(0);
    }

    @AfterEach void clean() {
        SecurityContextHolder.clearContext();
        source.loseCommit.set(false);
        source.statements.get().clear();
        sql.update("DELETE FROM learning_session_create_receipt");
        sql.update("DELETE FROM learning_session");
        sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE WHERE username IN ('anna','bob')");
        metrics.close();
    }

    @AfterAll static void dropSchema() {
        if (created) admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
    }

    @Test void additiveMigrationRetainsRealLegacyRowsAndCreatesOnlySeparateEmptyTables() {
        assertThat(legacyRows()).isEqualTo(legacyBefore);
        assertCounts(0);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='118' AND success=1", Integer.class)).isOne();
        assertThat(sql.queryForList("""
                SELECT referenced_table_name FROM information_schema.referential_constraints
                WHERE constraint_schema=? AND table_name IN ('learning_session','learning_session_create_receipt')
                """, String.class, SCHEMA)).containsExactlyInAnyOrder("quiz_revisions", "learning_session");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM spaced_repetition_entry", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM repetition_review_log", Integer.class)).isZero();
    }

    @Test void createReplayAndFreshComponentsKeepExactIdentityManifestClockAndOriginalRevision() {
        var command = command(quiz);
        var first = service.create(command);
        assertThat(first.replayed()).isFalse();
        assertThat(first.session().learnerId()).isEqualTo(ANNA);
        assertThat(first.session().createdAt()).isEqualTo(TIME);
        assertThat(first.session().updatedAt()).isEqualTo(TIME);
        assertThat(first.session().status()).isEqualTo(LearningSession.Status.CREATED);
        assertThat(first.session().version()).isOne();
        assertThat(first.session().source()).isEqualTo(quiz);
        assertThat(first.session().configuration()).isEqualTo(FsrsManifest.approved());
        var second = revision(quiz.quizId(), 8);
        permitted.add(second);
        var restarted = sessions(new HookRepository(), manager, Clock.fixed(TIME.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(restarted.create(command)).isEqualTo(new LearningSessionService.Result(first.session(), true));
        assertThat(restarted.read(first.session().id())).isEqualTo(first.session());
        rejected(() -> restarted.create(new LearningSessionService.Create(1, command.commandId(), second,
                command.configuration())), CONFLICT);
        assertThat(restarted.read(first.session().id())).isEqualTo(first.session());
        assertCounts(1);
        assertThat(legacyRows()).isEqualTo(legacyBefore);
        assertNoSchedules();
    }

    @Test void missingLegacyWrongQuizAndWrongRevisionVersionCannotCreateASession() {
        var wrongs = List.of(new LearningSession.Source(1, quiz.quizId(), UUID.randomUUID(), 1),
                new LearningSession.Source(1, UUID.randomUUID(), quiz.quizRevisionId(), 1),
                new LearningSession.Source(1, quiz.quizId(), quiz.quizRevisionId(), 2));
        for (var wrong : wrongs) {
            permitted.add(wrong); // Authority double permits; real bounded revision lookup must still refuse.
            rejected(() -> service.create(command(wrong)), SOURCE_UNAVAILABLE);
            assertCounts(0);
        }
        assertThat(legacyRows()).isEqualTo(legacyBefore);
        assertThat(service.create(command(quiz)).replayed()).isFalse();
    }

    @ParameterizedTest @ValueSource(ints = {2, 3})
    void structuredRevisionMetadataIsCapturedWithoutMaterializingSectionsOrCases(int format) {
        var original = revision(UUID.randomUUID(), 1);
        var revisions = new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(sql));
        var flat = revisions.findById(ANNA, original.quizRevisionId()).orElseThrow();
        var section = new RevisionSection(UUID.randomUUID(), 1, 0, "Section", null,
                flat.items().stream().map(QuizRevision.Item::id).toList());
        var cases = format == 3 ? List.of(new RevisionCaseBlock(UUID.randomUUID(), 1, section.id(), 0,
                UUID.randomUUID(), true, section.itemIds())) : List.<RevisionCaseBlock>of();
        var structured = new QuizRevision(UUID.randomUUID(), flat.quizId(), ANNA, format, flat.title(), null,
                TIME, flat.items(), List.of(section), cases);
        new TransactionTemplate(manager).executeWithoutResult(status -> revisions.insert(structured));
        var reference = new LearningSession.Source(1, structured.quizId(), structured.id(), format);
        permitted.add(reference);
        var saved = service.create(command(reference)).session();
        assertThat(service.read(saved.id()).source()).isEqualTo(reference);
        assertCounts(1);
        assertNoSchedules();
    }

    @Test void currentIdentityOwnershipAndSourceAccessAreCheckedForCreateReadAndReplay() {
        var command = command(quiz);
        var saved = service.create(command).session();
        login("bob");
        rejected(() -> service.read(saved.id()), UNAVAILABLE);
        rejected(() -> service.read(UUID.randomUUID()), UNAVAILABLE);
        var bob = service.create(command).session();
        assertThat(bob.id()).isNotEqualTo(saved.id()); // Keys are learner-scoped, not globally coupled.
        assertThat(bob.learnerId()).isEqualTo(BOB);
        login("anna");
        access.set(false); // Current private/org/release denial at the named source port.
        rejected(() -> service.read(saved.id()), SOURCE_UNAVAILABLE);
        rejected(() -> service.create(command), SOURCE_UNAVAILABLE);
        rejected(() -> service.create(command(quiz)), SOURCE_UNAVAILABLE);
        access.set(true);
        assertThat(service.read(saved.id())).isEqualTo(saved);
        sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ANNA.toString());
        rejected(() -> service.read(saved.id()), UNAVAILABLE);
        rejected(() -> service.create(command), UNAVAILABLE);
        SecurityContextHolder.clearContext();
        rejected(() -> service.create(command), UNAUTHENTICATED);
        login("anna");
        sql.update("UPDATE users SET is_active=TRUE WHERE user_id=UUID_TO_BIN(?)", ANNA.toString());
        assertThat(service.create(command).replayed()).isTrue();
        assertCounts(2);
    }

    @ParameterizedTest @EnumSource(Fault.class)
    void reachedWriteFailuresRollBackSessionAndReceiptThenRetrySuccessfully(Fault point) {
        var command = command(quiz);
        var reached = new AtomicBoolean();
        Consumer<LearningSession> failure = session -> {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session WHERE id=UUID_TO_BIN(?)",
                    Integer.class, session.id().toString())).isOne();
            reached.set(true);
            if (point == Fault.BEFORE_COMMIT) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("private-provider-diagnostic"); }
                });
            } else throw new IllegalStateException("private-provider-diagnostic");
        };
        if (point == Fault.AFTER_SESSION) repository.afterInsert = failure;
        else repository.afterReceipt = failure;
        rejected(() -> service.create(command), PERSISTENCE_UNAVAILABLE);
        assertThat(reached).isTrue();
        assertCounts(0); // Fresh connections, after actual transaction completion.
        assertNoSchedules();
        assertThat(legacyRows()).isEqualTo(legacyBefore);
        var restarted = sessions(new HookRepository(), manager, Clock.fixed(TIME, ZoneOffset.UTC));
        assertThat(restarted.create(command).replayed()).isFalse();
        assertCounts(1);
    }

    @Test void committedResponseLossIsReconciledWithSameCommandAfterRestart() {
        var command = command(quiz);
        repository.afterReceipt = session -> source.loseCommit.set(true);
        rejected(() -> service.create(command), PERSISTENCE_UNAVAILABLE);
        assertThat(source.lostCommits.get()).isEqualTo(1);
        assertCounts(1); // Delegate commit actually completed before acknowledgement was lost.
        var restarted = sessions(new HookRepository(), manager, Clock.fixed(TIME.plusSeconds(60), ZoneOffset.UTC));
        var replay = restarted.create(command);
        assertThat(replay.replayed()).isTrue();
        assertThat(restarted.read(replay.session().id())).isEqualTo(replay.session());
        assertCounts(1);
    }

    @Test void outerRcRollbackAndCaughtFailureRetainCallerAtomicity() {
        var command = command(quiz);
        var rc = transaction(TransactionDefinition.ISOLATION_READ_COMMITTED);
        rc.executeWithoutResult(status -> {
            var saved = service.create(command).session();
            assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
            assertThat(service.read(saved.id())).isEqualTo(saved);
            status.setRollbackOnly();
        });
        assertCounts(0);
        repository.afterReceipt = session -> { throw new IllegalStateException("reached-write"); };
        assertThatThrownBy(() -> rc.executeWithoutResult(status -> {
            rejected(() -> service.create(command), PERSISTENCE_UNAVAILABLE);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertCounts(0);
    }

    @Test void incompatibleCallersAreRefusedBeforeIdentityOrDomainSql() {
        for (int isolation : new int[]{TransactionDefinition.ISOLATION_DEFAULT, TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionDefinition.ISOLATION_SERIALIZABLE}) {
            transaction(isolation).executeWithoutResult(status -> {
                source.statements.get().clear();
                rejected(() -> service.create(command(quiz)), UNSUPPORTED_TRANSACTION);
                rejected(() -> service.read(UUID.randomUUID()), UNSUPPORTED_TRANSACTION);
                assertThat(source.statements.get()).isEmpty();
            });
        }
        var readOnly = transaction(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> {
            source.statements.get().clear();
            rejected(() -> service.create(command(quiz)), UNSUPPORTED_TRANSACTION);
            assertThat(source.statements.get()).isEmpty();
        });
        assertCounts(0);
        assertThat(service.create(command(quiz)).replayed()).isFalse();
    }

    @Test void duplicateCreatorWaitsOnActualUniqueKeyAndReplaysOneWinner() throws Exception {
        race(false);
    }

    @Test void conflictingCreatorWaitsThenCannotOverwriteWinner() throws Exception {
        race(true);
    }

    private void race(boolean conflict) throws Exception {
        var first = command(quiz);
        var secondSource = conflict ? revision(quiz.quizId(), 2) : quiz;
        permitted.add(secondSource);
        var second = new LearningSessionService.Create(1, first.commandId(), secondSource, first.configuration());
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holderId = new AtomicLong();
        repository.afterInsert = session -> {
            holderId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
            reached.countDown(); await(release);
        };
        var waiterRepository = new HookRepository();
        var waiterId = new AtomicLong();
        waiterRepository.beforeInsert = session -> waiterId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
        var waiter = sessions(waiterRepository, manager, Clock.fixed(TIME.plusSeconds(1), ZoneOffset.UTC));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> as("anna", () -> service.create(first)));
            assertThat(reached.await(20, TimeUnit.SECONDS)).isTrue();
            var duplicate = pool.submit(() -> as("anna", () -> {
                if (conflict) { rejected(() -> waiter.create(second), CONFLICT); return null; }
                return waiter.create(second);
            }));
            try { until(() -> {
                if (duplicate.isDone()) {
                    try { duplicate.get(); }
                    catch (Exception failure) { throw new AssertionError("duplicate failed before the expected lock wait", failure); }
                    fail("duplicate completed before the first creator committed");
                }
                return waiterId.get() > 0 && waiting(waiterId.get(), holderId.get());
            }, "duplicate did not wait on a real MySQL key lock"); }
            catch (AssertionError failure) {
                throw new AssertionError("waiter=" + waiterId.get() + "; locks=" + admin.queryForList("""
                        SELECT t.PROCESSLIST_ID,l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA=?
                        """, SCHEMA), failure);
            }
            release.countDown();
            var winner = holder.get(20, TimeUnit.SECONDS);
            assertThat(winner.replayed()).isFalse();
            var result = duplicate.get(20, TimeUnit.SECONDS);
            if (!conflict) assertThat(result).isEqualTo(new LearningSessionService.Result(winner.session(), true));
            assertThat(service.read(winner.session().id())).isEqualTo(winner.session());
            assertCounts(1);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void otherLearnerOnSameRevisionAndOtherSourceCommitBeforeHolderIsReleased() throws Exception {
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        repository.afterReceipt = session -> { reached.countDown(); await(release); };
        var peer = sessions(new HookRepository(), manager, Clock.fixed(TIME, ZoneOffset.UTC));
        var unrelated = revision(UUID.randomUUID(), 4); permitted.add(unrelated);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> as("anna", () -> service.create(command(quiz))));
            assertThat(reached.await(20, TimeUnit.SECONDS)).isTrue();
            var peerResults = pool.submit(() -> as("bob", () -> List.of(peer.create(command(quiz)), peer.create(command(unrelated)))))
                    .get(20, TimeUnit.SECONDS);
            assertThat(release.getCount()).isOne();
            assertThat(peerResults).allSatisfy(result -> assertThat(result.replayed()).isFalse());
            for (var result : peerResults) assertThat(as("bob", () -> peer.read(result.session().id()))).isEqualTo(result.session());
            release.countDown();
            assertThat(holder.get(20, TimeUnit.SECONDS).replayed()).isFalse();
            assertCounts(3);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void readCostIsBoundedMetadataAcrossMultipleSessionsAndLargeContent() {
        var large = revision(UUID.randomUUID(), 100); permitted.add(large);
        var smallSessions = new ArrayList<LearningSession>();
        var largeSessions = new ArrayList<LearningSession>();
        for (int i = 0; i < 3; i++) {
            smallSessions.add(service.create(command(quiz)).session());
            largeSessions.add(service.create(command(large)).session());
        }
        int expected = -1;
        for (var session : java.util.stream.Stream.concat(smallSessions.stream(), largeSessions.stream()).toList()) {
            source.statements.get().clear();
            assertThat(service.read(session.id())).isEqualTo(session);
            var statements = List.copyOf(source.statements.get());
            if (expected < 0) expected = statements.size();
            assertThat(statements).hasSize(expected);
            assertThat(statements).noneMatch(s -> s.contains("question_revisions") || s.contains("quiz_revision_items")
                    || s.contains("answers") || s.contains("spaced_repetition"));
            assertThat(statements).filteredOn(s -> s.startsWith("SELECT")).hasSize(4);
        }
        assertThat(legacyRows()).isEqualTo(legacyBefore);
        assertNoSchedules();
    }

    @Test void missingReceiptAndCorruptConfigurationAreNeverRepairedDuringReadsOrReplays() {
        var command = command(quiz);
        var saved = service.create(command).session();
        sql.update("DELETE FROM learning_session_create_receipt WHERE session_id=UUID_TO_BIN(?)", saved.id().toString());
        rejected(() -> service.read(saved.id()), CORRUPT);
        rejected(() -> service.create(command), CORRUPT);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session_create_receipt", Integer.class)).isZero();
        sql.update("INSERT INTO learning_session_create_receipt VALUES(UUID_TO_BIN(?),1,UUID_TO_BIN(?),UUID_TO_BIN(?),?)",
                saved.id().toString(), ANNA.toString(), command.commandId().toString(), java.sql.Timestamp.from(TIME));
        sql.update("UPDATE learning_session SET configuration=JSON_SET(configuration,'$.maximumIntervalDays',100) WHERE id=UUID_TO_BIN(?)", saved.id().toString());
        rejected(() -> service.read(saved.id()), CORRUPT);
        rejected(() -> service.create(command), CORRUPT);
        assertThat(sql.queryForObject("SELECT configuration->>'$.maximumIntervalDays' FROM learning_session WHERE id=UUID_TO_BIN(?)",
                String.class, saved.id().toString())).isEqualTo("100");
    }

    @Test void databaseRejectsUnsupportedFormatsAndMismatchedReceiptOwner() {
        var saved = service.create(command(quiz)).session();
        for (String assignment : List.of("schema_version=2", "source_kind='CARD'", "source_version=2",
                "source_schema_version=4", "strategy_id='latest'", "status='ACTIVE'", "version=2"))
            assertThatThrownBy(() -> sql.update("UPDATE learning_session SET " + assignment + " WHERE id=UUID_TO_BIN(?)", saved.id().toString()))
                    .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE learning_session_create_receipt SET learner_id=UUID_TO_BIN(?) WHERE session_id=UUID_TO_BIN(?)",
                BOB.toString(), saved.id().toString())).isInstanceOf(DataAccessException.class);
        assertThat(service.read(saved.id())).isEqualTo(saved);
    }

    private LearningSessionService sessions(LearningSessionRepository store, PlatformTransactionManager transactions, Clock clock) {
        AuthenticatedUserLookup identity = new AuthenticatedUserLookup() {
            @Override public Optional<UUID> findActiveUserId(String username) { throw new AssertionError("current identity required"); }
            @Override public Optional<UUID> lockActiveUserId(String username) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return sql.query("""
                        SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE
                        AND username<>'anonymous' FOR SHARE
                        """, (row, index) -> UUID.fromString(row.getString(1)), username).stream().findFirst();
            }
        };
        return new LearningSessionServiceImpl(store, (learner, recorded) -> {}, identity,
                List.of((learner, ref) -> access.get() && permitted.contains(ref)), clock, metrics, transactions);
    }

    private static LearningSessionService.Create command(LearningSession.Source ref) {
        return new LearningSessionService.Create(1, UUID.randomUUID(), ref, FsrsManifest.approved());
    }

    private class HookRepository extends JdbcLearningSessionRepository {
        Consumer<LearningSession> beforeInsert = session -> { }, afterInsert = session -> { }, afterReceipt = session -> { };
        HookRepository() { super(new NamedParameterJdbcTemplate(sql)); }
        @Override public LearningSession insertOrRead(LearningSession session) {
            beforeInsert.accept(session);
            var saved = super.insertOrRead(session);
            afterInsert.accept(saved);
            return saved;
        }
        @Override public void recordCreation(LearningSession session) { super.recordCreation(session); afterReceipt.accept(session); }
    }

    private static LearningSession.Source revision(UUID quizId, int items) {
        UUID id = UUID.randomUUID();
        var rows = new ArrayList<QuizRevision.Item>();
        for (int i = 0; i < items; i++) rows.add(new QuizRevision.Item(UUID.randomUUID(), i,
                new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.TRUE_FALSE, Difficulty.EASY,
                        "Retained question " + i, "{\"answer\":true,\"fixture\":\"" + "x".repeat(1000) + "\"}",
                        null, null, null, null, TIME, List.of())));
        new TransactionTemplate(manager).executeWithoutResult(status -> new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(sql))
                .insert(new QuizRevision(id, quizId, ANNA, 1, "Learning source", null, TIME, rows)));
        return new LearningSession.Source(1, quizId, id, 1);
    }

    private static void migrate(String target) {
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target(target).load().migrate();
    }

    private static void legacyFixture() {
        UUID category = UUID.randomUUID(), legacyQuiz = UUID.randomUUID(), attempt = UUID.randomUUID(), question = UUID.randomUUID();
        sql.update("INSERT INTO categories(category_id,category_name) VALUES(UUID_TO_BIN(?),'Learning compatibility')", category.toString());
        sql.update("""
                INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'Existing practice','PUBLIC','EASY','PUBLISHED',1,0,0)
                """, legacyQuiz.toString(), ANNA.toString(), category.toString());
        sql.update("INSERT INTO questions(id,type,difficulty,question,content) VALUES(UUID_TO_BIN(?),'TRUE_FALSE','EASY','Existing question','{\"answer\":true}')", question.toString());
        sql.update("INSERT INTO quiz_questions VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?))", legacyQuiz.toString(), question.toString());
        sql.update("INSERT INTO attempts(id,user_id,quiz_id,status,mode,total_score) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'COMPLETED','ALL_AT_ONCE',1)",
                attempt.toString(), ANNA.toString(), legacyQuiz.toString());
        sql.update("INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'{\"answer\":true}',TRUE,1,?)",
                UUID.randomUUID().toString(), attempt.toString(), question.toString(), java.sql.Timestamp.from(TIME));
    }

    private static Map<String, List<Map<String, Object>>> legacyRows() {
        // Stable scalar projections of actual retained rows, including the legacy result/projection.
        return Map.of("quizzes", sql.queryForList("SELECT HEX(quiz_id) id,title,status,is_repetition_enabled FROM quizzes ORDER BY quiz_id"),
                "attempts", sql.queryForList("SELECT HEX(id) id,status,total_score,accepted_answer_projection FROM attempts ORDER BY id"),
                "answers", sql.queryForList("SELECT HEX(id) id,response,is_correct,score,answered_at FROM answers ORDER BY id"));
    }

    private static void assertCounts(int count) {
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session", Integer.class)).isEqualTo(count);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session_create_receipt", Integer.class)).isEqualTo(count);
    }

    private static void assertNoSchedules() {
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM spaced_repetition_entry", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM repetition_review_log", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_automatic_result", Integer.class)).isZero();
    }

    private static TransactionTemplate transaction(int isolation) {
        var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation); return transaction;
    }

    private static void login(String name) {
        var principal = User.withUsername(name).password("unused").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static <T> T as(String name, Supplier<T> work) {
        login(name); try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void until(Supplier<Boolean> condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail(message);
            Thread.sleep(10);
        }
    }

    private static boolean waiting(long requester, long blocker) {
        // Use the same current Performance Schema thread identity as the established receipt
        // tests; do not join the separately sampled INFORMATION_SCHEMA transaction inventory.
        return admin.queryForObject("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID
                JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID
                JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                WHERE r.PROCESSLIST_ID=? AND b.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='learning_session'
                """, Integer.class, requester, blocker, SCHEMA) > 0;
    }

    private static class ObservedSource extends DelegatingDataSource {
        final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        final AtomicBoolean loseCommit = new AtomicBoolean();
        final AtomicInteger lostCommits = new AtomicInteger();
        ObservedSource(DriverManagerDataSource delegate) { super(delegate); }
        @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return observe(super.getConnection(user, password)); }
        private Connection observe(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement")) statements.get().add((String) arguments[0]);
                try {
                    Object result = method.invoke(connection, arguments);
                    if (method.getName().equals("commit") && loseCommit.compareAndSet(true, false)) {
                        lostCommits.incrementAndGet(); throw new SQLException("lost-commit-ack", "08006");
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}
