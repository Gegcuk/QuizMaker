package uk.gegc.quizmaker.features.quiz.infra;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

@JdbcTest
@ActiveProfiles("test")
@Tag("db-serial")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizOwnerFreshnessMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void additiveCutoverPreservesOldRowsAndEnforcesUniqueConstrainedMetadata() throws Exception {
        String schema = "quizmaker_812_migration";
        try (var connection = dataSource.getConnection()) {
            String original = connection.getCatalog();
            assertThat(original).startsWith("quizmaker_test");
            JdbcTemplate sql = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            sql.execute("CREATE DATABASE " + schema);
            try {
                connection.setCatalog(schema);
                sql.execute("CREATE TABLE quizzes (quiz_id BINARY(16) PRIMARY KEY) ENGINE=InnoDB");
                sql.update("INSERT INTO quizzes VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000001'))");
                var result = Flyway.configure().dataSource(new SingleConnectionDataSource(connection, true))
                        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                        .baselineOnMigrate(true).baselineVersion("77").target("78").load().migrate();
                assertThat(result.migrationsExecuted).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quizzes", Integer.class)).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_owner_freshness", Integer.class)).isZero();
                // An old application may still create a row during rolling replacement.
                sql.update("INSERT INTO quizzes VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000002'))");
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_owner_freshness", Integer.class)).isZero();
                sql.update("INSERT INTO quizzes VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000003'))");
                sql.update("INSERT INTO quiz_owner_freshness (quiz_id) VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000003'))");
                assertThat(sql.queryForObject("SELECT is_new FROM quiz_owner_freshness", Boolean.class)).isTrue();
                assertThatThrownBy(() -> sql.update("INSERT INTO quiz_owner_freshness VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000003'), false)"))
                        .isInstanceOf(DataAccessException.class);
                assertThatThrownBy(() -> sql.update("INSERT INTO quiz_owner_freshness VALUES (UUID_TO_BIN('00000000-0000-0000-0000-000000000004'), true)"))
                        .isInstanceOf(DataAccessException.class);
                assertThatThrownBy(() -> sql.update("UPDATE quiz_owner_freshness SET is_new=NULL"))
                        .isInstanceOf(DataAccessException.class);
                assertThatThrownBy(() -> sql.update("UPDATE quiz_owner_freshness SET is_new=2"))
                        .isInstanceOf(DataAccessException.class);
                sql.update("DELETE FROM quizzes WHERE quiz_id=UUID_TO_BIN('00000000-0000-0000-0000-000000000003')");
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_owner_freshness", Integer.class)).isZero();
            } finally {
                connection.setCatalog(original);
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }
}
