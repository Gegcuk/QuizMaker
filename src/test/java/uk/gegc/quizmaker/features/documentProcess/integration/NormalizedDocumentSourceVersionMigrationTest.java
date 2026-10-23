package uk.gegc.quizmaker.features.documentProcess.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.*;

/** Applies the actual Flyway migration to a connection-local MySQL fixture, never the application's table. */
@Tag("db-serial")
class NormalizedDocumentSourceVersionMigrationTest {
    @Test
    void additiveVersionPreservesExistingContentAndDefaultsNewRowsToZero() throws Exception {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        var target = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser"));
        try (var connection = target.getConnection()) {
            var fixture = new SingleConnectionDataSource(connection, true);
            var sql = new JdbcTemplate(fixture);
            String history = "flyway_structure_source_version_test";
            try {
                sql.execute("CREATE TEMPORARY TABLE normalized_documents (id INT PRIMARY KEY, normalized_text LONGTEXT, status VARCHAR(20))");
                sql.update("INSERT INTO normalized_documents VALUES (1, 'retained source', 'STRUCTURED')");
                var flyway = Flyway.configure().dataSource(fixture).table(history).locations("classpath:db/migration")
                        .baselineVersion("82").target("83").load();
                // Temporary tables do not make the schema nonempty to Flyway's metadata discovery.
                flyway.baseline();
                flyway.migrate();
                assertThat(sql.queryForObject("SELECT normalized_text FROM normalized_documents WHERE id=1", String.class))
                        .isEqualTo("retained source");
                assertThat(sql.queryForObject("SELECT status FROM normalized_documents WHERE id=1", String.class))
                        .isEqualTo("STRUCTURED");
                assertThat(sql.queryForObject("SELECT source_version FROM normalized_documents WHERE id=1", Long.class)).isZero();
                sql.update("INSERT INTO normalized_documents(id, normalized_text, status) VALUES (2, 'new source', 'NORMALIZED')");
                assertThat(sql.queryForObject("SELECT source_version FROM normalized_documents WHERE id=2", Long.class)).isZero();
                assertThatThrownBy(() -> sql.update("INSERT INTO normalized_documents VALUES (3, 'invalid', 'NORMALIZED', NULL)"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            } finally {
                sql.execute("DROP TEMPORARY TABLE IF EXISTS normalized_documents");
                sql.execute("DROP TABLE IF EXISTS " + history);
            }
        }
    }
}
