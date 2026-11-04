package uk.gegc.quizmaker.features.attempt.compatibility;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.LegacyCompatibilityService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.LegacyCompatibilityServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyInventoryException;
import uk.gegc.quizmaker.features.attempt.domain.repository.LegacyInventoryRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcLegacyInventoryRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** A new fully migrated catalog per class, with no application context or external providers. */
@Tag("db-serial")
class LegacyInventoryMySqlIntegrationTest {
    private static JdbcTemplate admin, sql;
    private static String catalog;
    private static InventoryDataSource source;
    private static DataSourceTransactionManager manager;
    private LegacyCompatibilityService service;
    private LegacyInventoryRepository repository;
    private SimpleMeterRegistry metrics;
    private UUID user, category, quiz, question;
    private static final String MARKER = "SYNTHETIC_PRIVATE_MARKER_608";
    private static final Limits LIMITS = new Limits(8, 1000, 30);

    @BeforeAll static void migrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String username = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, username, password));
        String name = "quizmaker_608_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        catalog = name;
        source = new InventoryDataSource(new DriverManagerDataSource(base + name + options, username, password));
        sql = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("91").load().migrate();
    }

    @BeforeEach void prepare() {
        source.reset();
        for (String table : List.of("repetition_review_log", "spaced_repetition_entry", "answers", "attempts", "share_link_usage", "share_link_analytics",
                "share_links", "quiz_analytics_snapshot", "quiz_questions", "quizzes", "questions", "categories")) {
            sql.update("DELETE FROM " + table);
        }
        user = user("inventory-" + UUID.randomUUID()); category = UUID.randomUUID();
        sql.update("INSERT INTO categories(category_id,category_name) VALUES (?,?)", bytes(category), category.toString());
        quiz = quiz(); question = question(QuestionType.TRUE_FALSE);
        repository = proxy(new JdbcLegacyInventoryRepository(new NamedParameterJdbcTemplate(source)));
        metrics = new SimpleMeterRegistry();
        service = new LegacyCompatibilityServiceImpl(repository, new AttemptPresetServiceImpl(), manager, metrics);
        source.reset();
    }

    @AfterEach void reset() { source.reset(); metrics.close(); }
    @AfterAll static void dropOnlyOwnedCatalog() {
        if (source != null) source.reset();
        if (catalog != null) admin.execute("DROP DATABASE " + catalog);
    }

    @Test void knownModesStatusesFamiliesAndLegacyResultsReconcileWithoutInventingHistory() throws Exception {
        for (String mode : List.of("ALL_AT_ONCE", "ONE_BY_ONE", "TIMED"))
            for (String state : List.of("IN_PROGRESS", "PAUSED", "COMPLETED", "ABANDONED")) attempt(mode, state);
        UUID a = attempt("ALL_AT_ONCE", "COMPLETED");
        for (QuestionType type : QuestionType.values()) answer(a, question(type));
        sql.update("INSERT INTO quiz_analytics_snapshot(quiz_id,attempts_count) VALUES (?,13)", bytes(quiz));
        var report = service.inventory(LIMITS);
        assertThat(report.complete()).isTrue(); assertThat(report.emptyRepetitionConfirmed()).isTrue();
        assertCount(report, Family.ATTEMPT, Finding.LEGACY_HISTORY, 13);
        assertCount(report, Family.ANSWER, Finding.LEGACY_HISTORY, 9);
        assertCount(report, Family.RESULT_SNAPSHOT, Finding.LEGACY_AGGREGATE, 1);
        assertThat(report.families()).allSatisfy(c -> assertThat(c.findings().keySet())
                .noneMatch(f -> f.classification() == Classification.SUPPORTED_ADAPTER));
        String serialized = new ObjectMapper().writeValueAsString(report);
        assertThat(serialized).doesNotContain(MARKER, user.toString(), quiz.toString(), question.toString(), a.toString());
        assertNoTargetRows();
        assertThat(metrics.get("attempt.legacy.inventory").tag("outcome", "complete").counter().count()).isEqualTo(1);
    }

    @Test void everyReaderWorksAgainstTheFullMigrationSchemaWithoutOrmSchemaRepair() {
        var transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> {
            for (Family family : Family.values()) repository.read(family, null, 10);
        });
    }

    @ParameterizedTest @EnumSource(QuizStatus.class)
    void everyCurrentQuizStatusIsKnownAndDisabledTimerIgnoresStoredInactiveDuration(QuizStatus status) {
        sql.update("UPDATE quizzes SET status=?,is_timer_enabled=0,timer_duration_min=-9", status.name());
        assertCount(service.inventory(LIMITS), Family.QUIZ_CONFIGURATION, Finding.MUTABLE_CONFIGURATION, 1);
    }

    @Test void exactAndConflictingDuplicatesAreAllQuarantinedWithoutReadingOrChoosingAnswers() {
        UUID a = attempt("ALL_AT_ONCE", "COMPLETED");
        UUID first = answer(a, question); answer(a, question);
        UUID secondQuestion = question(QuestionType.OPEN); answer(a, secondQuestion);
        UUID conflict = answer(a, secondQuestion);
        sql.update("UPDATE answers SET response=JSON_OBJECT('answer',?),score=0 WHERE id=?", MARKER, bytes(conflict));
        var before = sql.queryForList("SELECT id,response,score FROM answers ORDER BY id");
        var report = service.inventory(LIMITS);
        assertCount(report, Family.ANSWER, Finding.DUPLICATE_ANSWER, 4);
        assertThat(sql.queryForList("SELECT id,response,score FROM answers ORDER BY id")).usingRecursiveComparison().isEqualTo(before);
        assertThat(first).isNotNull(); assertNoTargetRows();
    }

    @Test void brokenReferencesUnknownNamesAndSentinelAmbiguityHaveExplicitBuckets() {
        UUID unknown = attempt("future", "IN_PROGRESS");
        UUID inconsistent = attempt("ALL_AT_ONCE", "COMPLETED");
        sql.update("UPDATE attempts SET completed_at=NULL WHERE id=?", bytes(inconsistent));
        UUID missing = attempt("TIMED", "IN_PROGRESS");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            sql.execute("SET FOREIGN_KEY_CHECKS=0");
            try { sql.update("UPDATE attempts SET quiz_id=? WHERE id=?", bytes(UUID.randomUUID()), bytes(missing)); }
            finally { sql.execute("SET FOREIGN_KEY_CHECKS=1"); }
        });
        UUID sentinel = user("anonymous");
        UUID ambiguous = attempt("ALL_AT_ONCE", "IN_PROGRESS");
        sql.update("UPDATE attempts SET user_id=? WHERE id=?", bytes(sentinel), bytes(ambiguous));
        var report = service.inventory(LIMITS);
        assertCount(report, Family.ATTEMPT, Finding.UNKNOWN_VALUE, 1);
        assertCount(report, Family.ATTEMPT, Finding.INCONSISTENT_STATE, 1);
        assertCount(report, Family.ATTEMPT, Finding.BROKEN_REFERENCE, 1);
        assertCount(report, Family.ATTEMPT, Finding.AMBIGUOUS_IDENTITY, 1);
        assertThat(sql.queryForObject("SELECT mode FROM attempts WHERE id=?", String.class, bytes(unknown))).isEqualTo("future");
    }

    @Test void sharedAttemptsAndRevokedExpiredOneTimeLinksRemainLegacyEvidence() {
        UUID sentinel = user("anonymous");
        for (String scope : List.of("QUIZ_VIEW", "QUIZ_ATTEMPT_START")) {
            UUID link = link(scope);
            sql.update("UPDATE share_links SET expires_at='2000-01-01',revoked_at='2000-01-02',one_time=1 WHERE id=?", bytes(link));
            UUID attempt = attempt("ALL_AT_ONCE", "COMPLETED");
            sql.update("UPDATE attempts SET user_id=?,share_link_id=? WHERE id=?", bytes(sentinel), bytes(link), bytes(attempt));
        }
        link("UNKNOWN");
        var report = service.inventory(LIMITS);
        assertCount(report, Family.SHARE_LINK, Finding.LEGACY_CAPABILITY, 2);
        assertCount(report, Family.SHARE_LINK, Finding.UNKNOWN_VALUE, 1);
        assertCount(report, Family.ATTEMPT, Finding.LEGACY_HISTORY, 2);
    }

    @Test void shareUsageAndAllKnownAnalyticsEventsAreCountedWithoutVisitorMetadata() throws Exception {
        UUID link = link("QUIZ_VIEW");
        sql.update("INSERT INTO share_link_usage(id,share_link_id,user_agent,ip_hash) VALUES (?,?,?,?)",
                bytes(UUID.randomUUID()), bytes(link), MARKER, MARKER);
        for (String event : List.of("VIEW", "ATTEMPT_START", "CONSUMED", "CREATED", "REVOKED", "FUTURE")) {
            sql.update("INSERT INTO share_link_analytics(id,share_link_id,event_type,ip_hash,date_bucket,user_agent,referrer) VALUES (?,?,?,?,?,?,?)",
                    bytes(UUID.randomUUID()), bytes(link), event, MARKER, "2026-01-01", MARKER, MARKER);
        }
        var report = service.inventory(LIMITS);
        assertCount(report, Family.SHARE_LINK_USAGE, Finding.LEGACY_CAPABILITY, 1);
        assertCount(report, Family.SHARE_LINK_EVENT, Finding.LEGACY_CAPABILITY, 5);
        assertCount(report, Family.SHARE_LINK_EVENT, Finding.UNKNOWN_VALUE, 1);
        assertThat(new ObjectMapper().writeValueAsString(report)).doesNotContain(MARKER);
    }

    @Test void invalidTimerAndUnknownOrCaseChangedVocabularyAreNotSilentlyNormalized() {
        sql.update("UPDATE quizzes SET is_timer_enabled=1,timer_duration_min=0");
        assertCount(service.inventory(LIMITS), Family.QUIZ_CONFIGURATION, Finding.INCONSISTENT_STATE, 1);
        sql.update("UPDATE quizzes SET is_timer_enabled=0,status='published'");
        assertCount(service.inventory(LIMITS), Family.QUIZ_CONFIGURATION, Finding.UNKNOWN_VALUE, 1);
        UUID a = attempt("all_at_once", "IN_PROGRESS"); answer(a, question);
        sql.update("UPDATE questions SET type='future-family'");
        var report = service.inventory(LIMITS);
        assertCount(report, Family.ATTEMPT, Finding.UNKNOWN_VALUE, 1);
        assertCount(report, Family.ANSWER, Finding.UNKNOWN_VALUE, 1);
    }

    @Test void missingCurrentQuestionMembershipIsUnknownRatherThanInventedHistoricalContent() {
        UUID a = attempt("ALL_AT_ONCE", "COMPLETED"); answer(a, question);
        sql.update("DELETE FROM quiz_questions WHERE quiz_id=?", bytes(quiz));
        assertCount(service.inventory(LIMITS), Family.ANSWER, Finding.BROKEN_REFERENCE, 1);
        assertThat(count("answers")).isEqualTo(1); assertNoTargetRows();
    }

    @Test void unexpectedRepetitionConfigurationEntriesAndLogsAreRetainedAndReported() {
        assertThat(service.inventory(LIMITS).emptyRepetitionConfirmed()).isTrue();
        sql.update("UPDATE quizzes SET is_repetition_enabled=1");
        UUID entry = UUID.randomUUID();
        sql.update("INSERT INTO spaced_repetition_entry(id,user_id,question_id,next_review_at,interval_days,repetition_count,ease_factor) VALUES (?,?,?,NOW(),1,0,2.5)",
                bytes(entry), bytes(user), bytes(question));
        sql.update("INSERT INTO repetition_review_log(repetition_review_id,user_id,entry_id,content_type,content_id,grade,interval_days,ease_factor,repetition_count,source_type) VALUES (?,?,?,'QUESTION',?,'GOOD',1,2.5,1,'DIRECT')",
                bytes(UUID.randomUUID()), bytes(user), bytes(entry), bytes(question));
        var report = service.inventory(LIMITS);
        assertThat(report.emptyRepetitionConfirmed()).isFalse();
        for (Family family : List.of(Family.QUIZ_CONFIGURATION, Family.REPETITION_ENTRY, Family.REPETITION_REVIEW))
            assertCount(report, family, Finding.UNEXPECTED_REPETITION, 1);
        assertThat(count("spaced_repetition_entry")).isEqualTo(1);
        assertThat(count("repetition_review_log")).isEqualTo(1);
    }

    @Test void keysetBudgetIsHonestAtExactBoundaryAndAcrossUnsignedUuidOrder() {
        for (int i = 0; i < 17; i++) attempt("ALL_AT_ONCE", "IN_PROGRESS");
        var existing = sql.queryForList("SELECT BIN_TO_UUID(id) FROM attempts ORDER BY id LIMIT 4", String.class);
        var boundaries = List.of("00000000-0000-0000-0000-000000000000", "7fffffff-ffff-ffff-ffff-ffffffffffff",
                "80000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff");
        for (int i = 0; i < boundaries.size(); i++) sql.update("UPDATE attempts SET id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",
                boundaries.get(i), existing.get(i));
        var partial = service.inventory(new Limits(3, 16, 30));
        assertThat(partial.complete()).isFalse();
        assertThat(family(partial, Family.ATTEMPT).complete()).isFalse();
        assertCount(partial, Family.ATTEMPT, Finding.LEGACY_HISTORY, 16);
        var exact = service.inventory(new Limits(3, 17, 30));
        assertThat(exact.complete()).isTrue(); assertCount(exact, Family.ATTEMPT, Finding.LEGACY_HISTORY, 17);
        assertThat(metrics.get("attempt.legacy.inventory").tag("outcome", "truncated").counter().count()).isEqualTo(1);
    }

    @Test void allPagesAndFamiliesUseOneSnapshotDespiteConcurrentCommittedChanges() {
        for (int i = 0; i < 5; i++) attempt("ALL_AT_ONCE", "IN_PROGRESS");
        var changed = new AtomicBoolean();
        source.after = (query, connection) -> {
            if (query.contains("FROM attempts ORDER") && changed.compareAndSet(false, true)) {
                // DriverManagerDataSource gives the other writer an independent committed connection.
                var writer = new JdbcTemplate(Objects.requireNonNull(source.getTargetDataSource()));
                writer.update("UPDATE quizzes SET is_repetition_enabled=1");
                writer.update("UPDATE attempts SET mode='FUTURE'");
            }
        };
        var report = service.inventory(new Limits(2, 100, 30));
        source.after = null;
        assertThat(changed).isTrue(); assertCount(report, Family.ATTEMPT, Finding.LEGACY_HISTORY, 5);
        assertCount(report, Family.QUIZ_CONFIGURATION, Finding.MUTABLE_CONFIGURATION, 1);
        assertThat(report.emptyRepetitionConfirmed()).isTrue();
        var next = service.inventory(LIMITS);
        assertCount(next, Family.ATTEMPT, Finding.UNKNOWN_VALUE, 5);
        assertThat(next.emptyRepetitionConfirmed()).isFalse();
    }

    @Test void queryCountDependsOnPagesNotRelationshipsAndPlansUseBoundedKeysetWindows() {
        for (int parent = 0; parent < 3; parent++) {
            UUID a = attempt("ALL_AT_ONCE", "COMPLETED");
            for (int child = 0; child < 8; child++) answer(a, question(QuestionType.TRUE_FALSE));
        }
        source.reset(); service.inventory(new Limits(100, 1000, 30));
        var queries = List.copyOf(source.queries);
        assertThat(queries).hasSize(9);
        source.reset(); service.inventory(new Limits(4, 1000, 30));
        assertThat(source.queries).hasSize(14); // six answer pages; one page for every other family
        assertThat(source.queries.stream().filter(q -> q.text().contains(" WHERE id>?"))).hasSize(5);
        var continuation = source.queries.stream().filter(q -> q.text().contains(" WHERE id>?")).findFirst().orElseThrow();

        // On a tiny table MySQL can correctly prefer a scan. Prove indexed relationship access
        // with realistic competing parents, without depending on generated foreign-key index names.
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            for (int parent = 0; parent < 64; parent++) {
                UUID a = attempt("ALL_AT_ONCE", "COMPLETED");
                for (int child = 0; child < 8; child++) answer(a, question(QuestionType.TRUE_FALSE));
            }
        });
        // Refresh statistics only in this newly populated fixture, not in the read-only inventory.
        sql.execute("ANALYZE TABLE answers,attempts,questions,quiz_questions");
        source.reset(); service.inventory(new Limits(1000, 1000, 30));
        assertThat(source.queries).hasSize(9); // still one query per family: 67 parents, 536 answers
        assertThat(queries).allSatisfy(q -> {
            assertThat(q.text()).startsWith("SELECT r.id,").contains(" LIMIT ?)", "ORDER BY r.id")
                    .doesNotContain("SELECT *", "r.response", "r.content", "token_hash", "email");
            var plan = sql.queryForList("EXPLAIN " + q.text(), q.parameters().toArray());
            assertThat(plan).isNotEmpty();
            assertThat(plan.stream().filter(step -> Set.of("u", "q", "a", "s", "c", "e", "membership", "other")
                    .contains(step.get("table")))).allSatisfy(step -> assertThat(step.get("type"))
                    .as("relationship lookup: %s", step).isIn("eq_ref", "ref", "const", "system", "unique_subquery", "index_subquery"));
        });
        assertThat(sql.queryForList("EXPLAIN " + continuation.text(), continuation.parameters().toArray()))
                .anySatisfy(step -> {
                    assertThat(step.get("table")).isEqualTo("answers");
                    assertThat(step.get("type")).isEqualTo("range");
                    assertThat(step.get("key")).isEqualTo("PRIMARY");
                });
    }

    @Test void transactionDeadlineNeverReturnsACompleteOrPartialSuccess() {
        var delayed = new AtomicBoolean();
        source.after = (query, connection) -> {
            if (query.startsWith("SELECT r.id,") && delayed.compareAndSet(false, true)) {
                try { Thread.sleep(1200); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException("Interrupted fixture"); }
            }
        };
        assertThatThrownBy(() -> service.inventory(new Limits(2, 100, 1))).isInstanceOf(LegacyInventoryException.class).hasNoCause();
        source.after = null;
        assertThat(metrics.get("attempt.legacy.inventory").tag("outcome", "unavailable").counter().count()).isEqualTo(1);
        assertThat(service.inventory(LIMITS).complete()).isTrue();
    }

    @Test void unavailableSchemaAndMidScanFailureReturnNoPartialReportOrSensitiveTransactionDiagnostics() {
        Logger logger = (Logger) LoggerFactory.getLogger(TransactionTemplate.class);
        Level previous = logger.getLevel(); var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender); logger.setLevel(Level.DEBUG);
        try {
            source.before = (query, connection) -> {
                if (query.contains("FROM answers")) throw new SQLException(MARKER);
            };
            assertThatThrownBy(() -> service.inventory(LIMITS)).isInstanceOf(LegacyInventoryException.class)
                    .hasMessage("Legacy inventory unavailable").hasNoCause();
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                    .doesNotContain(MARKER));
            source.before = null;
            sql.execute("RENAME TABLE repetition_review_log TO inventory_unavailable_fixture");
            try { assertThatThrownBy(() -> service.inventory(LIMITS)).isInstanceOf(LegacyInventoryException.class).hasNoCause(); }
            finally { sql.execute("RENAME TABLE inventory_unavailable_fixture TO repetition_review_log"); }
            assertThat(service.inventory(LIMITS).complete()).isTrue(); assertNoTargetRows();
        } finally { source.reset(); logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }

    @Test void independentReadOnlyTransactionCannotWriteOrSeeAnOuterUncommittedWrite() {
        var outer = new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            attempt("ALL_AT_ONCE", "IN_PROGRESS");
            assertThat(family(service.inventory(LIMITS), Family.ATTEMPT).examined()).isZero();
            status.setRollbackOnly();
        });
        source.before = (query, connection) -> {
            if (query.startsWith("SELECT r.id,")) {
                assertThat(connection.isReadOnly()).isTrue();
                assertThat(connection.getTransactionIsolation()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                try (var statement = connection.createStatement()) {
                    assertThatThrownBy(() -> statement.executeUpdate("UPDATE quizzes SET is_repetition_enabled=1"))
                            .isInstanceOf(SQLException.class);
                }
            }
        };
        assertThat(service.inventory(LIMITS).emptyRepetitionConfirmed()).isTrue();
        source.before = null; assertThat(count("attempts")).isZero();
    }

    private UUID user(String name) {
        if (name.equals("anonymous")) {
            var existing = sql.queryForList("SELECT BIN_TO_UUID(user_id) FROM users WHERE username='anonymous'", String.class);
            if (!existing.isEmpty()) return UUID.fromString(existing.get(0));
        }
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version) VALUES (?,?,?,'fixture',1,0,0,NOW(),NOW(),0)",
                bytes(id), name, id + "@example.invalid"); return id;
    }
    private UUID quiz() {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled) VALUES (?,?,?,?,'PUBLIC','EASY','PUBLISHED',1,0,0)",
                bytes(id), bytes(user), bytes(category), id.toString()); return id;
    }
    private UUID question(QuestionType type) {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO questions(id,type,difficulty,question,content) VALUES (?,?,'EASY',?,?)",
                bytes(id), type.name(), MARKER, QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString());
        sql.update("INSERT INTO quiz_questions(quiz_id,question_id) VALUES (?,?)", bytes(quiz), bytes(id)); return id;
    }
    private UUID attempt(String mode, String status) {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO attempts(id,user_id,quiz_id,mode,status,started_at,completed_at,total_score) VALUES (?,?,?,?,?,'2026-01-01',?,?)",
                bytes(id), bytes(user), bytes(quiz), mode, status,
                status.equals("COMPLETED") || status.equals("ABANDONED") ? "2026-01-02" : null,
                status.equals("COMPLETED") ? 1.0 : null); return id;
    }
    private UUID answer(UUID attempt, UUID question) {
        UUID id = UUID.randomUUID();
        var type = QuestionType.valueOf(sql.queryForObject("SELECT type FROM questions WHERE id=?", String.class, bytes(question)));
        sql.update("INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at) VALUES (?,?,?,?,1,1,'2026-01-01 00:01:00')",
                bytes(id), bytes(attempt), bytes(question), QuestionContracts.example(type, QuestionContracts.View.RESPONSE).toString()); return id;
    }
    private UUID link(String scope) {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO share_links(id,quiz_id,created_by,token_hash,scope,one_time) VALUES (?,?,?,?,?,0)",
                bytes(id), bytes(quiz), bytes(user), MARKER, scope); return id;
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private long count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static FamilyCount family(Report report, Family family) { return report.families().stream().filter(f -> f.family() == family).findFirst().orElseThrow(); }
    private static void assertCount(Report report, Family family, Finding finding, long expected) {
        assertThat(family(report, family).findings().getOrDefault(finding, 0L)).as(family + ":" + finding).isEqualTo(expected);
    }
    private void assertNoTargetRows() {
        for (String table : List.of("quiz_revisions", "attempt_lifecycle_state", "attempt_command_receipt"))
            assertThat(count(table)).as(table).isZero();
    }
    private static LegacyInventoryRepository proxy(Object target) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (LegacyInventoryRepository) proxy.getProxy();
    }
}
