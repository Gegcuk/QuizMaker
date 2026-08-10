package uk.gegc.quizmaker.features.quiz.infra;

import org.flywaydb.core.Flyway;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.Map;

import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Quiz Generation Operation Schema Validation Tests")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizGenerationOperationSchemaValidationTest {

    private static final String V62_MIGRATION_HISTORY_TABLE = "flyway_quiz_generation_operation_v62_history";
    private static final String V68_MIGRATION_HISTORY_TABLE = "flyway_quiz_generation_operation_v68_history";
    private static final String BASELINE_MARKER_TABLE = "quiz_generation_operation_baseline_marker";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetSchema() {
        dropSchemaAndMigrationHistory();
    }

    @AfterEach
    void removeMigrationArtifacts() {
        dropSchemaAndMigrationHistory();
    }

    @Test
    @DisplayName("V62 and V68 produce the current operation schema while preserving legacy rows")
    void migrateV62AndV68ProduceCurrentBackwardCompatibleSchema() {
        migrateV62();
        migrateV68();

        jdbcTemplate.update("""
                INSERT INTO quiz_generation_operations (
                    id, user_id, operation_type, idempotency_key, request_hash,
                    canonicalization_version, legacy_key, state, created_at, updated_at, expires_at, version
                ) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), 'TEXT', 'legacy-key', ?,
                    'v1', TRUE, 'CLAIMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 DAY), 0)
                """, "a".repeat(64));

        assertThat(columnType("request_hash")).isEqualToIgnoringCase("char(64)");
        assertThat(columnIsNullable("billing_tariff_version")).isTrue();
        assertThat(columnIsNullable("billing_base_tokens")).isTrue();
        assertThat(columnIsNullable("billing_tokens_per_thousand_characters")).isTrue();
        assertThatCode(this::validateQuizGenerationOperationSchema)
                .doesNotThrowAnyException();
    }

    private void migrateV62() {
        // Flyway only baselines a non-empty schema. The marker ensures this test
        // applies V62 alone instead of replaying all historical migrations.
        jdbcTemplate.execute("CREATE TABLE " + BASELINE_MARKER_TABLE + " (id INT NOT NULL)");

        Flyway.configure()
                .dataSource(dataSource)
                .table(V62_MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("61")
                .baselineDescription("before quiz generation operations")
                .target("62")
                .load()
                .migrate();
    }

    private void migrateV68() {
        // A separate history table baselines the focused V62 schema at V67, so
        // Flyway executes the real V68 resource without requiring unrelated tables.
        Flyway.configure()
                .dataSource(dataSource)
                .table(V68_MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("67")
                .baselineDescription("before operation tariff snapshots")
                .target("68")
                .load()
                .migrate();
    }

    private String columnType(String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'quiz_generation_operations' "
                        + "AND column_name = ?",
                String.class,
                columnName
        );
    }

    private boolean columnIsNullable(String columnName) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'quiz_generation_operations' "
                        + "AND column_name = ?",
                String.class,
                columnName
        );
        return "YES".equals(nullable);
    }

    private void validateQuizGenerationOperationSchema() {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setManagedTypes(PersistenceManagedTypes.of(QuizGenerationOperation.class.getName()));
        factory.setPersistenceProvider(new HibernatePersistenceProvider());
        factory.setJpaPropertyMap(Map.of(
                AvailableSettings.HBM2DDL_AUTO, "validate",
                AvailableSettings.DIALECT, "org.hibernate.dialect.MySQLDialect"
        ));
        factory.afterPropertiesSet();

        EntityManagerFactory entityManagerFactory = factory.getObject();
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
        factory.destroy();
    }

    private void dropSchemaAndMigrationHistory() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS quiz_generation_operations");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + V62_MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + V68_MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BASELINE_MARKER_TABLE);
    }
}
