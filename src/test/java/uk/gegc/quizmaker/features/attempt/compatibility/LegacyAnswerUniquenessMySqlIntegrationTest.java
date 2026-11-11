package uk.gegc.quizmaker.features.attempt.compatibility;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.LegacyCompatibilityServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.LegacyInventoryRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcLegacyInventoryRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Real Flyway/SQL invariant and rollout proof; application-command races have separate HTTP fixtures. */
@Tag("db-serial")
class LegacyAnswerUniquenessMySqlIntegrationTest {
    private static final String CONSTRAINT = "uq_answers_attempt_question";

    @Test
    void pausedUpgradeCopiesStoredFactsInitializesEmptyParentsAndPreservesPublishedResults() {
        try (var schema = new OwnedSchema()) {
            UUID parent = schema.attempt(), empty = schema.attempt();
            UUID first = schema.answer(parent, schema.question(), true);
            schema.answer(parent, schema.question(), false);
            UUID nullableScore = schema.answer(parent, schema.question(), true);
            schema.sql.update("UPDATE answers SET score=NULL WHERE id=UUID_TO_BIN(?)", nullableScore.toString());
            schema.sql.update("UPDATE attempts SET status='COMPLETED',total_score=7 WHERE id=UUID_TO_BIN(?)", parent.toString());
            var before = schema.rows();
            Flyway.configure().dataSource(schema.source).locations("classpath:db/migration").target("107").load().migrate();
            assertThat(schema.projection(parent)).isNull();
            assertThat(schema.projection(empty)).isNull();
            schema.migrate();
            assertThat(schema.rows()).isEqualTo(before);
            var reader = new uk.gegc.quizmaker.features.attempt.infra.repository.JsonCurrentAnswerRepository(new com.fasterxml.jackson.databind.ObjectMapper());
            var facts = reader.readCurrent(parent, schema.projection(parent));
            assertThat(facts).hasSize(3);
            assertThat(facts.stream().map(uk.gegc.quizmaker.features.attempt.domain.model.AcceptedAnswerFact::score).toList())
                    .containsExactlyInAnyOrder(1.0, 0.0, null);
            assertThat(facts).allSatisfy(fact -> assertThat(fact.answeredAt()).isEqualTo(java.time.Instant.parse("2026-01-01T00:01:00Z")));
            assertThat(reader.readCurrent(empty, schema.projection(empty))).isEmpty();
            assertThat(schema.sql.queryForObject("SELECT total_score FROM attempts WHERE id=UUID_TO_BIN(?)", Double.class, parent.toString())).isEqualTo(7.0);
            assertThat(schema.sql.queryForObject("SELECT status FROM attempts WHERE id=UUID_TO_BIN(?)", String.class, parent.toString())).isEqualTo("COMPLETED");
            UUID later = schema.attempt();
            assertThat(reader.readCurrent(later, schema.projection(later))).isEmpty();
            schema.answer(later, schema.question(), true);
            assertThat(reader.readCurrent(later, schema.projection(later))).hasSize(1);
            schema.migrate();
            assertThat(schema.sql.queryForObject("SELECT score FROM answers WHERE id=UUID_TO_BIN(?)", Double.class, first.toString())).isEqualTo(1.0);
        }
    }

