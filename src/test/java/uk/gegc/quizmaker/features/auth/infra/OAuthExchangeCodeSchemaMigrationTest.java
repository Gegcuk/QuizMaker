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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("OAuth exchange-code schema migration")
class OAuthExchangeCodeSchemaMigrationTest {

    private static final String HISTORY_TABLE = "flyway_oauth_exchange_history";
    private static final String BASELINE_MARKER = "oauth_exchange_baseline_marker";

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
    @DisplayName("V76 stores only a hashed, expiring, user-bound single-use code")
    void migrateV76CreatesPrivacyPreservingExchangeSchema() {
        jdbcTemplate.execute("CREATE TABLE " + BASELINE_MARKER + " (id INT NOT NULL)");

        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("75")
                .baselineDescription("before OAuth exchange codes")
                .target("76")
                .load()
                .migrate();

        assertThat(columnType("code_hash")).isEqualToIgnoringCase("char(64)");
        assertThat(columnCollation("code_hash")).isEqualToIgnoringCase("ascii_bin");
        assertThat(columnType("pkce_challenge")).isEqualToIgnoringCase("char(43)");
        assertThat(columnIsNullable("consumed_at")).isTrue();
        assertThat(indexExists("idx_oec_user_id")).isTrue();
        assertThat(indexExists("idx_oec_expires_at")).isTrue();
        assertThat(deleteRule("fk_oec_user")).isEqualTo("CASCADE");
        assertThat(checkConstraints()).contains(
                "chk_oec_pkce_method",
                "chk_oec_expiry",
                "chk_oec_consumed_time"
        );
        assertThat(tableColumns()).doesNotContain(
                "code",
                "code_verifier",
                "access_token",
                "refresh_token",
                "oauth_state"
        );
    }

    private void ensureUsersTableExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'users'",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute("CREATE TABLE users (user_id BINARY(16) NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        createdUsersTable = true;
    }

    private void dropMigrationArtifacts() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS oauth_exchange_codes");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BASELINE_MARKER);
    }

    private String columnType(String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oauth_exchange_codes' AND column_name = ?",
                String.class,
                columnName
        );
    }

    private String columnCollation(String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT collation_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oauth_exchange_codes' AND column_name = ?",
                String.class,
                columnName
        );
    }

    private boolean columnIsNullable(String columnName) {
        return "YES".equals(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oauth_exchange_codes' AND column_name = ?",
                String.class,
                columnName
        ));
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oauth_exchange_codes' AND index_name = ?",
                Integer.class,
                indexName
        );
        return count != null && count > 0;
    }

    private String deleteRule(String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT delete_rule FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND constraint_name = ?",
                String.class,
                constraintName
        );
    }

    private List<String> checkConstraints() {
        return jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oauth_exchange_codes' "
                        + "AND constraint_type = 'CHECK'",
                String.class
        );
    }

    private List<String> tableColumns() {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oauth_exchange_codes'",
                String.class
        );
    }
}
