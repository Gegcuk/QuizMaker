package uk.gegc.quizmaker.features.quiz.infra;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@JdbcTest
@ActiveProfiles("test")
@Tag("db-serial")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizOwnerFreshnessMigrationTest {
    private static final String QUIZ_TABLE = "test_quizzes_owner_freshness_v78";
    private static final String FRESHNESS_TABLE = "test_quiz_owner_freshness_v78";
    private static final String HISTORY_TABLE = "flyway_owner_freshness_v78_history";
    private static final String MIGRATION = "V78__create_quiz_owner_freshness.sql";

    @Autowired private DataSource dataSource;

    @Test
    void additiveCutoverPreservesOldRowsAndEnforcesUniqueConstrainedMetadata(@TempDir Path migrations) throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).startsWith("quizmaker_test");
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            dropFixtures(sql);
            try {
                sql.execute("CREATE TABLE " + QUIZ_TABLE + " (quiz_id BINARY(16) PRIMARY KEY) ENGINE=InnoDB");
                sql.update("INSERT INTO " + QUIZ_TABLE + " VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000001'))");

                // Run the production script with fixture-only table/constraint names in the existing database.
                // CI grants schema-scoped privileges; creating a separate database is not required.
                String migration = new ClassPathResource("db/migration/" + MIGRATION)
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("quiz_owner_freshness", FRESHNESS_TABLE)
                        .replace("REFERENCES quizzes(", "REFERENCES " + QUIZ_TABLE + "(");
                Files.writeString(migrations.resolve(MIGRATION), migration, StandardCharsets.UTF_8);
                var result = Flyway.configure().dataSource(new SingleConnectionDataSource(connection, true))
                        .createSchemas(false).table(HISTORY_TABLE).locations("filesystem:" + migrations)
                        .baselineOnMigrate(true).baselineVersion("77").target("78").load().migrate();
                assertThat(result.migrationsExecuted).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + QUIZ_TABLE, Integer.class)).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + FRESHNESS_TABLE, Integer.class)).isZero();
                // An old application may still create a row during rolling replacement.
                sql.update("INSERT INTO " + QUIZ_TABLE + " VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000002'))");
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + FRESHNESS_TABLE, Integer.class)).isZero();
                sql.update("INSERT INTO " + QUIZ_TABLE + " VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000003'))");
                sql.update("INSERT INTO " + FRESHNESS_TABLE + " (quiz_id) VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000003'))");
                assertThat(sql.queryForObject("SELECT is_new FROM " + FRESHNESS_TABLE, Boolean.class)).isTrue();
                assertThatThrownBy(() -> sql.update("INSERT INTO " + FRESHNESS_TABLE + " VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000003'), false)"))
                        .isInstanceOf(DataAccessException.class);
                assertThatThrownBy(() -> sql.update("INSERT INTO " + FRESHNESS_TABLE + " VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000004'), true)"))
                        .isInstanceOf(DataAccessException.class);
                assertThatThrownBy(() -> sql.update("UPDATE " + FRESHNESS_TABLE + " SET is_new=NULL"))
                        .isInstanceOf(DataAccessException.class);
                assertThatThrownBy(() -> sql.update("UPDATE " + FRESHNESS_TABLE + " SET is_new=2"))
                        .isInstanceOf(DataAccessException.class);
                sql.update("DELETE FROM " + QUIZ_TABLE + " WHERE quiz_id=UUID_TO_BIN('00000000-0000-0000-0000-000000000003')");
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + FRESHNESS_TABLE, Integer.class)).isZero();
            } finally {
                dropFixtures(sql);
            }
        }
    }

    private void dropFixtures(JdbcTemplate sql) {
        sql.execute("DROP TABLE IF EXISTS " + FRESHNESS_TABLE);
        sql.execute("DROP TABLE IF EXISTS " + HISTORY_TABLE);
        sql.execute("DROP TABLE IF EXISTS " + QUIZ_TABLE);
    }
}
