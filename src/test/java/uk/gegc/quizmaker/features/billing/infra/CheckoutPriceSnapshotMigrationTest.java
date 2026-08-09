package uk.gegc.quizmaker.features.billing.infra;

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

@DisplayName("Checkout price snapshot migration")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutPriceSnapshotMigrationTest {

    private static final String MIGRATION_HISTORY_TABLE = "flyway_checkout_snapshot_history";
    private static final String BASELINE_MARKER_TABLE = "checkout_snapshot_baseline_marker";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        dropMigrationArtifacts();
        jdbcTemplate.execute("""
                CREATE TABLE payments (
                    id INT NOT NULL PRIMARY KEY,
                    pack_id BINARY(16) NULL,
                    session_metadata JSON NULL
                ) ENGINE=InnoDB
                """);
        jdbcTemplate.update(
                "INSERT INTO payments (id, session_metadata) VALUES (?, CAST(? AS JSON))",
                1,
                "{\"priceId\":\"price_pending\"}"
        );
        jdbcTemplate.update(
                "INSERT INTO payments (id, session_metadata) VALUES (?, CAST(? AS JSON))",
                2,
                "{\"primaryPack\":{\"stripePriceId\":\"price_async\"}}"
        );
        jdbcTemplate.update("INSERT INTO payments (id, session_metadata) VALUES (?, CAST(? AS JSON))", 3, "{}");
    }

    @AfterEach
    void cleanUp() {
        dropMigrationArtifacts();
    }

    @Test
    @DisplayName("V67 backfills provable pending and async prices while preserving ambiguous rows")
    void migrateV67BackfillsOnlyProvableStripePrices() {
        jdbcTemplate.execute("CREATE TABLE " + BASELINE_MARKER_TABLE + " (id INT NOT NULL)");

        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("66")
                .baselineDescription("before checkout price snapshots")
                .target("67")
                .load()
                .migrate();

        assertThat(columnType()).isEqualToIgnoringCase("varchar(100)");
        assertThat(columnIsNullable()).isTrue();
        assertThat(snapshotFor(1)).isEqualTo("price_pending");
        assertThat(snapshotFor(2)).isEqualTo("price_async");
        assertThat(snapshotFor(3)).isNull();
    }

    private String columnType() {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'payments' "
                        + "AND column_name = 'stripe_price_id_snapshot'",
                String.class
        );
    }

    private boolean columnIsNullable() {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'payments' "
                        + "AND column_name = 'stripe_price_id_snapshot'",
                String.class
        );
        return "YES".equals(nullable);
    }

    private String snapshotFor(int id) {
        return jdbcTemplate.queryForObject(
                "SELECT stripe_price_id_snapshot FROM payments WHERE id = ?",
                String.class,
                id
        );
    }

    private void dropMigrationArtifacts() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS payments");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BASELINE_MARKER_TABLE);
    }
}
