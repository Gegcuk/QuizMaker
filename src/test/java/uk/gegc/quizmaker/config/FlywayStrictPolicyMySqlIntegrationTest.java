package uk.gegc.quizmaker.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Fail-closed Flyway policy with MySQL")
class FlywayStrictPolicyMySqlIntegrationTest {

    private static final String CHECKSUM_HISTORY = "flyway_policy_checksum_history";
    private static final String ORDER_HISTORY = "flyway_policy_order_history";
    private static final String BASELINE_HISTORY = "flyway_policy_baseline_history";
    private static final String MALFORMED_HISTORY = "flyway_policy_malformed_history";
    private static final String CHECKSUM_PROBE = "flyway_policy_checksum_probe";
    private static final String ORDER_PROBE = "flyway_policy_order_probe";
    private static final String UNMANAGED_PROBE = "flyway_policy_unmanaged_probe";
    private static final String MALFORMED_PROBE = "flyway_policy_malformed_probe";
    private static final String CLEAN_GUARD_SCHEMA = "flyway_policy_clean_guard_unallocated";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void removePolicyFixtures() {
        for (String table : List.of(CHECKSUM_PROBE, ORDER_PROBE, UNMANAGED_PROBE, MALFORMED_PROBE,
                CHECKSUM_HISTORY, ORDER_HISTORY, BASELINE_HISTORY, MALFORMED_HISTORY)) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("rejects checksum drift without rewriting successful history")
    void checksumDriftFailsWithoutHistoryRepair() {
        Flyway original = initializedFlyway(CHECKSUM_HISTORY, "checksum-original");
        assertThat(original.migrate().migrationsExecuted).isEqualTo(1);
        Integer checksum = checksum(CHECKSUM_HISTORY);
        long historyRows = historyRowCount(CHECKSUM_HISTORY);

        Flyway drifted = strictFlyway(CHECKSUM_HISTORY, "checksum-drift");

        assertThatThrownBy(drifted::migrate).isInstanceOf(FlywayException.class);
        assertThat(checksum(CHECKSUM_HISTORY)).isEqualTo(checksum);
        assertThat(successfulVersions(CHECKSUM_HISTORY)).containsExactly("1");
        assertThat(historyRowCount(CHECKSUM_HISTORY)).isEqualTo(historyRows);
    }

    @Test
    @DisplayName("rejects a late lower version without applying it out of order")
    void lateLowerVersionFailsWithoutApplication() {
        Flyway versionTwo = initializedFlyway(ORDER_HISTORY, "order-step-one");
        assertThat(versionTwo.migrate().migrationsExecuted).isEqualTo(1);

        Flyway withLateVersionOne = strictFlyway(ORDER_HISTORY, "order-step-two");

        assertThatThrownBy(withLateVersionOne::migrate).isInstanceOf(FlywayException.class);
        assertThat(successfulVersions(ORDER_HISTORY)).containsExactly("2");
        assertThat(jdbcTemplate.queryForList(
                "SELECT marker FROM " + ORDER_PROBE + " ORDER BY marker", Integer.class))
                .containsExactly(2);
    }

    @Test
    @DisplayName("refuses clean before accessing an unallocated schema")
    void cleanIsDisabled() {
        assertThat(schemaExists(CLEAN_GUARD_SCHEMA)).isFalse();
        Flyway flyway = Flyway.configure()
                .dataSource(Objects.requireNonNull(jdbcTemplate.getDataSource(), "test datasource"))
                .schemas(CLEAN_GUARD_SCHEMA)
                .createSchemas(false)
                .cleanDisabled(true)
                .load();

        assertThatThrownBy(flyway::clean)
                .isInstanceOf(FlywayException.class);
        assertThat(schemaExists(CLEAN_GUARD_SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("rejects a malformed migration filename before creating history")
    void malformedMigrationNameFailsClosed() {
        assertThatThrownBy(() -> strictFlyway(MALFORMED_HISTORY, "malformed-name").migrate())
                .isInstanceOf(FlywayException.class);
        assertThat(tableExists(MALFORMED_HISTORY)).isFalse();
        assertThat(tableExists(MALFORMED_PROBE)).isFalse();
    }

    @Test
    @DisplayName("rejects a nonempty unmanaged schema instead of baselining it")
    void nonEmptySchemaIsNotAutomaticallyBaselined() {
        jdbcTemplate.execute("CREATE TABLE " + UNMANAGED_PROBE + " (id INT NOT NULL PRIMARY KEY)");
        Flyway flyway = strictFlyway(BASELINE_HISTORY, "checksum-original");

        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
        assertThat(tableExists(UNMANAGED_PROBE)).isTrue();
        assertThat(tableExists(BASELINE_HISTORY)).isFalse();
        assertThat(tableExists(CHECKSUM_PROBE)).isFalse();
    }

    private Flyway initializedFlyway(String historyTable, String location) {
        Flyway flyway = strictFlyway(historyTable, location);
        assertThat(flyway.baseline().successfullyBaselined).isTrue();
        return flyway;
    }

    private Flyway strictFlyway(String historyTable, String location) {
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "test datasource");
        return Flyway.configure()
                .dataSource(dataSource)
                .table(historyTable)
                .locations("classpath:db/flyway-policy/" + location)
                .baselineOnMigrate(false)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .outOfOrder(false)
                .load();
    }

    private List<String> successfulVersions(String historyTable) {
        return jdbcTemplate.queryForList("SELECT version FROM " + historyTable
                + " WHERE success = 1 AND type <> 'BASELINE' ORDER BY installed_rank", String.class);
    }

    private Integer checksum(String historyTable) {
        return jdbcTemplate.queryForObject("SELECT checksum FROM " + historyTable
                + " WHERE success = 1 AND type <> 'BASELINE'", Integer.class);
    }

    private long historyRowCount(String historyTable) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + historyTable, Long.class));
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, tableName);
        return count != null && count == 1;
    }

    private boolean schemaExists(String schemaName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, schemaName);
        return count != null && count == 1;
    }
}
