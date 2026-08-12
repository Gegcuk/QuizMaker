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

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Quiz generation output checkpoint schema migration")
class QuizGenerationOutputCheckpointSchemaMigrationTest {

    private static final String HISTORY_TABLE = "flyway_generation_checkpoint_history";
    private static final String BASELINE_MARKER = "generation_checkpoint_baseline_marker";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dropArtifacts();
    }

    @AfterEach
    void cleanUp() {
        dropArtifacts();
    }

    @Test
    @DisplayName("V72 creates one bounded checkpoint per job and cascades terminal job cleanup")
    void migrationCreatesCheckpointConstraintsAndCascade() {
        jdbcTemplate.execute("""
                CREATE TABLE quiz_generation_jobs (
                    id BINARY(16) NOT NULL PRIMARY KEY
                ) ENGINE=InnoDB
                """);

        migrateV72();

        assertThat(columnType("schema_version")).isEqualToIgnoringCase("smallint");
        assertThat(columnType("payload")).isEqualToIgnoringCase("mediumtext");
        assertThat(columnType("question_count")).isEqualToIgnoringCase("int");
        assertThat(indexExists("idx_qgoc_created_job")).isTrue();

        jdbcTemplate.update("INSERT INTO quiz_generation_jobs (id) VALUES (UUID_TO_BIN(?))", JOB_ID);
        jdbcTemplate.update("INSERT INTO quiz_generation_jobs (id) VALUES (UUID_TO_BIN(?))", OTHER_JOB_ID);
        insertCheckpoint(1);

        assertThatThrownBy(() -> insertCheckpoint(1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO quiz_generation_output_checkpoints (
                    job_id, schema_version, payload, question_count, created_at
                ) VALUES (UUID_TO_BIN(?), 1, '{}', 0, CURRENT_TIMESTAMP(6))
                """, OTHER_JOB_ID))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("chk_qgoc_question_count");

        jdbcTemplate.update("DELETE FROM quiz_generation_jobs WHERE id = UUID_TO_BIN(?)", JOB_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quiz_generation_output_checkpoints", Integer.class)).isZero();
    }

    @Test
    @DisplayName("V72 is a no-op for focused legacy schemas that do not own generation jobs")
    void migrationLeavesUnrelatedFocusedSchemaUntouched() {
        jdbcTemplate.execute("CREATE TABLE " + BASELINE_MARKER + " (id INT NOT NULL)");

        migrateV72();

        assertThat(tableExists("quiz_generation_output_checkpoints")).isFalse();
    }

    private void migrateV72() {
        Flyway.configure()
                .dataSource(dataSource)
                .table(HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("71")
                .baselineDescription("before durable generation output checkpoints")
                .target("72")
                .load()
                .migrate();
    }

    private void insertCheckpoint(int questionCount) {
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_output_checkpoints (
                    job_id, schema_version, payload, question_count, created_at
                ) VALUES (UUID_TO_BIN(?), 1, '{"schemaVersion":1,"chunks":[]}', ?, CURRENT_TIMESTAMP(6))
                """, JOB_ID, questionCount);
    }

    private String columnType(String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'quiz_generation_output_checkpoints' "
                        + "AND column_name = ?",
                String.class,
                columnName
        );
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'quiz_generation_output_checkpoints' "
                        + "AND index_name = ?",
                Integer.class,
                indexName
        );
        return count != null && count > 0;
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
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_output_checkpoints");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_jobs");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BASELINE_MARKER);
    }

    private static final String JOB_ID = "c42b5718-5b64-4aef-a82a-96b2cfecf47c";
    private static final String OTHER_JOB_ID = "ee75e863-b020-4608-bf0f-e39101e99784";
}
