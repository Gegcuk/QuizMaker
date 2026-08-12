package uk.gegc.quizmaker.features.document.infra;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Document file-path index migration")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentFilePathIndexMigrationTest {

    private static final String MIGRATION_HISTORY_TABLE = "flyway_document_file_path_index_history";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpPreV71Schema() {
        dropArtifacts();
        jdbcTemplate.execute("""
                CREATE TABLE documents (
                    id BINARY(16) NOT NULL PRIMARY KEY,
                    file_path VARCHAR(500) NOT NULL
                ) ENGINE=InnoDB
                """);
    }

    @AfterEach
    void cleanUp() {
        dropArtifacts();
    }

    @Test
    @DisplayName("V71 adds the indexed authoritative path lookup used by reconciliation")
    void migrateV71AddsDocumentFilePathIndex() {
        migrateV71();

        assertThat(indexCount()).isEqualTo(1);
        assertThat(firstIndexColumn()).isEqualTo("file_path");
    }

    @Test
    @DisplayName("V71 preserves an equivalent index that already exists")
    void migrateV71IsSafeWhenIndexAlreadyExists() {
        jdbcTemplate.execute("CREATE INDEX idx_documents_file_path ON documents (file_path)");

        migrateV71();

        assertThat(indexCount()).isEqualTo(1);
        assertThat(firstIndexColumn()).isEqualTo("file_path");
    }

    @Test
    @DisplayName("V71 fails instead of accepting a same-named index on the wrong column")
    void migrateV71RejectsConflictingIndexDefinition() {
        jdbcTemplate.execute("CREATE INDEX idx_documents_file_path ON documents (id)");

        assertThatThrownBy(this::migrateV71)
                .isInstanceOf(FlywayException.class);
    }

    private void migrateV71() {
        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("70")
                .baselineDescription("before document path index")
                .target("71")
                .load()
                .migrate();
    }

    private int indexCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'documents' "
                        + "AND index_name = 'idx_documents_file_path'",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private String firstIndexColumn() {
        return jdbcTemplate.queryForObject(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'documents' "
                        + "AND index_name = 'idx_documents_file_path' AND seq_in_index = 1",
                String.class
        );
    }

    private void dropArtifacts() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MIGRATION_HISTORY_TABLE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS documents");
    }
}
