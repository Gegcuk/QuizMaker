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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Subscription mutation schema migration")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubscriptionMutationSchemaMigrationTest {

    private static final String MIGRATION_HISTORY_TABLE = "flyway_subscription_mutation_history";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpPreV70Schema() {
        dropArtifacts();
        jdbcTemplate.execute("""
                CREATE TABLE subscription_status (
                    id BINARY(16) NOT NULL PRIMARY KEY,
                    user_id BINARY(16) NOT NULL UNIQUE,
                    subscription_id VARCHAR(255) NULL
                ) ENGINE=InnoDB
                """);
    }

    @AfterEach
    void cleanUp() {
        dropArtifacts();
    }

    @Test
    @DisplayName("V70 stores hashed client keys and enforces durable operation identity")
    void migrateV70CreatesDurableMutationClaims() {
        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("69")
                .baselineDescription("before subscription mutation claims")
                .target("70")
                .load()
                .migrate();

        assertThat(columnType("idempotency_key_hash")).isEqualToIgnoringCase("char(64)");
        assertThat(columnIsNullable("idempotency_key_hash")).isTrue();
        assertThat(columnIsNullable("request_hash")).isFalse();
        assertThat(columnIsNullable("stripe_idempotency_key")).isFalse();
        assertThat(hasConstraint("fk_smo_subscription_status", "FOREIGN KEY")).isTrue();
        assertThat(hasConstraint("uq_smo_user_idempotency_key_hash", "UNIQUE")).isTrue();
        assertThat(hasConstraint("uq_smo_stripe_idempotency_key", "UNIQUE")).isTrue();

        String statusId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        insertStatus(statusId, userId);
        insertOperation(statusId, userId, "a".repeat(64), "qm-sub-mut-first", "price_pro");

        assertThatThrownBy(() -> insertOperation(
                statusId, userId, "a".repeat(64), "qm-sub-mut-second", "price_plus"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("UPDATE subscription_mutation_operations SET state = 'SUCCEEDED'");
        assertThatThrownBy(() -> insertOperation(
                statusId, userId, "b".repeat(64), "qm-sub-mut-first", "price_plus"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertStatus(String statusId, String userId) {
        jdbcTemplate.update("""
                INSERT INTO subscription_status (id, user_id, subscription_id)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'sub_owner')
                """, statusId, userId);
    }

    private void insertOperation(
            String statusId,
            String userId,
            String keyHash,
            String stripeKey,
            String targetPrice
    ) {
        jdbcTemplate.update("""
                INSERT INTO subscription_mutation_operations (
                    id, subscription_status_id, user_id, subscription_id, operation_type,
                    target_price_id, idempotency_key_hash, request_hash, stripe_idempotency_key,
                    state, lease_expires_at, created_at, updated_at, version
                ) VALUES (
                    UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), 'sub_owner', 'UPDATE',
                    ?, ?, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                )
                """, statusId, userId, targetPrice, keyHash, "c".repeat(64), stripeKey);
    }

    private String columnType(String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'subscription_mutation_operations' "
                        + "AND column_name = ?",
                String.class,
                columnName
        );
    }

    private boolean columnIsNullable(String columnName) {
        return "YES".equals(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'subscription_mutation_operations' "
                        + "AND column_name = ?",
                String.class,
                columnName
        ));
    }

    private boolean hasConstraint(String constraintName, String constraintType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'subscription_mutation_operations' "
                        + "AND constraint_name = ? AND constraint_type = ?",
                Integer.class,
                constraintName,
                constraintType
        );
        return count != null && count == 1;
    }

    private void dropArtifacts() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS subscription_mutation_operations");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS subscription_status");
    }
}
