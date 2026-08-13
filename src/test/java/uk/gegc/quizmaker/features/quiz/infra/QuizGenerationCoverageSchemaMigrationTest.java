package uk.gegc.quizmaker.features.quiz.infra;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Quiz generation coverage schema migration")
class QuizGenerationCoverageSchemaMigrationTest {

    private static final String HISTORY_TABLE = "flyway_generation_coverage_history";
    private static final String BASELINE_MARKER = "generation_coverage_baseline_marker";
    private static final String JOB_ID = "c42b5718-5b64-4aef-a82a-96b2cfecf47c";
    private static final String OTHER_JOB_ID = "ee75e863-b020-4608-bf0f-e39101e99784";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        dropArtifacts();
    }

    @AfterEach
    void cleanUp() {
        dropArtifacts();
    }

    @Test
    @DisplayName("V73 atomically creates constrained unique facts with cascading cleanup")
    void migrationCreatesCoverageConstraintsAndCascade() {
        jdbcTemplate.execute("""
                CREATE TABLE quiz_generation_jobs (
                    id BINARY(16) NOT NULL PRIMARY KEY
                ) ENGINE=InnoDB
                """);

        migrateV73();

        assertThat(columnType("quiz_generation_coverage", "job_id"))
                .isEqualToIgnoringCase("binary(16)");
        assertThat(columnType("quiz_generation_type_coverage", "question_type"))
                .isEqualToIgnoringCase("varchar(32)");
        jdbcTemplate.update("INSERT INTO quiz_generation_jobs (id) VALUES (UUID_TO_BIN(?))", JOB_ID);
        jdbcTemplate.update("INSERT INTO quiz_generation_jobs (id) VALUES (UUID_TO_BIN(?))", OTHER_JOB_ID);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertAggregate(JOB_ID, "PARTIAL", 10, 9, 1);
            insertType(JOB_ID, "MCQ_SINGLE", 10, 9, 2);
        })).isInstanceOf(DataAccessException.class);
        assertThat(count("quiz_generation_coverage")).isZero();
        assertThat(count("quiz_generation_type_coverage")).isZero();

        insertAggregate(JOB_ID, "PARTIAL", 10, 9, 1);
        insertType(JOB_ID, "MCQ_SINGLE", 10, 9, 1);

        assertThatThrownBy(() -> insertType(JOB_ID, "MCQ_SINGLE", 10, 9, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertAggregate(OTHER_JOB_ID, "FAILED_THRESHOLD", 10, 9, 2))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("chk_qgc_missing");

        jdbcTemplate.update("DELETE FROM quiz_generation_jobs WHERE id = UUID_TO_BIN(?)", JOB_ID);
        assertThat(count("quiz_generation_coverage")).isZero();
        assertThat(count("quiz_generation_type_coverage")).isZero();
    }

    @Test
    @DisplayName("V73 leaves focused legacy schemas without generation jobs untouched")
    void migrationLeavesUnrelatedFocusedSchemaUntouched() {
        jdbcTemplate.execute("CREATE TABLE " + BASELINE_MARKER + " (id INT NOT NULL)");

        migrateV73();

        assertThat(tableExists("quiz_generation_coverage")).isFalse();
        assertThat(tableExists("quiz_generation_type_coverage")).isFalse();
    }

    private void migrateV73() {
        Flyway.configure()
                .dataSource(dataSource)
                .table(HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("72")
                .baselineDescription("before typed generation coverage")
                .target("73")
                .load()
                .migrate();
    }

    private void insertAggregate(
            String jobId,
            String outcome,
            int requested,
            int accepted,
            int missing
    ) {
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_coverage (
                    job_id, outcome, threshold_percent, requested_count,
                    accepted_count, missing_count, discarded_count, created_at
                ) VALUES (UUID_TO_BIN(?), ?, 80, ?, ?, ?, 0, CURRENT_TIMESTAMP(6))
                """, jobId, outcome, requested, accepted, missing);
    }

    private void insertType(
            String jobId,
            String questionType,
            int requested,
            int accepted,
            int missing
    ) {
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_type_coverage (
                    job_id, question_type, requested_count, accepted_count, missing_count
                ) VALUES (UUID_TO_BIN(?), ?, ?, ?, ?)
                """, jobId, questionType, requested, accepted, missing);
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName
        );
    }

    private int count(String tableName) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return count == null ? 0 : count;
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private void dropArtifacts() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_type_coverage");
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_coverage");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_jobs");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BASELINE_MARKER);
    }
}
