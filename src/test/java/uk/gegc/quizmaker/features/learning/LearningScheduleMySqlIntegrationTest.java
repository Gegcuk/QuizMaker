package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.ActiveCycle;
import uk.gegc.quizmaker.features.learning.infra.repository.*;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.LearningDueSelection;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.application.LearningScheduleService.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.FsrsFixtures.assertGoldenNumber;

/** Real #597/#575/#647/#649 writers and transactions. Current content authority and future #650
 * completion are named contract fixtures; this suite does not claim public Learning conformance. */
@Tag("db-serial")
class LearningScheduleMySqlIntegrationTest {
    static final String SCHEMA = "learning_schedule_" + UUID.randomUUID().toString().replace("-", "");
    static final UUID ANNA = UUID.randomUUID(), BOB = UUID.randomUUID();
    static final Instant TIME = Instant.parse("2026-11-19T09:00:00.123456Z");
    static JdbcTemplate admin, sql;
    static ObservedSource source;
    static DataSourceTransactionManager manager;
    static QuizRevision revision;
    static Map<String, List<Map<String, Object>>> retained;
    static boolean created;
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final AtomicBoolean access = new AtomicBoolean(true);
    final Set<LearningSession.Source> permitted = ConcurrentHashMap.newKeySet();
    final Set<UUID> completedSessions = ConcurrentHashMap.newKeySet();
    HookRepository repository;
    AuthenticatedUserLookup users;
    LearningSessionService sessions;
    LearningScheduleService schedules;

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
        sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).target("119").load().migrate();
        for (var entry : Map.of(ANNA, "anna", BOB, "bob").entrySet()) sql.update("""
            INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version)
            VALUES(UUID_TO_BIN(?),?,?,'not-a-login',1,0,0,'2026-01-01','2026-01-01',0)
            """, entry.getKey().toString(), entry.getValue(), entry.getValue() + "@example.invalid");
        revision = revision(UUID.randomUUID(), 3);
        legacyFixture(); retained = retained();
        Flyway.configure().dataSource(source).load().migrate();
    }
    @AfterAll static void drop() { if (created) admin.execute("DROP DATABASE " + SCHEMA); }
    @BeforeEach void setup() {
        source.reset(); permitted.add(ref(revision)); repository = new HookRepository();
        users = new AuthenticatedUserLookup() {
            @Override public Optional<UUID> findActiveUserId(String name) { throw new AssertionError("current locking identity required"); }
            @Override public Optional<UUID> lockActiveUserId(String name) {
                return sql.query("SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE FOR SHARE",
                        (r, n) -> UUID.fromString(r.getString(1)), name).stream().findFirst();
            }
        };
        sessions = new LearningSessionServiceImpl(new JdbcLearningSessionRepository(new NamedParameterJdbcTemplate(sql)), org.mockito.Mockito.mock(uk.gegc.quizmaker.features.learning.domain.repository.LearningCommandBudget.class), users,
                List.of(this::allows), Clock.fixed(TIME, ZoneOffset.UTC), metrics, manager);
        schedules = schedules(repository, Clock.fixed(TIME.plusNanos(789), ZoneOffset.UTC));
        login("anna");
    }
    @AfterEach void clean() {
        source.reset(); SecurityContextHolder.clearContext();
        sql.update("DELETE FROM learning_cycle_session"); sql.update("DELETE FROM learning_review_event");
        sql.update("DELETE FROM learning_fsrs_state");
        sql.update("UPDATE learning_schedule_slot SET active_cycle_id=NULL,cycle_version=0");
        sql.update("DELETE FROM learning_schedule_cycle"); sql.update("DELETE FROM learning_schedule_slot");
        sql.update("DELETE FROM learning_schedule_receipt");
        sql.update("DELETE FROM learning_form_option"); sql.update("DELETE FROM learning_form_item"); sql.update("DELETE FROM learning_form");
        sql.update("DELETE FROM learning_session_create_receipt"); sql.update("DELETE FROM learning_session");
        sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE WHERE username IN ('anna','bob')");
        assertThat(retained()).isEqualTo(retained);
        assertThat(count("spaced_repetition_entry")).isZero(); assertThat(count("repetition_review_log")).isZero();
        assertThat(count("attempt_automatic_result")).isZero();
        metrics.close();
    }

    @Test void additiveMigrationStartsEmptyAndReadNeverImplicitlyEnrolls() {
        assertThat(retained()).isEqualTo(retained);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='120' AND success=1", Integer.class)).isOne();
        rejected(() -> schedules.read(ref(revision), question(0)), UNAVAILABLE);
        for (String table : List.of("learning_schedule_slot", "learning_schedule_cycle", "learning_fsrs_state", "learning_schedule_receipt", "learning_review_event"))
            assertThat(count(table)).isZero();
    }

    @ParameterizedTest @EnumSource(Rating.class)
    void enrollmentReviewAndHistoricalReplaySurviveComponentRestart(Rating rating) {
        var enrollment = enroll(0);
        var first = schedules.execute(enrollment);
        assertThat(first.replayed()).isFalse();
        var initial = first.snapshot();
        assertThat(initial.cycleVersion()).isOne(); assertThat(initial.state().sequence()).isZero();
        assertThat(initial.state().dueAt()).isEqualTo(TIME);
        assertThat(initial.state().manifest()).isEqualTo(FsrsManifest.approved());
        var command = rate(initial, rating);
        var reviewed = schedules.execute(command);
        var expected = new FsrsSchedulingStrategyImpl().review(initial.state(),
                new LearningSchedule.Review(command.id(), initial.state().identity(), 0, rating, TIME));
        assertThat(reviewed.snapshot().state()).isEqualTo(expected.state());
        var restarted = schedules(new HookRepository(), Clock.fixed(TIME.plusSeconds(5000), ZoneOffset.ofHours(10)));
        assertThat(restarted.execute(command)).isEqualTo(new Result(reviewed.snapshot(), true));
        assertThat(restarted.execute(enrollment)).isEqualTo(new Result(initial, true));
        assertThat(restarted.execute(enroll(0)).snapshot()).isEqualTo(reviewed.snapshot());
        assertThat(restarted.read(ref(revision), question(0))).isEqualTo(reviewed.snapshot());
        assertThat(new FsrsStateFormat().decodeTransition(sql.queryForObject("SELECT transition FROM learning_review_event", String.class))).isEqualTo(expected);
        assertThat(count("learning_schedule_slot")).isOne(); assertThat(count("learning_schedule_cycle")).isOne();
        assertThat(count("learning_review_event")).isOne();
        rejected(() -> restarted.execute(new Rate(1, command.id(), command.source(), command.questionRevisionId(),
                command.expected(), rating == Rating.AGAIN ? Rating.EASY : Rating.AGAIN)), CONFLICT);
        assertThat(count("learning_review_event")).isOne();
    }

    @ParameterizedTest @ValueSource(longs = {86340, 86400})
    void persistedElapsedTimeIsIndependentOfProfileTimezoneAndMidnight(long elapsed) {
        var initial = schedules.execute(enroll(0)).snapshot();
        var first = schedules.execute(rate(initial, Rating.EASY)).snapshot();
        Instant at = TIME.plusSeconds(elapsed);
        var command = rate(first, Rating.GOOD);
        var expected = new FsrsSchedulingStrategyImpl().review(first.state(),
                new LearningSchedule.Review(command.id(), first.state().identity(), 1, Rating.GOOD, at));
        var changedZone = schedules(new HookRepository(), Clock.fixed(at, ZoneId.of("Europe/London")));
        assertThat(changedZone.execute(command).snapshot().state()).isEqualTo(expected.state());
        assertThat(schedules(new HookRepository(), Clock.fixed(at.plusSeconds(1), ZoneId.of("Pacific/Auckland"))).execute(command).snapshot().state())
                .isEqualTo(expected.state());
    }

    static java.util.stream.Stream<Arguments> goldenCases() throws Exception { return FsrsSchedulingStrategyTest.goldenCases(); }
    @ParameterizedTest(name = "persisted {0}") @MethodSource("goldenCases")
    void pinnedGoldenHistoriesUseRealEnrollmentAndReviewsThenReconstructExactly(String name, String[] row) {
        Instant base = FsrsFixtures.TIME.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        long age = switch (row[0]) {
            case "fresh" -> 0;
            case "learning-short" -> 30;
            case "learning-long", "relearning-long" -> row[0].equals("relearning-long") ? 1900800 : 172800;
            case "review-short" -> 86399;
            case "review-long", "relearning-short" -> 1728000;
            default -> throw new AssertionError(row[0]);
        };
        var initialClock = Clock.fixed(base.minusSeconds(age), ZoneOffset.UTC);
        var current = schedules(new HookRepository(), initialClock).execute(enroll(0)).snapshot();
        if (!row[0].equals("fresh")) {
            var firstRating = row[0].equals("learning-short") ? Rating.AGAIN : row[0].equals("learning-long") ? Rating.GOOD : Rating.EASY;
            current = schedules(new HookRepository(), initialClock).execute(rate(current, firstRating)).snapshot();
            if (row[0].startsWith("relearning")) {
                Instant last = base.minusSeconds(row[0].equals("relearning-short") ? 600 : 172800);
                current = schedules(new HookRepository(), Clock.fixed(last, ZoneOffset.UTC)).execute(rate(current, Rating.AGAIN)).snapshot();
            }
        }
        var command = rate(current, Rating.valueOf(row[8]));
        var at = Instant.parse(row[7]).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var result = schedules(new HookRepository(), Clock.fixed(at, ZoneId.of("Europe/London"))).execute(command).snapshot();
        assertThat(result.state().phase().name()).isEqualTo(row[9]);
        assertThat(result.state().step()).isEqualTo(row[10].equals("-") ? null : Integer.valueOf(row[10]));
        assertGoldenNumber(result.state().stability(), row[11]);
        assertGoldenNumber(result.state().difficulty(), row[12]);
        assertThat(result.state().dueAt()).isEqualTo(Instant.parse(row[13]).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        // Compare the actual calculated state exactly through persistence/replay, independently
        // of the small platform allowance used only for the external numeric golden oracle.
        assertThat(schedules(new HookRepository(), Clock.fixed(TIME, ZoneOffset.UTC)).execute(command)).isEqualTo(new Result(result, true));
        assertThat(schedules(new HookRepository(), Clock.fixed(TIME, ZoneOffset.UTC)).read(ref(revision), question(0))).isEqualTo(result);
    }

    @Test void newRevisionAndDifferentQuizHaveIndependentStateAndNoGuessedProgress() {
        var original = schedules.execute(enroll(0)).snapshot();
        var rated = schedules.execute(rate(original, Rating.EASY)).snapshot();
        var later = revision(revision.quizId(), 1); permitted.add(ref(later));
        var other = revision(UUID.randomUUID(), 1); permitted.add(ref(other));
        for (var rev : List.of(later, other)) {
            var fresh = schedules.execute(new Enroll(1, UUID.randomUUID(), ref(rev), rev.items().get(0).question().id(), FsrsManifest.approved())).snapshot();
            assertThat(fresh.state().sequence()).isZero(); assertThat(fresh.state().dueAt()).isEqualTo(TIME);
            assertThat(fresh.state().identity().cycleId()).isNotEqualTo(original.state().identity().cycleId());
        }
        assertThat(schedules.read(ref(revision), question(0))).isEqualTo(rated);
    }

    @Test void serverIdentityCurrentAccessAndRevisionMembershipApplyAlsoToReplays() {
        var command = enroll(0); var saved = schedules.execute(command).snapshot();
        access.set(false);
        rejected(() -> schedules.execute(command), SOURCE_UNAVAILABLE);
        rejected(() -> schedules.read(ref(revision), question(0)), SOURCE_UNAVAILABLE);
        access.set(true);
        rejected(() -> schedules.execute(new Enroll(1, UUID.randomUUID(), ref(revision), UUID.randomUUID(), FsrsManifest.approved())), SOURCE_UNAVAILABLE);
        var wrong = new LearningSession.Source(1, UUID.randomUUID(), revision.id(), 1); permitted.add(wrong);
        rejected(() -> schedules.read(wrong, question(0)), SOURCE_UNAVAILABLE);
        as("bob", () -> {
            rejected(() -> schedules.read(ref(revision), question(0)), UNAVAILABLE);
            rejected(() -> schedules.execute(rate(saved, Rating.EASY)), UNAVAILABLE);
            var own = schedules.execute(command).snapshot();
            assertThat(own.state().identity().learnerId()).isEqualTo(BOB);
            assertThat(own.state().identity().cycleId()).isNotEqualTo(saved.state().identity().cycleId());
            return null;
        });
        login("anna");
        sql.update("UPDATE users SET is_active=FALSE WHERE username='anna'");
        rejected(() -> schedules.execute(command), UNAVAILABLE);
        sql.update("UPDATE users SET is_active=TRUE WHERE username='anna'");
        SecurityContextHolder.clearContext(); rejected(() -> schedules.execute(command), UNAUTHENTICATED);
        login("anna"); assertThat(schedules.read(ref(revision), question(0))).isEqualTo(saved);
    }

    @ParameterizedTest @ValueSource(strings = {"strategy", "state_version", "manifest", "missing_state", "sequence", "missing_event", "initial_receipt"})
    void unsupportedOrCorruptEvidenceNeverFallsBackOrRepairs(String corruption) {
        var initial = schedules.execute(enroll(0)).snapshot();
        var current = schedules.execute(rate(initial, Rating.GOOD)).snapshot();
        switch (corruption) {
            case "strategy" -> sql.update("UPDATE learning_schedule_cycle SET strategy_id='future/v9'");
            case "state_version" -> sql.update("UPDATE learning_fsrs_state SET state=JSON_SET(state,'$.version',9)");
            case "manifest" -> sql.update("UPDATE learning_fsrs_state SET state=JSON_SET(state,'$.manifest.maximumIntervalDays',100)");
            case "missing_state" -> sql.update("DELETE FROM learning_fsrs_state");
            case "sequence" -> sql.update("UPDATE learning_fsrs_state SET sequence=50");
            case "missing_event" -> sql.update("DELETE FROM learning_review_event");
            case "initial_receipt" -> sql.update("UPDATE learning_schedule_receipt SET result_cycle_version=NULL,result_state=NULL WHERE JSON_UNQUOTE(JSON_EXTRACT(result_state,'$.sequence'))='0'");
            default -> throw new AssertionError(corruption);
        }
        var reason = corruption.equals("strategy") || corruption.equals("state_version") ? UNSUPPORTED_VERSION : corruption.equals("manifest") ? UNSUPPORTED_MANIFEST : CORRUPT;
        int receipts = count("learning_schedule_receipt"), events = count("learning_review_event");
        rejected(() -> schedules.read(ref(revision), question(0)), reason);
        rejected(() -> schedules.execute(rate(current, Rating.HARD)), reason);
        assertThat(count("learning_schedule_receipt")).isEqualTo(receipts);
        assertThat(count("learning_review_event")).isEqualTo(events);
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT INTO learning_review_event", "UPDATE learning_fsrs_state", "UPDATE learning_schedule_receipt", "COMMIT"})
    void reachedReviewFailuresRollBackEventStateAndReceiptThenRetry(String checkpoint) {
        var initial = schedules.execute(enroll(0)).snapshot(); var command = rate(initial, Rating.GOOD);
        source.fault.set(checkpoint);
        rejected(() -> schedules.execute(command), PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne();
        assertThat(schedules.read(ref(revision), question(0))).isEqualTo(initial);
        assertThat(count("learning_review_event")).isZero(); assertThat(count("learning_schedule_receipt")).isOne();
        assertThat(schedules.execute(command).snapshot().state().sequence()).isOne();
        assertThat(count("learning_review_event")).isOne(); assertThat(count("learning_schedule_receipt")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT INTO learning_schedule_cycle", "INSERT INTO learning_fsrs_state", "UPDATE learning_schedule_slot", "UPDATE learning_schedule_receipt", "COMMIT"})
    void reachedEnrollmentFailuresLeaveNoSlotCycleStateOrReceipt(String checkpoint) {
        var command = enroll(0); source.fault.set(checkpoint);
        rejected(() -> schedules.execute(command), PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne();
        for (var table : List.of("learning_schedule_slot", "learning_schedule_cycle", "learning_fsrs_state", "learning_schedule_receipt")) assertThat(count(table)).isZero();
        assertThat(schedules.execute(command).snapshot().state().sequence()).isZero();
    }

    @Test void uncertainCommitReconcilesFromDurableReceiptAfterReconstruction() {
        var initial = schedules.execute(enroll(0)).snapshot(); var command = rate(initial, Rating.EASY);
        source.loseCommit.set(true);
        rejected(() -> schedules.execute(command), PERSISTENCE_UNAVAILABLE);
        assertThat(source.lostCommits.get()).isOne();
        var retry = schedules(new HookRepository(), Clock.fixed(TIME.plusSeconds(900), ZoneOffset.UTC)).execute(command);
        assertThat(retry.replayed()).isTrue(); assertThat(retry.snapshot().state().sequence()).isOne();
        assertThat(retry.snapshot().state().dueAt()).isEqualTo(TIME.plus(Duration.ofDays(16)));
        assertThat(count("learning_review_event")).isOne(); assertThat(count("learning_schedule_receipt")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT INTO learning_schedule_cycle", "UPDATE learning_schedule_slot", "INSERT INTO learning_fsrs_state", "UPDATE learning_schedule_receipt", "COMMIT"})
    void reachedReplacementFailuresRestoreOriginalActiveCycleAndRetryOnce(String checkpoint) {
        var original = schedules.execute(enroll(0)).snapshot(); var command = restart(original);
        source.fault.set(checkpoint);
        rejected(() -> schedules.execute(command), PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne();
        assertThat(schedules.read(ref(revision), question(0))).isEqualTo(original);
        assertThat(count("learning_schedule_cycle")).isOne(); assertThat(count("learning_fsrs_state")).isOne();
        assertThat(count("learning_schedule_receipt")).isOne();
        var retried = schedules.execute(command);
        assertThat(retried.replayed()).isFalse(); assertThat(retried.snapshot().cycleVersion()).isEqualTo(2);
        assertThat(schedules.execute(command)).isEqualTo(new Result(retried.snapshot(), true));
        assertThat(count("learning_schedule_cycle")).isEqualTo(2);
    }

    @Test void captureAndReleaseReceiptFailuresRollBackReachedSessionGuardChanges() {
        var initial = schedules.execute(enroll(0)).snapshot(); var session = sessionWithForm(0);
        var capture = new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), session.id());
        source.fault.set("UPDATE learning_schedule_receipt");
        rejected(() -> schedules.execute(capture), PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne(); assertThat(count("learning_cycle_session")).isZero();
        assertThat(count("learning_schedule_receipt")).isOne();
        assertThat(schedules.execute(capture).replayed()).isFalse();
        completedSessions.add(session.id());
        var release = new Release(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), session.id());
        source.reached.set(0); source.fault.set("UPDATE learning_schedule_receipt");
        rejected(() -> schedules.execute(release), PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne();
        assertThat(sql.queryForObject("SELECT released FROM learning_cycle_session", Boolean.class)).isFalse();
        assertThat(count("learning_schedule_receipt")).isEqualTo(2);
        rejected(() -> schedules.execute(restart(initial)), ACTIVE_SESSION);
        assertThat(schedules.execute(release).replayed()).isFalse();
        assertThat(schedules.execute(restart(initial)).snapshot().cycleVersion()).isEqualTo(2);
    }

    @Test void outerRcOwnsCommitAndCaughtReachedFailureMakesCallerRollbackOnly() {
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            var initial = schedules.execute(enroll(0)).snapshot();
            schedules.execute(rate(initial, Rating.GOOD)); status.setRollbackOnly();
        });
        assertThat(count("learning_schedule_slot")).isZero(); assertThat(count("learning_schedule_receipt")).isZero();
        var initial = schedules.execute(enroll(0)).snapshot();
        long version = sql.queryForObject("SELECT version FROM users WHERE username='anna'", Long.class);
        assertThatThrownBy(() -> tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            sql.update("UPDATE users SET version=version+1 WHERE username='anna'");
            source.fault.set("INSERT INTO learning_review_event");
            rejected(() -> schedules.execute(rate(initial, Rating.AGAIN)), PERSISTENCE_UNAVAILABLE);
            assertThat(source.reached.get()).isOne(); assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(sql.queryForObject("SELECT version FROM users WHERE username='anna'", Long.class)).isEqualTo(version);
        assertThat(schedules.read(ref(revision), question(0))).isEqualTo(initial);
    }

    @Test void incompatibleAndReadOnlyCallersRejectBeforeAnyIdentityOrDomainSql() {
        for (int isolation : List.of(TransactionDefinition.ISOLATION_DEFAULT, TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            tx(isolation).executeWithoutResult(status -> {
                source.statements.get().clear();
                rejected(() -> schedules.execute(enroll(0)), UNSUPPORTED_TRANSACTION);
                rejected(() -> schedules.read(ref(revision), question(0)), UNSUPPORTED_TRANSACTION);
                assertThat(source.statements.get()).isEmpty();
            });
        }
        var readOnly = tx(TransactionDefinition.ISOLATION_READ_COMMITTED); readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> {
            source.statements.get().clear(); rejected(() -> schedules.execute(enroll(0)), UNSUPPORTED_TRANSACTION);
            assertThat(source.statements.get()).isEmpty();
        });
    }

    @Test void restartKeepsHistoricalReceiptsAndStaleReviewCannotAdvanceReplacement() {
        var initial = schedules.execute(enroll(0)).snapshot(); var review = rate(initial, Rating.GOOD);
        var reviewed = schedules.execute(review).snapshot();
        var restart = restart(reviewed); var replacement = schedules.execute(restart).snapshot();
        assertThat(replacement.cycleVersion()).isEqualTo(2); assertThat(replacement.state().sequence()).isZero();
        assertThat(replacement.state().identity().cycleId()).isNotEqualTo(initial.state().identity().cycleId());
        assertThat(schedules.execute(review)).isEqualTo(new Result(reviewed, true));
        assertThat(schedules.execute(restart)).isEqualTo(new Result(replacement, true));
        rejected(() -> schedules.execute(rate(reviewed, Rating.EASY)), STALE_STATE);
        assertThat(schedules.read(ref(revision), question(0))).isEqualTo(replacement);
        assertThat(count("learning_schedule_cycle")).isEqualTo(2); assertThat(count("learning_review_event")).isOne();
    }

    @Test void capturedRealSessionMustFinishBeforeCycleReplacementAndCannotBeRecapturedAfterRelease() {
        var initial = schedules.execute(enroll(0)).snapshot(); var session = sessionWithForm(0);
        var capture = new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), session.id());
        var captured = schedules.execute(capture);
        rejected(() -> schedules.execute(restart(initial)), ACTIVE_SESSION);
        var release = new Release(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), session.id());
        rejected(() -> schedules.execute(release), SESSION_INCOMPLETE);
        var noCompletionAdapter = new LearningScheduleServiceImpl(repository, users, List.of(this::allows), List.of(), sessions,
                new FsrsSchedulingStrategyImpl(), Clock.fixed(TIME, ZoneOffset.UTC), metrics, manager);
        rejected(() -> noCompletionAdapter.execute(release), SESSION_INCOMPLETE);
        // Explicit fixture for the separately owned #650 completion contract, not a lifecycle write.
        completedSessions.add(session.id()); schedules.execute(release);
        assertThat(schedules.execute(release).replayed()).isTrue();
        rejected(() -> schedules.execute(new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), session.id())), SESSION_CLOSED);
        var replacement = schedules.execute(restart(initial)).snapshot();
        assertThat(replacement.cycleVersion()).isEqualTo(2);
        int receipts = count("learning_schedule_receipt");
        var rebind = new Capture(1, UUID.randomUUID(), ref(revision), question(0), replacement.expected(), session.id());
        rejected(() -> schedules.execute(rebind), SESSION_CLOSED);
        assertThat(count("learning_cycle_session")).isOne();
        assertThat(count("learning_schedule_receipt")).isEqualTo(receipts);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_schedule_receipt WHERE command_id=UUID_TO_BIN(?)",
                Integer.class, rebind.id().toString())).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_cycle_session WHERE cycle_id=UUID_TO_BIN(?)",
                Integer.class, replacement.state().identity().cycleId().toString())).isZero();
        assertThat(schedules.read(ref(revision), question(0))).isEqualTo(replacement);
        assertThat(schedules.execute(capture)).isEqualTo(new Result(captured.snapshot(), true));
        assertThat(schedules.execute(release).replayed()).isTrue();
        assertThat(count("learning_cycle_session")).isOne();
        assertThat(count("learning_schedule_receipt")).isEqualTo(receipts);
        // A refused transfer cannot manufacture ACTIVE_SESSION on the replacement cycle.
        var next = schedules.execute(restart(replacement)).snapshot();
        assertThat(next.cycleVersion()).isEqualTo(3);
        var newSession = sessionWithForm(0);
        var newCapture = new Capture(1, UUID.randomUUID(), ref(revision), question(0), next.expected(), newSession.id());
        assertThat(schedules.execute(newCapture)).isEqualTo(new Result(next, false));
        assertThat(count("learning_cycle_session")).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_cycle_session WHERE session_id=UUID_TO_BIN(?)",
                Integer.class, session.id().toString())).isOne();
        assertThat(sql.queryForObject("SELECT released FROM learning_cycle_session WHERE session_id=UUID_TO_BIN(?)",
                Boolean.class, session.id().toString())).isTrue();
        assertThat(sessions.read(session.id())).isEqualTo(session); // #649 never changes session lifecycle.
    }

    @Test void captureRejectsUnboundAndForeignSessionsWithPositiveSamePathControl() {
        var initial = schedules.execute(enroll(0)).snapshot(); var otherQuestionSession = sessionWithForm(1);
        rejected(() -> schedules.execute(new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), otherQuestionSession.id())), SESSION_UNAVAILABLE);
        var foreign = as("bob", () -> sessionWithForm(0)); login("anna");
        rejected(() -> schedules.execute(new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), foreign.id())), SESSION_UNAVAILABLE);
        var own = sessionWithForm(0);
        assertThat(schedules.execute(new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), own.id())).replayed()).isFalse();
        assertThat(count("learning_cycle_session")).isOne();
    }

    @Test void releasedQuestionHistoryDoesNotBlockAnotherCapturedQuestionInSameSession() {
        var first = schedules.execute(enroll(0)).snapshot();
        var other = schedules.execute(enroll(1)).snapshot();
        var session = sessionWithForm(0, 1);
        schedules.execute(new Capture(1, UUID.randomUUID(), ref(revision), question(0), first.expected(), session.id()));
        schedules.execute(new Capture(1, UUID.randomUUID(), ref(revision), question(1), other.expected(), session.id()));
        completedSessions.add(session.id()); // Named #650 completion-contract fixture, not a lifecycle writer.
        schedules.execute(new Release(1, UUID.randomUUID(), ref(revision), question(0), first.expected(), session.id()));
        schedules.execute(restart(first));
        int bindings = count("learning_cycle_session");
        var sameOtherQuestion = new Capture(1, UUID.randomUUID(), ref(revision), question(1), other.expected(), session.id());
        assertThat(schedules.execute(sameOtherQuestion)).isEqualTo(new Result(other, false));
        assertThat(count("learning_cycle_session")).isEqualTo(bindings);
        assertThat(schedules.read(ref(revision), question(1))).isEqualTo(other);
        assertThat(sql.queryForObject("SELECT released FROM learning_cycle_session WHERE cycle_id=UUID_TO_BIN(?) AND session_id=UUID_TO_BIN(?)",
                Boolean.class, other.state().identity().cycleId().toString(), session.id().toString())).isFalse();
    }

    @Test void strategyNeutralCycleCasUsesSameSlotAndFsrsNeverFallsBackFromForeignStrategy() {
        var initial = schedules.execute(enroll(0)).snapshot();
        var key = new Key(ANNA, ref(revision), question(0));
        var custom = new ActiveCycle(UUID.randomUUID(), 2, "custom/v1");
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            var active = repository.lockCycle(key).orElseThrow();
            assertThat(active).isEqualTo(new ActiveCycle(initial.state().identity().cycleId(), 1, "fsrs/v1"));
            // Common authority contract only: no custom state/review or public switch is claimed.
            repository.replaceCycle(key, custom, active.version(), UUID.randomUUID(), TIME);
        });
        assertThat(count("learning_schedule_slot")).isOne(); assertThat(count("learning_schedule_cycle")).isEqualTo(2);
        rejected(() -> schedules.read(ref(revision), question(0)), UNSUPPORTED_VERSION);
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            assertThat(repository.lockCycle(key)).contains(custom);
            status.setRollbackOnly();
        });
    }

    @Test void commonAuthorityCannotReplaceCapturedCycleAndDatabaseRejectsInvalidBindings() {
        var initial = schedules.execute(enroll(0)).snapshot(); var session = sessionWithForm(0);
        schedules.execute(new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), session.id()));
        var key = new Key(ANNA, ref(revision), question(0));
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            repository.lockCycle(key);
            rejected(() -> repository.replaceCycle(key, new ActiveCycle(UUID.randomUUID(), 2, "custom/v1"), 1, UUID.randomUUID(), TIME), ACTIVE_SESSION);
            status.setRollbackOnly();
        });
        assertThatThrownBy(() -> sql.update("UPDATE learning_schedule_slot SET cycle_version=99"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sql.update("UPDATE learning_schedule_receipt SET result_cycle_version=NULL WHERE result_state IS NOT NULL"))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, failure ->
                        assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                                cause -> assertThat(cause.getErrorCode()).isEqualTo(3819)));
        assertThat(schedules.read(ref(revision), question(0))).isEqualTo(initial);
    }

    enum Race { SAME_COMMAND, CONFLICTING_COMMAND, SAME_SEQUENCE, RESTART }
    @ParameterizedTest @EnumSource(Race.class)
    void realLockRacesHaveOneWinnerAndReplayOrExplicitConflict(Race race) throws Exception {
        var initial = schedules.execute(enroll(0)).snapshot();
        Command first = race == Race.RESTART ? restart(initial) : rate(initial, Rating.GOOD);
        Command second = switch (race) {
            case SAME_COMMAND -> first;
            case CONFLICTING_COMMAND -> new Rate(1, first.id(), ref(revision), question(0), initial.expected(), Rating.EASY);
            case SAME_SEQUENCE -> rate(initial, Rating.EASY);
            case RESTART -> restart(initial);
        };
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        AtomicLong holderId = new AtomicLong(), waiterId = new AtomicLong();
        repository.afterComplete = value -> { holderId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class)); reached.countDown(); await(release); };
        var peerRepository = new HookRepository();
        peerRepository.beforeReserve = () -> waiterId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
        var peer = schedules(peerRepository, Clock.fixed(TIME, ZoneOffset.UTC));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> as("anna", () -> schedules.execute(first))); await(reached);
            var loser = pool.submit(() -> as("anna", () -> {
                if (race == Race.SAME_COMMAND) return peer.execute(second);
                rejected(() -> peer.execute(second), race == Race.CONFLICTING_COMMAND ? CONFLICT : STALE_STATE);
                return null;
            }));
            until(() -> waiterId.get() != 0 && waiting(waiterId.get(), holderId.get()), "peer never reached the real receipt/slot wait");
            assertThat(loser.isDone()).isFalse(); release.countDown();
            var committed = winner.get(30, TimeUnit.SECONDS); var repeated = loser.get(30, TimeUnit.SECONDS);
            if (race == Race.SAME_COMMAND) assertThat(repeated).isEqualTo(new Result(committed.snapshot(), true));
            assertThat(schedules.read(ref(revision), question(0))).isEqualTo(committed.snapshot());
            assertThat(count("learning_schedule_cycle")).isEqualTo(race == Race.RESTART ? 2 : 1);
            assertThat(count("learning_review_event")).isEqualTo(race == Race.RESTART ? 0 : 1);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void captureAndReplacementSerializeOnOneCycleWithoutLateScopeExpansion(boolean captureWins) throws Exception {
        var initial = schedules.execute(enroll(0)).snapshot(); var session = sessionWithForm(0);
        Command capture = new Capture(1, UUID.randomUUID(), ref(revision), question(0), initial.expected(), session.id());
        Command replacement = restart(initial);
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        AtomicLong holderId = new AtomicLong(), waiterId = new AtomicLong();
        repository.afterComplete = value -> { holderId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class)); reached.countDown(); await(release); };
        var store = new HookRepository(); store.beforeReserve = () -> waiterId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
        var peer = schedules(store, Clock.fixed(TIME, ZoneOffset.UTC));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> as("anna", () -> schedules.execute(captureWins ? capture : replacement))); await(reached);
            var waiter = pool.submit(() -> as("anna", () -> {
                rejected(() -> peer.execute(captureWins ? replacement : capture), captureWins ? ACTIVE_SESSION : STALE_STATE);
                return true;
            }));
            until(() -> waiterId.get() != 0 && waiting(waiterId.get(), holderId.get()), "capture/restart peer never reached its slot wait");
            release.countDown(); assertThat(holder.get(30, TimeUnit.SECONDS).replayed()).isFalse(); assertThat(waiter.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(count("learning_schedule_cycle")).isEqualTo(captureWins ? 1 : 2);
            assertThat(count("learning_cycle_session")).isEqualTo(captureWins ? 1 : 0);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void independentItemsLearnersAndUnrelatedQuizCommitBeforeHolderRelease() throws Exception {
        var held = schedules.execute(enroll(0)).snapshot();
        var otherItem = schedules.execute(enroll(1)).snapshot();
        var otherLearner = as("bob", () -> schedules.execute(enroll(0)).snapshot()); login("anna");
        var unrelated = revision(UUID.randomUUID(), 1); permitted.add(ref(unrelated));
        var unrelatedState = as("bob", () -> schedules.execute(new Enroll(1, UUID.randomUUID(), ref(unrelated),
                unrelated.items().get(0).question().id(), FsrsManifest.approved())).snapshot()); login("anna");
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        repository.afterComplete = value -> { if (value.state().identity().cycleId().equals(held.state().identity().cycleId())) { reached.countDown(); await(release); } };
        var pool = Executors.newFixedThreadPool(4);
        try {
            var holder = pool.submit(() -> as("anna", () -> schedules.execute(rate(held, Rating.GOOD)))); await(reached);
            var itemPeer = pool.submit(() -> as("anna", () -> schedules.execute(rate(otherItem, Rating.GOOD))));
            var learnerPeer = pool.submit(() -> as("bob", () -> schedules.execute(rate(otherLearner, Rating.GOOD))));
            var unrelatedPeer = pool.submit(() -> as("bob", () -> schedules.execute(rate(unrelatedState, Rating.GOOD))));
            assertThat(itemPeer.get(30, TimeUnit.SECONDS).snapshot().state().sequence()).isOne();
            assertThat(learnerPeer.get(30, TimeUnit.SECONDS).snapshot().state().sequence()).isOne();
            assertThat(unrelatedPeer.get(30, TimeUnit.SECONDS).snapshot().state().sequence()).isOne();
            assertThat(release.getCount()).isOne(); assertThat(holder.isDone()).isFalse();
            release.countDown(); assertThat(holder.get(30, TimeUnit.SECONDS).snapshot().state().sequence()).isOne();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void readQueriesAndSelectedRowsStayBoundedAcrossParentsAndLargeSourceContent() {
        var large = revision(UUID.randomUUID(), 501); permitted.add(ref(large));
        var inputs = List.of(enroll(0), enroll(1), new Enroll(1, UUID.randomUUID(), ref(large), large.items().get(500).question().id(), FsrsManifest.approved()));
        var saved = inputs.stream().map(c -> schedules.execute(c).snapshot()).toList();
        for (int i = 0; i < inputs.size(); i++) {
            source.statements.get().clear(); source.reads.get().clear(); source.rows.set(0);
            assertThat(schedules.read(inputs.get(i).source(), inputs.get(i).questionRevisionId())).isEqualTo(saved.get(i));
            assertThat(source.statements.get()).hasSize(3).allSatisfy(statement ->
                    assertThat(statement).doesNotContain("SELECT *", "question_text", "q.content", "answers", "explanation"));
            assertThat(source.rows.get()).isEqualTo(3);
            var executed = List.copyOf(source.reads.get());
            assertThat(executed).hasSize(3);
            for (var query : executed) {
                var plan = sql.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
                    try (var statement = connection.prepareStatement("EXPLAIN FORMAT=JSON " + query.sql())) {
                        for (var parameter : query.parameters().entrySet()) statement.setObject(parameter.getKey(), parameter.getValue());
                        try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
                    }
                });
                try {
                    var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(plan);
                    assertThat(tree.findValuesAsText("access_type")).doesNotContain("ALL");
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
        }
        assertThat(count("learning_review_event")).isZero();
    }

    @Test void dueSelectionUsesRealStateAndStableUnsignedTiesWithoutWritingOrImplicitEnrollment() {
        var selector = due(Clock.fixed(TIME.plusNanos(789), ZoneOffset.UTC), 20);
        var empty = selector.select(select(ref(revision), 2, null));
        assertThat(empty.outcome()).isEqualTo(LearningDueSelection.Outcome.EMPTY);
        assertThat(count("learning_schedule_slot")).isZero();
        var originals = java.util.stream.IntStream.range(0, 3).mapToObj(i -> schedules.execute(enroll(i)).snapshot()).toList();
        var before = learningState();
        source.reset();
        var first = selector.select(select(ref(revision), 2, null));
        assertThat(first.items()).extracting(LearningDueSelection.Item::questionRevisionId)
                .containsExactlyElementsOf(originals.stream().map(s -> s.state().identity().questionRevisionId())
                        .sorted(Comparator.comparing(UUID::toString)).limit(2).toList());
        assertThat(first.selectedAt()).isEqualTo(TIME); assertThat(first.next()).isNotNull();
        assertThat(first.outcome()).isEqualTo(LearningDueSelection.Outcome.READY);
        assertThat(source.statements.get()).hasSize(3).allSatisfy(query ->
                assertThat(query.stripLeading()).matches("(?s)(SELECT|WITH).*").doesNotContain("FOR UPDATE", "FOR SHARE", "question_text", "q.content"));
        assertThat(source.rows.get()).isEqualTo(5); // identity + source + limit/one lookahead
        assertThat(selector.select(select(ref(revision), 2, null))).isEqualTo(first);
        var last = due(Clock.fixed(TIME, ZoneId.of("Pacific/Auckland")), 20).select(select(ref(revision), 2, first.next()));
        assertThat(last.items()).hasSize(1); assertThat(last.next()).isNull();
        assertThat(last.outcome()).isEqualTo(LearningDueSelection.Outcome.PARTIAL);
        var all = new ArrayList<>(first.items()); all.addAll(last.items());
        assertThat(all).extracting(LearningDueSelection.Item::questionRevisionId)
                .containsExactlyElementsOf(originals.stream().map(s -> s.state().identity().questionRevisionId())
                        .sorted(Comparator.comparing(UUID::toString)).toList());
        for (var item : all) assertThat(item.expected()).isEqualTo(originals.stream()
                .filter(s -> s.state().identity().questionRevisionId().equals(item.questionRevisionId())).findFirst().orElseThrow().expected());
        assertThat(learningState()).isEqualTo(before);
    }

    @Test void dueBoundaryUsesServerInstantAndOldestDuePrecedesQuestionIdentity() {
        var older = schedules(new HookRepository(), Clock.fixed(TIME.minusSeconds(10), ZoneOffset.UTC)).execute(enroll(2)).snapshot();
        var current = schedules.execute(enroll(0)).snapshot();
        var future = schedules.execute(enroll(1)).snapshot(); schedules.execute(rate(future, Rating.EASY));
        var before = learningState();
        assertThat(due(Clock.fixed(TIME.minusNanos(1000), ZoneOffset.UTC), 20).select(select(ref(revision), 20, null)).items())
                .extracting(LearningDueSelection.Item::questionRevisionId).containsExactly(older.state().identity().questionRevisionId());
        var atBoundary = due(Clock.fixed(TIME, ZoneOffset.UTC), 20).select(select(ref(revision), 20, null));
        assertThat(atBoundary.items()).extracting(LearningDueSelection.Item::questionRevisionId)
                .containsExactly(older.state().identity().questionRevisionId(), current.state().identity().questionRevisionId());
        assertThat(learningState()).isEqualTo(before);
    }

    @Test void hundredDueItemsRemainReachableAcrossPagesAndRealReviewBatchesOnTheSameDate() {
        var hundred = revision(UUID.randomUUID(), 100); permitted.add(ref(hundred));
        for (var item : hundred.items()) schedules.execute(new Enroll(1, UUID.randomUUID(), ref(hundred), item.question().id(), FsrsManifest.approved()));
        var selector = due(Clock.fixed(TIME, ZoneOffset.UTC), 20);
        var before = learningState();
        LearningDueSelection.Cursor cursor = null;
        var paged = new ArrayList<UUID>();
        do {
            var page = selector.select(select(ref(hundred), 20, cursor));
            assertThat(page.items()).hasSize(20);
            paged.addAll(page.items().stream().map(LearningDueSelection.Item::questionRevisionId).toList());
            cursor = page.next();
        } while (cursor != null);
        assertThat(paged).hasSize(100).doesNotHaveDuplicates().isSortedAccordingTo(Comparator.comparing(UUID::toString));
        assertThat(learningState()).isEqualTo(before);
        var reviewed = new HashSet<UUID>();
        for (int batch = 0; batch < 5; batch++) {
            var page = selector.select(select(ref(hundred), 20, null));
            assertThat(page.items()).hasSize(20);
            for (var item : page.items()) {
                assertThat(reviewed.add(item.questionRevisionId())).isTrue();
                schedules.execute(new Rate(1, UUID.randomUUID(), ref(hundred), item.questionRevisionId(), item.expected(), Rating.EASY));
            }
        }
        assertThat(reviewed).hasSize(100);
        assertThat(selector.select(select(ref(hundred), 20, null)).outcome()).isEqualTo(LearningDueSelection.Outcome.EMPTY);
        assertThat(count("learning_review_event")).isEqualTo(100);
        assertThat(count("learning_session")).isZero(); assertThat(count("attempt_automatic_result")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"strategy", "version", "manifest", "missing-state", "initial", "transition"})
    void dueUnknownVersionsAndMissingEvidenceFailExplicitlyAndNeverRepair(String fault) {
        var initial = schedules.execute(enroll(0)).snapshot();
        if (fault.equals("version") || fault.equals("manifest") || fault.equals("transition")) schedules.execute(rate(initial, Rating.EASY));
        switch (fault) {
            case "strategy" -> sql.update("UPDATE learning_schedule_cycle SET strategy_id='custom/v1'");
            case "version" -> sql.update("UPDATE learning_fsrs_state SET state=JSON_SET(state,'$.version',2)");
            case "manifest" -> sql.update("UPDATE learning_fsrs_state SET state=JSON_SET(state,'$.manifest.version',2)");
            case "missing-state" -> sql.update("DELETE FROM learning_fsrs_state");
            case "initial" -> sql.update("UPDATE learning_schedule_receipt SET result_cycle_version=2");
            case "transition" -> {
                sql.update("UPDATE learning_review_event SET transition=JSON_SET(transition,'$.state.dueAt','2027-01-01T00:00:00Z')");
                assertThat(new FsrsStateFormat().decodeTransition(sql.queryForObject("SELECT transition FROM learning_review_event", String.class))
                        .state().dueAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
            }
            default -> throw new AssertionError(fault);
        }
        var before = learningState();
        var expected = Set.of("strategy", "version", "manifest").contains(fault) ? UNSUPPORTED_VERSION : CORRUPT;
        // Unsupported future state is checked even before due. Other corruption is exercised at due.
        Instant at = fault.equals("transition") ? TIME.plus(Duration.ofDays(20)) : TIME;
        rejected(() -> due(Clock.fixed(at, ZoneOffset.UTC), 20).select(select(ref(revision), 20, null)), expected);
        assertThat(learningState()).isEqualTo(before);
    }

    @Test void dueSelectionReauthorizesAndCannotReadAnotherLearnerOrSource() {
        schedules.execute(enroll(0));
        var selector = due(Clock.fixed(TIME, ZoneOffset.UTC), 20);
        as("bob", () -> {
            assertThat(selector.select(select(ref(revision), 20, null)).outcome()).isEqualTo(LearningDueSelection.Outcome.EMPTY);
            schedules.execute(enroll(1));
            assertThat(selector.select(select(ref(revision), 20, null)).items())
                    .extracting(LearningDueSelection.Item::questionRevisionId).containsExactly(question(1)); return null;
        });
        login("anna");
        assertThat(selector.select(select(ref(revision), 20, null)).items())
                .extracting(LearningDueSelection.Item::questionRevisionId).containsExactly(question(0));
        permitted.clear();
        rejected(() -> selector.select(select(ref(revision), 20, null)), SOURCE_UNAVAILABLE);
        permitted.add(ref(revision));
        sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ANNA.toString());
        rejected(() -> selector.select(select(ref(revision), 20, null)), UNAVAILABLE);
    }

    @Test void dueSqlFailureReachesRealQueryLeavesNoWritesAndRetrySucceeds() {
        schedules.execute(enroll(0)); var before = learningState();
        var selector = due(Clock.fixed(TIME, ZoneOffset.UTC), 20);
        source.fault.set("WITH candidates");
        rejected(() -> selector.select(select(ref(revision), 20, null)), PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne(); assertThat(learningState()).isEqualTo(before);
        assertThat(selector.select(select(ref(revision), 20, null)).items()).hasSize(1);
    }

    @Test void dueBatchQueryCountAndReturnedRowsAreBoundedAcrossParentsAndLargeContent() throws Exception {
        var large = revision(UUID.randomUUID(), 501); permitted.add(ref(large));
        schedules.execute(enroll(0)); as("bob", () -> schedules.execute(enroll(1))); login("anna");
        for (int i = 0; i < 25; i++) schedules.execute(new Enroll(1, UUID.randomUUID(), ref(large), large.items().get(i).question().id(), FsrsManifest.approved()));
        var selector = due(Clock.fixed(TIME, ZoneOffset.UTC), 20);
        for (var scope : List.of(ref(revision), ref(large))) for (int size : List.of(1, 20)) {
            source.reset(); var page = selector.select(select(scope, size, null));
            assertThat(page.items()).hasSize(scope.equals(ref(revision)) ? 1 : size);
            assertThat(source.statements.get()).hasSize(3).allSatisfy(statement ->
                    assertThat(statement).doesNotContain("SELECT *", "question_text", "answers", "explanation", "FOR UPDATE", "FOR SHARE"));
            assertThat(source.rows.get()).isLessThanOrEqualTo(size + 3);
            for (var query : List.copyOf(source.reads.get())) {
                var plan = sql.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
                    try (var statement = connection.prepareStatement("EXPLAIN FORMAT=JSON " + query.sql())) {
                        for (var parameter : query.parameters().entrySet()) statement.setObject(parameter.getKey(), parameter.getValue());
                        try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
                    }
                });
                var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(plan);
                assertThat(tree.findValuesAsText("access_type")).doesNotContain("ALL");
            }
        }
        assertThat(count("learning_review_event")).isZero();
    }

    @Test void concurrentDueReadReturnsCommittedStateBeforeHeldReviewCommitsWithoutBlockingWriter() throws Exception {
        var initial = schedules.execute(enroll(0)).snapshot();
        var held = new HookRepository();
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        held.afterComplete = value -> { reached.countDown(); await(release); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> as("anna", () ->
                    schedules(held, Clock.fixed(TIME, ZoneOffset.UTC)).execute(rate(initial, Rating.EASY))));
            await(reached);
            var reader = pool.submit(() -> as("anna", () ->
                    due(Clock.fixed(TIME, ZoneOffset.UTC), 20).select(select(ref(revision), 20, null))));
            var page = reader.get(30, TimeUnit.SECONDS);
            assertThat(page.items()).extracting(LearningDueSelection.Item::expected).containsExactly(initial.expected());
            assertThat(writer.isDone()).isFalse(); assertThat(release.getCount()).isOne();
            release.countDown(); assertThat(writer.get(30, TimeUnit.SECONDS).snapshot().state().sequence()).isOne();
            login("anna");
            assertThat(due(Clock.fixed(TIME, ZoneOffset.UTC), 20).select(select(ref(revision), 20, null)).outcome())
                    .isEqualTo(LearningDueSelection.Outcome.EMPTY);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void repeatedImmutableQuestionOccurrencesStillSelectOneQuizLocalSchedule() {
        var question = revision.items().get(0).question();
        var repeated = new QuizRevision(UUID.randomUUID(), UUID.randomUUID(), ANNA, 1, "Repeated source", null, TIME,
                List.of(new QuizRevision.Item(UUID.randomUUID(), 0, question), new QuizRevision.Item(UUID.randomUUID(), 1, question)));
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status ->
                new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(sql)).insert(repeated));
        permitted.add(ref(repeated));
        var original = schedules.execute(new Enroll(1, UUID.randomUUID(), ref(repeated), question.id(), FsrsManifest.approved())).snapshot();
        var selected = due(Clock.fixed(TIME, ZoneOffset.UTC), 20).select(select(ref(repeated), 20, null));
        assertThat(selected.items()).extracting(LearningDueSelection.Item::questionRevisionId).containsExactly(question.id());
        assertThat(selected.items()).extracting(LearningDueSelection.Item::expected).containsExactly(original.expected());
        assertThat(count("learning_schedule_slot")).isOne();
    }

    LearningDueSelectionService due(Clock clock, int maximum) {
        var config = new LearningDueSelectionConfig(); config.setMaxBatchSize(maximum);
        var identities = new AuthenticatedUserLookup() {
            @Override public Optional<UUID> findActiveUserId(String name) {
                return sql.query("SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE",
                        (r, n) -> UUID.fromString(r.getString(1)), name).stream().findFirst();
            }
            @Override public Optional<UUID> lockActiveUserId(String name) { throw new AssertionError("selection must not lock identity"); }
        };
        return new LearningDueSelectionServiceImpl(new JdbcLearningDueSelectionRepository(new NamedParameterJdbcTemplate(sql)),
                identities, List.of(this::allows), config, clock, metrics, manager);
    }
    static LearningDueSelectionService.Select select(LearningSession.Source ref, int size, LearningDueSelection.Cursor after) {
        return new LearningDueSelectionService.Select(1, ref, size, after);
    }
    static Map<String, List<Map<String, Object>>> learningState() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("learning_schedule_slot", "learning_schedule_cycle", "learning_fsrs_state",
                "learning_schedule_receipt", "learning_review_event", "learning_cycle_session", "learning_session", "learning_form")) {
            var rows = sql.queryForList("SELECT * FROM " + table + " ORDER BY 1,2");
            rows.forEach(row -> row.replaceAll((column, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
            result.put(table, rows);
        }
        return result;
    }

    LearningScheduleService schedules(JdbcLearningScheduleRepository store, Clock clock) {
        return new LearningScheduleServiceImpl(store, users, List.of(this::allows),
                List.of(session -> completedSessions.contains(session.id())), sessions,
                new FsrsSchedulingStrategyImpl(), clock, metrics, manager);
    }
    boolean allows(UUID learner, LearningSession.Source ref) { return access.get() && permitted.contains(ref); }
    Enroll enroll(int index) { return new Enroll(1, UUID.randomUUID(), ref(revision), question(index), FsrsManifest.approved()); }
    Rate rate(Snapshot current, Rating rating) {
        var id = current.state().identity();
        return new Rate(1, UUID.randomUUID(), new LearningSession.Source(1, id.quizId(), id.quizRevisionId(), 1), id.questionRevisionId(), current.expected(), rating);
    }
    Restart restart(Snapshot current) { return new Restart(1, UUID.randomUUID(), ref(revision), current.state().identity().questionRevisionId(), current.expected(), FsrsManifest.approved()); }
    static UUID question(int index) { return revision.items().get(index).question().id(); }
    static LearningSession.Source ref(QuizRevision rev) { return new LearningSession.Source(1, rev.quizId(), rev.id(), rev.schemaVersion()); }
    LearningSession sessionWithForm(int... itemIndexes) {
        var session = sessions.create(new LearningSessionService.Create(1, UUID.randomUUID(), ref(revision), FsrsManifest.approved())).session();
        var formItems = Arrays.stream(itemIndexes).mapToObj(index -> revision.items().get(index))
                .map(selected -> new LearningForm.Item(selected.id(), selected.question().id(), 1, new ContractVersion("question.mcq-single.response", 1),
                        selected.question().options().stream().map(QuestionRevision.Option::id).toList())).toList();
        var forms = new LearningFormServiceImpl(sessions, new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql)),
                List.of((s, items) -> items.stream().map(i -> new LearningFormContent.PresentedItem(i, "Saved question", new ParticipantContent.Choices(
                        i.optionIds().stream().map(id -> new ParticipantContent.Option(id, "Option")).toList()))).toList()),
                Clock.fixed(TIME, ZoneOffset.UTC), metrics, manager);
        forms.materialize(new LearningFormService.Materialize(1, session.id(), formItems));
        return session;
    }
    static void rejected(Runnable work, LearningScheduleException.Reason reason) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(LearningScheduleException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason); assertThat(failure).hasNoCause(); assertThat(failure.getSuppressed()).isEmpty();
        });
    }
    class HookRepository extends JdbcLearningScheduleRepository {
        Runnable beforeReserve = () -> { };
        Consumer<Snapshot> afterComplete = value -> { };
        HookRepository() { super(new NamedParameterJdbcTemplate(sql)); }
        @Override public Receipt reserve(Key key, UUID command, UUID reservation, String input) { beforeReserve.run(); return super.reserve(key, command, reservation, input); }
        @Override public void complete(Key key, UUID command, UUID reservation, Snapshot result) {
            super.complete(key, command, reservation, result);
            String checkpoint = source.fault.get();
            if ("COMMIT".equals(checkpoint) && source.fault.compareAndSet(checkpoint, null)) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { source.reached.incrementAndGet(); throw new LearningScheduleException(PERSISTENCE_UNAVAILABLE); }
            });
            afterComplete.accept(result);
        }
    }
    static QuizRevision revision(UUID quizId, int count) {
        var items = new ArrayList<QuizRevision.Item>();
        for (int i = 0; i < count; i++) items.add(new QuizRevision.Item(UUID.randomUUID(), i,
                new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.MCQ_SINGLE, Difficulty.EASY,
                        "Saved question " + i, "{\"options\":[{\"id\":\"a\",\"text\":\"Option 0\",\"correct\":true},{\"id\":\"b\",\"text\":\"Option 1\",\"correct\":false}],\"large\":\"" + "x".repeat(4000) + "\"}",
                        null, null, null, null, Instant.parse("2026-09-01T00:00:00Z"), List.of(new QuestionRevision.Option(UUID.randomUUID(), "a", 0), new QuestionRevision.Option(UUID.randomUUID(), "b", 1)))));
        var saved = new QuizRevision(UUID.randomUUID(), quizId, ANNA, 1, "Learning source", null, Instant.parse("2026-09-01T00:00:00Z"), items);
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(sql)).insert(saved);
            for (var item : items) {
                var binding = new QuestionContractBinding(item.question().id(), new ContractVersion("question.mcq-single.response", 1), new ContractVersion("question.mcq-single.binary", 1));
                sql.update("INSERT INTO question_revision_contract_bindings VALUES(UUID_TO_BIN(?),'question.mcq-single.response',1,'question.mcq-single.binary',1,?)", item.question().id().toString(), binding.fingerprint());
            }
        });
        return saved;
    }
    static void legacyFixture() {
        UUID category = UUID.randomUUID(), quiz = UUID.randomUUID(), attempt = UUID.randomUUID(), question = UUID.randomUUID();
        sql.update("INSERT INTO categories(category_id,category_name) VALUES(UUID_TO_BIN(?),'Schedule compatibility')", category.toString());
        sql.update("INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'Existing practice','PUBLIC','EASY','PUBLISHED',1,0,0)", quiz.toString(), ANNA.toString(), category.toString());
        sql.update("INSERT INTO questions(id,type,difficulty,question,content) VALUES(UUID_TO_BIN(?),'TRUE_FALSE','EASY','Existing question','{\"answer\":true}')", question.toString());
        sql.update("INSERT INTO quiz_questions VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?))", quiz.toString(), question.toString());
        sql.update("INSERT INTO attempts(id,user_id,quiz_id,status,mode,total_score) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'COMPLETED','ALL_AT_ONCE',1)", attempt.toString(), ANNA.toString(), quiz.toString());
        sql.update("INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'{\"answer\":true}',TRUE,1,?)", UUID.randomUUID().toString(), attempt.toString(), question.toString(), Timestamp.from(TIME));
    }
    static Map<String, List<Map<String, Object>>> retained() {
        return Map.of("quizzes", sql.queryForList("SELECT HEX(quiz_id) id,title,status,is_repetition_enabled FROM quizzes ORDER BY quiz_id"),
                "attempts", sql.queryForList("SELECT HEX(id) id,status,total_score,accepted_answer_projection FROM attempts ORDER BY id"),
                "answers", sql.queryForList("SELECT HEX(id) id,response,is_correct,score,answered_at FROM answers ORDER BY id"));
    }
    static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    static TransactionTemplate tx(int isolation) { var t = new TransactionTemplate(manager); t.setIsolationLevel(isolation); return t; }
    static void login(String name) { var p = User.withUsername(name).password("unused").authorities("ROLE_USER").build(); SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p, null, p.getAuthorities())); }
    static <T> T as(String name, Supplier<T> work) { login(name); try { return work.get(); } finally { SecurityContextHolder.clearContext(); } }
    static void await(CountDownLatch latch) { try { assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    static void until(Supplier<Boolean> condition, String message) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.get()) { if (System.nanoTime() > end) fail(message); Thread.sleep(10); }
    }
    static boolean waiting(long requester, long blocker) {
        return admin.queryForObject("""
            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
            JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID
            JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID
            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            WHERE r.PROCESSLIST_ID=? AND b.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=?
            AND l.OBJECT_NAME IN ('learning_schedule_receipt','learning_schedule_slot')
            """, Integer.class, requester, blocker, SCHEMA) > 0;
    }
    static class ObservedSource extends DelegatingDataSource {
        record Query(String sql, Map<Integer, Object> parameters) { }
        final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        final ThreadLocal<List<Query>> reads = ThreadLocal.withInitial(ArrayList::new);
        final AtomicInteger rows = new AtomicInteger(), reached = new AtomicInteger(), lostCommits = new AtomicInteger();
        final AtomicReference<String> fault = new AtomicReference<>(); final AtomicBoolean loseCommit = new AtomicBoolean();
        ObservedSource(DriverManagerDataSource delegate) { super(delegate); }
        void reset() { statements.get().clear(); reads.get().clear(); rows.set(0); reached.set(0); fault.set(null); loseCommit.set(false); lostCommits.set(0); }
        @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return observe(super.getConnection(user, password)); }
        void checkpoint(String query) throws SQLException {
            String point = fault.get();
            if (point != null && query.stripLeading().startsWith(point) && fault.compareAndSet(point, null)) { reached.incrementAndGet(); throw new SQLException("PRIVATE_SCHEDULE_FAULT", "45000"); }
        }
        Connection observe(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object value = method.invoke(connection, args);
                    if (method.getName().equals("prepareStatement")) {
                        String query = (String) args[0]; statements.get().add(query);
                        var parameters = new TreeMap<Integer, Object>();
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                            try {
                                if (m.getName().startsWith("set") && a != null && a.length >= 2 && a[0] instanceof Integer index)
                                    parameters.put(index, a[1]);
                                Object result = m.invoke(value, a);
                                if (m.getName().equals("executeQuery") && query.stripLeading().startsWith("SELECT"))
                                    reads.get().add(new Query(query, Map.copyOf(parameters)));
                                if (m.getName().startsWith("execute")) checkpoint(query);
                                if (result instanceof ResultSet rs) return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class}, (rp, rm, ra) -> {
                                    try { Object found = rm.invoke(rs, ra); if (rm.getName().equals("next") && Boolean.TRUE.equals(found)) rows.incrementAndGet(); return found; }
                                    catch (InvocationTargetException e) { throw e.getCause(); }
                                });
                                return result;
                            } catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                    }
                    if (method.getName().equals("commit") && loseCommit.compareAndSet(true, false)) { lostCommits.incrementAndGet(); throw new SQLException("PRIVATE_LOST_COMMIT_ACK", "08006"); }
                    return value;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }
    }
}
