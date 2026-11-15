package uk.gegc.quizmaker.features.attempt.response;

import java.util.*;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in diagnostic, not a Surefire *Test. Run its method explicitly on an isolated MySQL server.
 * Reuses real publication/start/acceptance; no timing thresholds or remote providers.
 */
class ResponseWriteCostProbe extends ResponseAcceptanceMySqlIntegrationTest {
    @ParameterizedTest @CsvSource({"100,false", "250,false", "500,false", "100,true", "250,true", "500,true"})
    void measureWriteCost(int size, boolean batch) throws Exception {
        String historical = System.getProperty("response.cost.historicalMigrations");
        if (historical != null) {
            // Only a disposable diagnostic schema: the two never-deployed migrations are
            // supplied outside the repository, not added to application migration resources.
            Flyway.configure().dataSource(source).locations("filesystem:" + historical)
                    .validateOnMigrate(false).load().migrate();
        }
        var config = admin.queryForMap("SELECT VERSION() version, @@global.transaction_isolation isolation_level, "
                + "@@global.log_bin binlog, @@global.binlog_format binlog_format, @@global.binlog_row_image row_image, "
                + "@@global.innodb_buffer_pool_size buffer_pool, @@global.innodb_page_size page_size");
        assertThat(config.get("binlog").toString()).isIn("1", "true");
        for (int sample = 1; sample <= 3; sample++) {
            if (sample > 1) quizId = newQuiz(); // One active attempt per owner/revision, as in production.
            var revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList());
            binding.bind(quizId, revision);
            var started = starts.start(command(revision, AnswerOrder.AUTHORED, "cost-start-" + sample));
            var items = delivery.read(started.attemptId()).form().items();
            // Allocate commands before sampling; fixture creation and assertions are excluded.
            var requests = IntStream.range(0, size).mapToObj(i -> answer(started.attemptId(), items.get(i),
                    i + 1, "cost-single-" + started.attemptId() + "-" + i)).toList();
            var batchRequest = batch(started.attemptId(), items, 1, "cost-batch-" + started.attemptId());
            observer().reset();
            var before = counters(); long start = System.nanoTime();
            int selects = 0;
            if (batch) {
                assertThat(batches.acceptBatch(batchRequest).responses()).hasSize(size);
                selects = observer().selects.size(); observer().selects.clear();
            } else {
                for (var request : requests) {
                    responses.accept(request);
                    selects += observer().selects.size(); observer().selects.clear();
                }
            }
            long nanos = System.nanoTime() - start; var after = counters();
            var result = new LinkedHashMap<String, Object>();
            result.put("mode", batch ? "batch" : "single"); result.put("items", size); result.put("sample", sample);
            result.put("historical_projections", historical != null); result.put("config", config);
            result.put("elapsed_ms", nanos / 1_000_000.0); result.put("jdbc_selects", selects);
            before.forEach((key, value) -> {
                long delta = after.get(key) - value;
                assertThat(delta).as("Monotonic counter %s", key).isNotNegative();
                result.put(key, delta);
            });
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response WHERE attempt_id=UUID_TO_BIN(?)",
                    Integer.class, started.attemptId().toString())).isEqualTo(size);
            assertThat(sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?)",
                    Long.class, started.attemptId().toString())).isEqualTo(size + 1L);
            System.out.println("RESPONSE_WRITE_COST " + json.writeValueAsString(result));
        }
    }

    @ParameterizedTest @CsvSource({"5,TRUE_FALSE", "50,TRUE_FALSE", "500,TRUE_FALSE",
            "5,MCQ_SINGLE", "50,MCQ_SINGLE", "500,MCQ_SINGLE"})
    void measureReadCost(int size, QuestionType family) throws Exception {
        var config = admin.queryForMap("SELECT VERSION() version, @@global.transaction_isolation isolation_level, "
                + "@@global.innodb_buffer_pool_size buffer_pool, @@global.binlog_format binlog_format, @@global.binlog_row_image row_image");
        for (int sample = 1; sample <= 3; sample++) {
            if (sample > 1) quizId = newQuiz();
            var revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(family)).toList());
            binding.bind(quizId, revision);
            var started = starts.start(command(revision, AnswerOrder.AUTHORED, "read-start-" + sample));
            var item = delivery.read(started.attemptId()).form().items().get(0);
            var request = answer(started.attemptId(), item, 1, "read-cost-" + started.attemptId());
            for (String operation : List.of("form", "single")) {
                observer().reset(); var before = counters(); long begin = System.nanoTime();
                if (operation.equals("form")) assertThat(delivery.read(started.attemptId()).form().items()).hasSize(size);
                else assertThat(responses.accept(request).replayed()).isFalse();
                double elapsed = (System.nanoTime() - begin) / 1_000_000.0;
                long applicationStatements = observer().executedStatements.get();
                int selects = observer().selects.size(); var after = counters();
                var result = new LinkedHashMap<String, Object>();
                result.put("mode", operation); result.put("family", family.name()); result.put("items", size);
                result.put("sample", sample); result.put("config", config); result.put("elapsed_ms", elapsed);
                result.put("jdbc_statements", applicationStatements); result.put("jdbc_selects", selects);
                before.forEach((key, value) -> {
                    long delta = after.get(key) - value;
                    assertThat(delta).as("Monotonic counter %s", key).isNotNegative(); result.put(key, delta);
                });
                assertThat(((Number) result.get("session_commands")).longValue()).isPositive();
                assertThat(((Number) result.get("isolation_reads")).longValue()).isPositive();
                System.out.println("RESPONSE_READ_COST " + json.writeValueAsString(result));
            }
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response WHERE attempt_id=UUID_TO_BIN(?)",
                    Integer.class, started.attemptId().toString())).isEqualTo(1);
        }
    }

    private Map<String, Long> counters() {
        // Observe application pool threads from a separate admin connection. Isolation SETs,
        // driver/session statements and trigger statements are included; observer SQL is not.
        var totals = admin.queryForMap("""
                SELECT COALESCE(SUM(s.COUNT_STAR),0) statements, COALESCE(SUM(s.SUM_ROWS_SENT),0) rows_sent,
                       COALESCE(SUM(s.SUM_ROWS_EXAMINED),0) rows_examined,
                       COALESCE(SUM(CASE WHEN s.EVENT_NAME IN ('statement/sql/set_option', 'statement/sql/commit',
                           'statement/sql/rollback') THEN s.COUNT_STAR ELSE 0 END),0) session_commands
                FROM performance_schema.events_statements_summary_by_thread_by_event_name s
                JOIN performance_schema.threads t ON t.THREAD_ID=s.THREAD_ID
                WHERE t.PROCESSLIST_DB=?
                """, SCHEMA);
        var result = new LinkedHashMap<String, Long>();
        totals.forEach((key, value) -> result.put(key, ((Number) value).longValue()));
        result.put("isolation_reads", admin.queryForObject("""
                SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest
                WHERE SCHEMA_NAME=? AND UPPER(DIGEST_TEXT) LIKE 'SELECT%TRANSACTION_ISOLATION%'
                """, Long.class, SCHEMA));
        result.put("bytes_sent", admin.queryForObject("""
                SELECT COALESCE(SUM(CAST(s.VARIABLE_VALUE AS UNSIGNED)),0)
                FROM performance_schema.status_by_thread s JOIN performance_schema.threads t ON t.THREAD_ID=s.THREAD_ID
                WHERE t.PROCESSLIST_DB=? AND s.VARIABLE_NAME='Bytes_sent'
                """, Long.class, SCHEMA));
        result.put("redo_bytes", admin.queryForObject("SELECT VARIABLE_VALUE FROM performance_schema.global_status "
                + "WHERE VARIABLE_NAME='Innodb_os_log_written'", Long.class));
        result.put("binlog_bytes", admin.queryForList("SHOW BINARY LOGS").stream()
                .mapToLong(row -> ((Number) row.get("File_size")).longValue()).sum());
        return result;
    }
}
