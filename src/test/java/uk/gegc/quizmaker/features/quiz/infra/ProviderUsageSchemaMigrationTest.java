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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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

    @Test
    @DisplayName("V137 preserves legacy facts and allows a previous writer to report a reconciled attempt")
    void migrateV137PreservesHistoryAndOlderTerminalWrites() {
        createPreV75UsageTable();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V75__add_provider_attempt_lifecycle.sql"))
                .execute(jdbcTemplate.getDataSource());
        String jobId = jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM quiz_generation_jobs WHERE test_key = 'active-snapshot'", String.class);
        insertUsage(jobId, "REPORTED", 125L);
        insertUsage(jobId, "MISSING", null);
        insertUsage(jobId, "FAILED", null);
        insertUsage(jobId, "STARTED", null);
        var previousFacts = jdbcTemplate.queryForList("""
                SELECT record_state, provider_llm_tokens, recorded_at
                FROM quiz_generation_provider_usage ORDER BY record_state
                """);

        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("136")
                .target("137")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForList("""
                SELECT record_state, provider_llm_tokens, recorded_at
                FROM quiz_generation_provider_usage ORDER BY record_state
                """)).isEqualTo(previousFacts);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM quiz_generation_provider_usage
                WHERE execution_fence IS NOT NULL OR unknown_at IS NOT NULL OR terminal_at IS NOT NULL
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'quiz_generation_provider_usage'
                    AND index_name = 'idx_qgpu_state_unknown_id'
                """, Long.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForMap("""
                EXPLAIN SELECT id FROM quiz_generation_provider_usage
                FORCE INDEX (idx_qgpu_state_unknown_id)
                WHERE record_state = 'STARTED' AND unknown_at IS NULL
                    AND id > UUID_TO_BIN('00000000-0000-0000-0000-000000000000')
                ORDER BY id LIMIT 50
                """)).containsEntry("key", "idx_qgpu_state_unknown_id").containsEntry("type", "range");

        assertThat(jdbcTemplate.update("""
                UPDATE quiz_generation_provider_usage SET unknown_at = CURRENT_TIMESTAMP(6)
                WHERE record_state = 'STARTED'
                """)).isEqualTo(1);
        // The prior writer updates only the existing provider data after reloading STARTED.
        assertThat(jdbcTemplate.update("""
                UPDATE quiz_generation_provider_usage SET record_state = 'REPORTED', provider_llm_tokens = 17
                WHERE record_state = 'STARTED'
                """)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT provider_llm_tokens FROM quiz_generation_provider_usage
                WHERE record_state = 'REPORTED' AND unknown_at IS NOT NULL
                """, Long.class)).isEqualTo(17L);
    }

    @Test
    @DisplayName("V138 preserves historical start times and records 150 ms across the old rounding boundary")
    void migrateV138PreservesMicrosecondIntervalsAndAllowsBoundedLegacyLookup() {
        createPreV75UsageTable();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V75__add_provider_attempt_lifecycle.sql"))
                .execute(jdbcTemplate.getDataSource());
        String legacyJobId = jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM quiz_generation_jobs WHERE test_key = 'active-legacy'", String.class);
        String jobId = jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM quiz_generation_jobs WHERE test_key = 'active-snapshot'", String.class);
        UUID legacyAttempt = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_provider_usage (id, job_id, provider_attempt_id, record_state, recorded_at)
                VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), 'STARTED', '2026-08-01 12:01:01')
                """, legacyJobId, legacyAttempt.toString());
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("136")
                .target("137")
                .load();
        flyway.migrate();
        var legacyStart = jdbcTemplate.queryForObject("""
                SELECT recorded_at FROM quiz_generation_provider_usage WHERE provider_attempt_id = UUID_TO_BIN(?)
                """, java.time.LocalDateTime.class, legacyAttempt.toString());

        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .target("138")
                .load()
                .migrate();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT recorded_at FROM quiz_generation_provider_usage WHERE provider_attempt_id = UUID_TO_BIN(?)
                """, java.time.LocalDateTime.class, legacyAttempt.toString())).isEqualTo(legacyStart);

        UUID newAttempt = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO quiz_generation_provider_usage (id, job_id, provider_attempt_id, record_state, recorded_at)
                VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), 'STARTED', '2026-08-02 12:01:00.800')
                """, jobId, newAttempt.toString());
        assertThat(jdbcTemplate.update("""
                UPDATE quiz_generation_provider_usage
                SET record_state = 'REPORTED', provider_llm_tokens = 37, terminal_at = '2026-08-02 12:01:00.950'
                WHERE provider_attempt_id = UUID_TO_BIN(?)
                """, newAttempt.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT TIMESTAMPDIFF(MICROSECOND, recorded_at, terminal_at)
                FROM quiz_generation_provider_usage WHERE provider_attempt_id = UUID_TO_BIN(?)
                """, Long.class, newAttempt.toString())).isEqualTo(150_000L);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE quiz_generation_provider_usage
                SET first_conflict_at = '2026-08-02 12:01:01', conflicting_record_state = 'REPORTED',
                    conflicting_provider_llm_tokens = 37
                WHERE provider_attempt_id = UUID_TO_BIN(?)
                """, newAttempt.toString())).isInstanceOf(DataAccessException.class);
        int recorded = jdbcTemplate.update("""
                UPDATE quiz_generation_provider_usage
                SET first_conflict_at = '2026-08-02 12:01:01', conflicting_record_state = 'REPORTED',
                    conflicting_provider_llm_tokens = 99
                WHERE provider_attempt_id = UUID_TO_BIN(?)
                """, newAttempt.toString());
        assertThat(recorded).isEqualTo(1);

        assertThat(jdbcTemplate.queryForList("""
                SELECT BIN_TO_UUID(provider_attempt_id)
                FROM quiz_generation_provider_usage
                WHERE record_state = 'STARTED' AND unknown_at IS NULL AND execution_fence IS NULL
                    AND recorded_at < '2026-08-02 12:01:02'
                ORDER BY recorded_at, id
                LIMIT 50
                """, String.class)).containsExactly(legacyAttempt.toString());
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
