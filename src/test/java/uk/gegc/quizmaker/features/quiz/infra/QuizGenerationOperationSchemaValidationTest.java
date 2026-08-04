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

import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Quiz Generation Operation Schema Validation Tests")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizGenerationOperationSchemaValidationTest {

    private static final String MIGRATION_HISTORY_TABLE = "flyway_quiz_generation_operation_history";
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
    @DisplayName("V62 CHAR request hash passes Hibernate schema validation")
    void migrate_v62RequestHashColumn_passesHibernateSchemaValidation() {
        migrateV62();

        assertThatCode(this::validateQuizGenerationOperationSchema)
                .doesNotThrowAnyException();
    }

    private void migrateV62() {
        // Flyway only baselines a non-empty schema. The marker ensures this test
        // applies V62 alone instead of replaying all historical migrations.
        jdbcTemplate.execute("CREATE TABLE " + BASELINE_MARKER_TABLE + " (id INT NOT NULL)");

        Flyway.configure()
                .dataSource(dataSource)
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("61")
                .baselineDescription("before quiz generation operations")
                .target("62")
                .load()
                .migrate();
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
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BASELINE_MARKER_TABLE);
    }
}