    @Test
    void partialBackfillFailureKeepsUninitializedStateAndRequiresExplicitRepairBeforeRetry() {
        try (var schema = new OwnedSchema()) {
            UUID first = schema.attempt(), second = schema.attempt();
            schema.answer(first, schema.question(), true); schema.answer(second, schema.question(), false);
            var before = schema.rows();
            Flyway.configure().dataSource(schema.source).locations("classpath:db/migration").target("107").load().migrate();
            String failing = schema.sql.queryForObject("SELECT BIN_TO_UUID(MAX(id)) FROM attempts", String.class);
            schema.sql.execute("CREATE TRIGGER projection_test_fault BEFORE UPDATE ON attempts FOR EACH ROW BEGIN IF NEW.id=UUID_TO_BIN('"+failing+"') AND NEW.accepted_answer_projection IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='projection-backfill-fault'; END IF; END");
            assertThatThrownBy(schema::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining("projection-backfill-fault");
            assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM attempts WHERE accepted_answer_projection IS NOT NULL", Integer.class)).isEqualTo(1);
            assertThat(schema.projection(UUID.fromString(failing))).isNull();
            assertThat(schema.rows()).isEqualTo(before);
            assertThatThrownBy(schema::migrate).isInstanceOf(FlywayException.class);
            assertThatThrownBy(() -> schema.answer(UUID.fromString(failing), schema.question(), true))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("Accepted answer projection is unavailable");
            assertThat(schema.rows()).isEqualTo(before);
            schema.sql.execute("DROP TRIGGER projection_test_fault");
            // Explicit operator recovery in this disposable schema; never an application startup action.
            Flyway.configure().dataSource(schema.source).locations("classpath:db/migration").load().repair();
            schema.migrate();
            assertThat(schema.rows()).isEqualTo(before);
            assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM attempts WHERE accepted_answer_projection IS NULL", Integer.class)).isZero();
            UUID control = schema.attempt(); schema.answer(control, schema.question(), true);
        }
    }

    @Test
    void exactVerificationRejectsChangedProjectionWithoutRewritingOriginalAnswers() {
        try (var schema = new OwnedSchema()) {
            UUID parent = schema.attempt(), id = schema.answer(parent, schema.question(), true);
            var before = schema.rows();
            var injected = new java.util.concurrent.atomic.AtomicBoolean();
            var gated = beforeStatement(schema.source, "CALL reconcile_legacy_answer_projection(FALSE)", () -> {
                injected.set(true);
                schema.sql.update("UPDATE attempts SET accepted_answer_projection=JSON_SET(accepted_answer_projection,?,999) WHERE id=UUID_TO_BIN(?)",
                        "$.answers.\""+id+"\".score", parent.toString());
            });
            assertThatThrownBy(() -> Flyway.configure().dataSource(gated).locations("classpath:db/migration").load().migrate())
                    .isInstanceOf(FlywayException.class).hasStackTraceContaining("Legacy answer projection verification failed");
            assertThat(injected).isTrue(); assertThat(schema.rows()).isEqualTo(before);
            assertThatThrownBy(schema::migrate).isInstanceOf(FlywayException.class);
            Flyway.configure().dataSource(schema.source).locations("classpath:db/migration").load().repair();
            schema.migrate();
            assertThat(schema.rows()).isEqualTo(before);
            var facts = new uk.gegc.quizmaker.features.attempt.infra.repository.JsonCurrentAnswerRepository(new com.fasterxml.jackson.databind.ObjectMapper())
                    .readCurrent(parent, schema.projection(parent));
            assertThat(facts).singleElement().satisfies(fact -> assertThat(fact.score()).isEqualTo(1.0));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 129, 512, 2048})
    void measureProjectionMigrationAndStoredSizeWithoutRegrading(int count) {
        try (var schema = new OwnedSchema()) {
            UUID parent = schema.attempt(); schema.attempt();
            for (int index = 0; index < count; index++) schema.answer(parent, schema.question(), true);
            long started = System.nanoTime(); schema.migrate();
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            var facts = new uk.gegc.quizmaker.features.attempt.infra.repository.JsonCurrentAnswerRepository(new com.fasterxml.jackson.databind.ObjectMapper())
                    .readCurrent(parent, schema.projection(parent));
            assertThat(facts).hasSize(count).allSatisfy(fact -> assertThat(fact.score()).isEqualTo(1.0));
            assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM answers", Integer.class)).isEqualTo(count);
            int bytes = schema.sql.queryForObject("SELECT JSON_STORAGE_SIZE(accepted_answer_projection) FROM attempts WHERE id=UUID_TO_BIN(?)", Integer.class, parent.toString());
            System.out.printf("PROJECTION_MIGRATION answers=%d elapsedMs=%d jsonBytes=%d%n", count, elapsed, bytes);
            assertThat(elapsed).isLessThan(60_000L);
        }
    }

    private static javax.sql.DataSource beforeStatement(javax.sql.DataSource source, String prefix, Runnable action) {
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        return new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        if (result instanceof java.sql.Statement statement) return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{method.getReturnType()}, (statementProxy, operation, parameters) -> {
                            if (parameters != null && parameters.length > 0 && parameters[0] instanceof String sql && sql.stripLeading().startsWith(prefix) && once.compareAndSet(false,true)) action.run();
                            try { return operation.invoke(statement, parameters); }
                            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
    }

    @Test
    void cleanUpgradePreservesEvidenceRejectsDuplicateInsertAndUpdateAndKeepsValidPairs() {
        try (var schema = new OwnedSchema()) {
            UUID attempt = schema.attempt(), otherAttempt = schema.attempt();
            UUID question = schema.question(), otherQuestion = schema.question();
            UUID original = schema.answer(attempt, question, true);
            var before = schema.rows();

            schema.migrate();
            assertThat(schema.rows()).isEqualTo(before);
            for (boolean response : List.of(true, false)) {
                assertThatThrownBy(() -> schema.answer(attempt, question, response))
                        .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(CONSTRAINT);
                assertThat(schema.rows()).isEqualTo(before);
            }
            assertThat(schema.indexColumns()).containsExactly("attempt_id", "question_id");
            UUID other = schema.answer(attempt, otherQuestion, true);
            schema.answer(otherAttempt, question, false);
            assertThatThrownBy(() -> schema.sql.update("UPDATE answers SET question_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",
                    question.toString(), other.toString()))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(CONSTRAINT);
            assertThat(schema.sql.queryForObject("SELECT BIN_TO_UUID(question_id) FROM answers WHERE id=UUID_TO_BIN(?)",
                    String.class, other.toString())).isEqualTo(otherQuestion.toString());
            assertThat(schema.sql.queryForObject("SELECT score FROM answers WHERE id=UUID_TO_BIN(?)",
                    Double.class, original.toString())).isEqualTo(1.0);

            var plan = schema.sql.queryForMap("EXPLAIN SELECT id FROM answers WHERE attempt_id=UUID_TO_BIN(?) AND question_id=UUID_TO_BIN(?)",
                    attempt.toString(), question.toString());
            assertThat(plan.get("key")).isEqualTo(CONSTRAINT);
            assertThat(plan.get("type")).isEqualTo("const");
            schema.migrate(); // A later process can validate/replay migrations without changing retained evidence.
            assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM answers", Integer.class)).isEqualTo(3);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void historicalExactOrConflictingDuplicatesStopRolloutWithoutChoosingOrRegrading(boolean conflicting) {
        try (var schema = new OwnedSchema()) {
            UUID attempt = schema.attempt(), question = schema.question();
            schema.answer(attempt, question, true);
            schema.answer(attempt, question, !conflicting);
            schema.sql.update("UPDATE attempts SET status='COMPLETED',completed_at='2026-01-02',total_score=2 WHERE id=UUID_TO_BIN(?)",
                    attempt.toString());
            schema.sql.update("INSERT INTO quiz_analytics_snapshot(quiz_id,attempts_count) VALUES (UUID_TO_BIN(?),7)", schema.quiz.toString());
            var answers = schema.rows();
            var attempts = schema.sql.queryForList("SELECT * FROM attempts ORDER BY id");
            var snapshots = schema.sql.queryForList("SELECT * FROM quiz_analytics_snapshot ORDER BY quiz_id");
            assertDuplicatePreflight(schema.preflight());

            assertThatThrownBy(schema::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining(CONSTRAINT);
            assertThat(schema.indexColumns()).isEmpty();
            assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='106' AND success=1",
                    Integer.class)).isZero();
            assertThat(schema.rows()).isEqualTo(answers);
            assertThat(schema.sql.queryForList("SELECT * FROM attempts ORDER BY id")).usingRecursiveComparison().isEqualTo(attempts);
            assertThat(schema.sql.queryForList("SELECT * FROM quiz_analytics_snapshot ORDER BY quiz_id"))
                    .usingRecursiveComparison().isEqualTo(snapshots);
            // New reader/service instances still report the same discrepancy after the failed deployment.
            assertDuplicatePreflight(schema.preflight());
            assertThatThrownBy(schema::migrate).isInstanceOf(FlywayException.class);
            assertThat(schema.rows()).isEqualTo(answers);
        }
    }

    @Test
    void databaseGuardSerializesIndependentInsertsWithoutApplicationLocks() throws Exception {
        try (var schema = new OwnedSchema()) {
            schema.migrate();
            UUID attempt = schema.attempt(), question = schema.question();
            var inserted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Future<UUID> first = executor.submit(() -> new TransactionTemplate(schema.manager).execute(status -> {
                    UUID id = schema.answer(attempt, question, true);
                    inserted.countDown();
                    await(release);
                    return id;
                }));
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Throwable> second = executor.submit(() -> {
                    try {
                        new TransactionTemplate(schema.manager).executeWithoutResult(status -> {
                            secondStarted.countDown();
                            schema.answer(attempt, question, false);
                        });
                        return null;
                    } catch (DataIntegrityViolationException conflict) { return conflict; }
                });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                UUID winner = first.get(10, TimeUnit.SECONDS);
                assertThat(winner).isNotNull();
                assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining(CONSTRAINT);
                assertThat(schema.sql.queryForObject("SELECT BIN_TO_UUID(id) FROM answers WHERE attempt_id=UUID_TO_BIN(?)",
                        String.class, attempt.toString())).isEqualTo(winner.toString());
                assertThat(schema.sql.queryForObject("SELECT SUM(score) FROM answers WHERE attempt_id=UUID_TO_BIN(?)",
                        Double.class, attempt.toString())).isEqualTo(1.0);
            } finally {
                release.countDown(); executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static void assertDuplicatePreflight(Report report) {
        assertThat(report.complete()).isTrue();
        var answers = report.families().stream().filter(f -> f.family() == Family.ANSWER).findFirst().orElseThrow();
        assertThat(answers.findings().get(Finding.DUPLICATE_ANSWER)).isEqualTo(2L);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for insert gate"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class OwnedSchema implements AutoCloseable {
        private final String catalog = "quizmaker_848_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DriverManagerDataSource source;
        private final JdbcTemplate sql;
        private final DataSourceTransactionManager manager;
        private final UUID user = UUID.randomUUID(), quiz = UUID.randomUUID();

        OwnedSchema() {
            String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
            if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
            String username = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
            String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
            String base = "jdbc:mysql://127.0.0.1:" + port + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
            admin = new JdbcTemplate(new DriverManagerDataSource(base + options, username, password));
            admin.execute("CREATE DATABASE " + catalog + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            source = new DriverManagerDataSource(base + catalog + options, username, password);
            sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
            try {
                Flyway.configure().dataSource(source).locations("classpath:db/migration").target("105").load().migrate();
                sql.update("""
                        INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version)
                        VALUES (UUID_TO_BIN(?),?,?,'fixture',1,0,0,'2026-01-01','2026-01-01',0)
                        """, user.toString(), "uniqueness-" + user, user + "@example.invalid");
                UUID category = UUID.randomUUID();
                sql.update("INSERT INTO categories(category_id,category_name) VALUES (UUID_TO_BIN(?),?)", category.toString(), "uniqueness-" + category);
                sql.update("""
                        INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled)
                        VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'Uniqueness fixture','PUBLIC','EASY','PUBLISHED',1,0,0)
                        """, quiz.toString(), user.toString(), category.toString());
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        String projection(UUID parent) {
            return sql.queryForObject("SELECT accepted_answer_projection FROM attempts WHERE id=UUID_TO_BIN(?)", String.class, parent.toString());
        }

        void migrate() { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate(); }

        UUID attempt() {
            UUID id = UUID.randomUUID();
            sql.update("""
                    INSERT INTO attempts(id,user_id,quiz_id,mode,status,started_at)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'ALL_AT_ONCE','IN_PROGRESS','2026-01-01')
                    """, id.toString(), user.toString(), quiz.toString());
            return id;
        }

        UUID question() {
            UUID id = UUID.randomUUID();
            sql.update("INSERT INTO questions(id,type,difficulty,question,content) VALUES (UUID_TO_BIN(?),'TRUE_FALSE','EASY','Synthetic question','{\"answer\":true}')", id.toString());
            sql.update("INSERT INTO quiz_questions(quiz_id,question_id) VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?))", quiz.toString(), id.toString());
            return id;
        }

        UUID answer(UUID attempt, UUID question, boolean correct) {
            UUID id = UUID.randomUUID();
            sql.update("""
                    INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?, ?,?,'2026-01-01 00:01:00')
                    """, id.toString(), attempt.toString(), question.toString(), "{\"answer\":" + correct + "}", correct, correct ? 1.0 : 0.0);
            return id;
        }

        List<Map<String, Object>> rows() {
            return sql.queryForList("SELECT BIN_TO_UUID(id) AS id,BIN_TO_UUID(attempt_id) AS attempt_id,BIN_TO_UUID(question_id) AS question_id,response,is_correct,score,answered_at FROM answers ORDER BY id");
        }

        List<String> indexColumns() {
            return sql.queryForList("""
                    SELECT column_name FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name='answers' AND index_name=? AND non_unique=0 ORDER BY seq_in_index
                    """, String.class, CONSTRAINT);
        }

        Report preflight() {
            var proxy = new ProxyFactory(new JdbcLegacyInventoryRepository(new NamedParameterJdbcTemplate(source)));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var metrics = new SimpleMeterRegistry();
            try {
                return new LegacyCompatibilityServiceImpl((LegacyInventoryRepository) proxy.getProxy(),
                        new AttemptPresetServiceImpl(), manager, metrics).inventory(new Limits(8, 1000, 30));
            } finally { metrics.close(); }
        }

        @Override public void close() { admin.execute("DROP DATABASE " + catalog); }
    }
}
