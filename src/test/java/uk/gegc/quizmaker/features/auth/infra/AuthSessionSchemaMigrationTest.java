package uk.gegc.quizmaker.features.auth.infra;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Authentication Session Schema Migration Tests")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthSessionSchemaMigrationTest {

    private static final String MIGRATION_HISTORY_TABLE = "flyway_auth_session_history";
    private static final String BASELINE_MARKER_TABLE = "auth_session_baseline_marker";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean createdUsersTable;

    @BeforeEach
    void resetSchema() {
        dropMigrationArtifacts();
        ensureUsersTableExists();
    }

    @AfterEach
    void cleanUp() {
        dropMigrationArtifacts();
        if (createdUsersTable) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS users");
        }
    }

    @Test
    @DisplayName("V66 creates a revocable session store with indexed expiry and user cleanup")
    void migrate_v66_createsRevocableSessionSchema() {
        jdbcTemplate.execute("CREATE TABLE " + BASELINE_MARKER_TABLE + " (id INT NOT NULL)");

        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("65")
                .baselineDescription("before authentication sessions")
                .target("66")
                .load()
                .migrate();

        assertThat(columnType("refresh_token_hash")).isEqualToIgnoringCase("char(64)");
        assertThat(columnIsNullable("refresh_token_hash")).isFalse();
        assertThat(columnIsNullable("revoked_at")).isTrue();
        assertThat(indexExists("idx_auth_sessions_user_expires_at")).isTrue();
        assertThat(indexExists("idx_auth_sessions_expires_at")).isTrue();
        assertThat(deleteRule("fk_auth_sessions_user")).isEqualTo("CASCADE");
    }

    private void ensureUsersTableExists() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'users'",
                Integer.class
        );
        if (tableCount != null && tableCount > 0) {
            return;
        }

        jdbcTemplate.execute("CREATE TABLE users (user_id BINARY(16) NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        createdUsersTable = true;
    }

    private void dropMigrationArtifacts() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS auth_sessions");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BASELINE_MARKER_TABLE);
    }

    private String columnType(String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'auth_sessions' AND column_name = ?",
                String.class,
                columnName
        );
    }

    private boolean columnIsNullable(String columnName) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'auth_sessions' AND column_name = ?",
                String.class,
                columnName
        );
        return "YES".equals(nullable);
    }

    private boolean indexExists(String indexName) {
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'auth_sessions' AND index_name = ?",
                Integer.class,
                indexName
        );
        return indexCount != null && indexCount > 0;
    }

    private String deleteRule(String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT delete_rule FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND constraint_name = ?",
                String.class,
                constraintName
        );
    }
}
