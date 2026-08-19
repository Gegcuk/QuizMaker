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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Provider usage schema migration")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProviderUsageSchemaMigrationTest {

    private static final String MIGRATION_HISTORY_TABLE = "flyway_provider_usage_history";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpPreV69Schema() {
        dropArtifacts();
        jdbcTemplate.execute("""
                CREATE TABLE quiz_generation_jobs (
                    id BINARY(16) NOT NULL PRIMARY KEY,
                    test_key VARCHAR(32) NOT NULL UNIQUE,
                    status VARCHAR(24) NOT NULL,
                    provider_llm_tokens BIGINT NULL,
                    billing_tariff_version VARCHAR(100) NULL,
                    billing_base_tokens BIGINT NULL,
                    billing_tokens_per_thousand_characters DECIMAL(10,4) NULL,
                    billing_quoted_content_characters BIGINT NULL,
                    billing_quoted_question_type_count INT NULL
                ) ENGINE=InnoDB
                """);
        insertJob("active-legacy", "PROCESSING", false);
        insertJob("active-snapshot", "PENDING", true);
        insertJob("historical", "COMPLETED", false);
    }

    @AfterEach
    void cleanUp() {
        dropArtifacts();
    }

    @Test
    @DisplayName("V69 classifies rollout jobs and enforces one immutable record per provider attempt")
    void migrateV69ClassifiesLegacyJobsAndEnforcesAttemptIdentity() {
        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("68")
                .baselineDescription("before durable provider usage")
                .target("69")
                .load()
                .migrate();

        assertThat(providerUsageState("active-legacy")).isEqualTo("LEGACY_REVIEW");
        assertThat(providerUsageState("active-snapshot")).isEqualTo("INCOMPLETE");
        assertThat(providerUsageState("historical")).isEqualTo("NOT_RECORDED");
        assertThat(columnIsNullable("provider_usage_state")).isFalse();

        String jobId = jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM quiz_generation_jobs WHERE test_key = 'active-snapshot'",
                String.class
        );
        String providerAttemptId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_provider_usage (
                    id, job_id, provider_attempt_id, record_state, provider_llm_tokens, recorded_at
                ) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), 'REPORTED', 125, CURRENT_TIMESTAMP)
                """, jobId, providerAttemptId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO quiz_generation_provider_usage (
                    id, job_id, provider_attempt_id, record_state, provider_llm_tokens, recorded_at
                ) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), 'REPORTED', 125, CURRENT_TIMESTAMP)
                """, jobId, providerAttemptId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V75 preserves terminal rows and enforces provider attempt lifecycle values")
    void migrateV75PreservesRowsAndEnforcesLifecycle() {
        createPreV75UsageTable();
        String jobId = jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM quiz_generation_jobs WHERE test_key = 'active-snapshot'",
                String.class
        );
        insertUsage(jobId, "REPORTED", 125L);
        insertUsage(jobId, "MISSING", null);

        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("74")
                .target("75")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quiz_generation_provider_usage", Long.class)).isEqualTo(2L);
        insertUsage(jobId, "STARTED", null);
        insertUsage(jobId, "FAILED", null);
        assertThatThrownBy(() -> insertUsage(jobId, "STARTED", 1L))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertUsage(jobId, "REPORTED", null))
                .isInstanceOf(DataAccessException.class);
    }

    private void createPreV75UsageTable() {
        jdbcTemplate.execute("""
                CREATE TABLE quiz_generation_provider_usage (
                    id BINARY(16) NOT NULL PRIMARY KEY,
                    job_id BINARY(16) NOT NULL,
                    provider_attempt_id BINARY(16) NOT NULL,
                    record_state VARCHAR(16) NOT NULL,
                    provider_llm_tokens BIGINT NULL,
                    recorded_at TIMESTAMP NOT NULL,
                    CONSTRAINT uq_qgpu_job_attempt UNIQUE (job_id, provider_attempt_id),
                    CONSTRAINT fk_qgpu_job FOREIGN KEY (job_id)
                        REFERENCES quiz_generation_jobs(id) ON DELETE CASCADE,
                    CONSTRAINT chk_qgpu_record CHECK (
                        (record_state = 'REPORTED' AND provider_llm_tokens IS NOT NULL
                            AND provider_llm_tokens >= 0)
                        OR (record_state = 'MISSING' AND provider_llm_tokens IS NULL)
                    ),
                    INDEX idx_qgpu_job_recorded (job_id, recorded_at),
                    INDEX idx_qgpu_state_recorded (record_state, recorded_at)
                ) ENGINE=InnoDB
                """);
    }

    private void insertUsage(String jobId, String state, Long tokens) {
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_provider_usage (
                    id, job_id, provider_attempt_id, record_state, provider_llm_tokens, recorded_at
                ) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(UUID()), ?, ?, CURRENT_TIMESTAMP)
                """, jobId, state, tokens);
    }

    private void insertJob(String testKey, String status, boolean completeTariff) {
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_jobs (
                    id, test_key, status, billing_tariff_version, billing_base_tokens,
                    billing_tokens_per_thousand_characters, billing_quoted_content_characters,
                    billing_quoted_question_type_count
                ) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?)
                """,
                testKey,
                status,
                completeTariff ? "v1-content-length" : null,
                completeTariff ? 3L : null,
                completeTariff ? 0.35 : null,
                completeTariff ? 2_000L : null,
                completeTariff ? 2 : null
        );
    }

    private String providerUsageState(String testKey) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_usage_state FROM quiz_generation_jobs WHERE test_key = ?",
                String.class,
                testKey
        );
    }

    private boolean columnIsNullable(String columnName) {
        return "YES".equals(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'quiz_generation_jobs' "
                        + "AND column_name = ?",
                String.class,
                columnName
        ));
    }

    private void dropArtifacts() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_provider_usage");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_jobs");
    }
}
