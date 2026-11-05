package uk.gegc.quizmaker.features.question.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Tag("db-serial")
class QuestionSourceMigrationMySqlIntegrationTest {
    @Test void legacyRowsRemainUnknownAndRecordedAuthorityCannotBeErasedOrTransferred() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String name = "source_migration_" + UUID.randomUUID().toString().replace("-", "");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            var source = new DriverManagerDataSource(base + name + options, user, password);
            var sql = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("93").load().migrate();
            sql.update("""
                    INSERT INTO questions(id,type,difficulty,question,content)
                    VALUES(UNHEX(LPAD('1',32,'0')),'TRUE_FALSE','EASY','Existing author content','{"answer":true}')
                    """);
            var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("94").load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(migration.migrate().migrationsExecuted).isZero();
            assertThat(sql.queryForObject("SELECT source_owner_id FROM questions", byte[].class)).isNull();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_source_confirmations", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT question FROM questions", String.class)).isEqualTo("Existing author content");
            sql.update("UPDATE questions SET source_owner_id=UNHEX(LPAD('2',32,'0'))");
            assertThatThrownBy(() -> sql.update("UPDATE questions SET source_owner_id=NULL")).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> sql.update("UPDATE questions SET source_owner_id=UNHEX(LPAD('3',32,'0'))"))
                    .isInstanceOf(DataAccessException.class);
            sql.update("UPDATE questions SET question='Ordinary edits still work', is_deleted=TRUE");
            assertThat(sql.queryForObject("SELECT HEX(source_owner_id) FROM questions", String.class)).isEqualTo("0".repeat(31) + "2");
        } finally { admin.execute("DROP DATABASE " + name); }
    }
}
