package uk.gegc.quizmaker.features.document.infra;

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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document Schema Compatibility Migration Tests")
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentSchemaCompatibilityMigrationTest {

    private static final String MIGRATION_HISTORY_TABLE = "flyway_document_schema_history";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID documentId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @BeforeEach
    void resetSchema() {
        dropDocumentSchemaAndMigrationHistory();
    }

    @AfterEach
    void removeMigrationArtifacts() {
        dropDocumentSchemaAndMigrationHistory();
    }

    private void dropDocumentSchemaAndMigrationHistory() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS document_chunks");
            jdbcTemplate.execute("DROP TABLE IF EXISTS documents");
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + MIGRATION_HISTORY_TABLE);
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("Migrates legacy camelCase document tables without losing document or chunk data")
    void migrate_legacyCamelCaseSchema_normalizesColumnsAndPreservesData() {
        createLegacySchema();
        insertLegacyRows();

        migrateFromVersion63();

        assertThat(columnNames("documents")).contains(
                "original_filename", "content_type", "file_size", "file_path",
                "uploaded_at", "processed_at", "total_pages", "total_chunks", "processing_error"
        ).doesNotContain(
                "originalFilename", "contentType", "fileSize", "filePath",
                "uploadedAt", "processedAt", "totalPages", "totalChunks", "processingError"
        );
        assertThat(columnNames("document_chunks")).contains(
                "chunk_index", "start_page", "end_page", "word_count", "character_count",
                "created_at", "chapter_title", "section_title", "chapter_number", "section_number", "chunk_type"
        ).doesNotContain(
                "chunkIndex", "startPage", "endPage", "wordCount", "characterCount",
                "createdAt", "chapterTitle", "sectionTitle", "chapterNumber", "sectionNumber", "chunkType"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_type FROM documents WHERE id = UNHEX(REPLACE(?, '-', ''))",
                String.class,
                documentId.toString()
        )).isEqualTo("text/plain");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chunk_type FROM document_chunks WHERE id = UNHEX(REPLACE(?, '-', ''))",
                Integer.class,
                chunkId.toString()
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("Leaves the current snake_case document schema and data unchanged")
    void migrate_currentSnakeCaseSchema_leavesColumnsAndDataUsable() {
        createCurrentSchema();
        insertCurrentRows();

        migrateFromVersion63();

        assertThat(columnNames("documents")).contains("content_type", "original_filename");
        assertThat(columnNames("document_chunks")).contains("chunk_index", "chunk_type");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_type FROM documents WHERE id = UNHEX(REPLACE(?, '-', ''))",
                String.class,
                documentId.toString()
        )).isEqualTo("application/pdf");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chunk_type FROM document_chunks WHERE id = UNHEX(REPLACE(?, '-', ''))",
                Integer.class,
                chunkId.toString()
        )).isEqualTo(3);
    }

    private void migrateFromVersion63() {
        Flyway.configure()
                .dataSource(jdbcTemplate.getDataSource())
                .table(MIGRATION_HISTORY_TABLE)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("63")
                .baselineDescription("legacy document schema")
                .load()
                .migrate();
    }

    private List<String> columnNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                tableName
        );
    }

    private void createLegacySchema() {
        jdbcTemplate.execute("""
                CREATE TABLE documents (
                    id BINARY(16) NOT NULL,
                    originalFilename VARCHAR(255) NOT NULL,
                    contentType VARCHAR(255) NOT NULL,
                    fileSize BIGINT NOT NULL,
                    filePath VARCHAR(500) NOT NULL,
                    status TINYINT NOT NULL,
                    uploadedAt TIMESTAMP NOT NULL,
                    processedAt TIMESTAMP NOT NULL,
                    user_id BINARY(16) NOT NULL,
                    title VARCHAR(255) NULL,
                    author VARCHAR(255) NULL,
                    totalPages INT NULL,
                    totalChunks INT NULL,
                    processingError TEXT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbcTemplate.execute("""
                CREATE TABLE document_chunks (
                    id BINARY(16) NOT NULL,
                    document_id BINARY(16) NOT NULL,
                    chunkIndex INT NOT NULL,
                    title TEXT NOT NULL,
                    content MEDIUMTEXT NOT NULL,
                    startPage INT NOT NULL,
                    endPage INT NOT NULL,
                    wordCount INT NOT NULL,
                    characterCount INT NOT NULL,
                    createdAt TIMESTAMP NOT NULL,
                    chapterTitle VARCHAR(255) NULL,
                    sectionTitle VARCHAR(255) NULL,
                    chapterNumber INT NULL,
                    sectionNumber INT NULL,
                    chunkType VARCHAR(20) NULL,
                    PRIMARY KEY (id),
                    CONSTRAINT fk_document_chunks_document
                        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
                ) ENGINE=InnoDB
                """);
    }

    private void createCurrentSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE documents (
                    id BINARY(16) NOT NULL,
                    original_filename VARCHAR(255) NOT NULL,
                    content_type VARCHAR(255) NOT NULL,
                    file_size BIGINT NOT NULL,
                    file_path VARCHAR(500) NOT NULL,
                    status TINYINT NOT NULL,
                    uploaded_at TIMESTAMP NOT NULL,
                    processed_at TIMESTAMP NOT NULL,
                    user_id BINARY(16) NOT NULL,
                    title VARCHAR(255) NULL,
                    author VARCHAR(255) NULL,
                    total_pages INT NULL,
                    total_chunks INT NULL,
                    processing_error TEXT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbcTemplate.execute("""
                CREATE TABLE document_chunks (
                    id BINARY(16) NOT NULL,
                    document_id BINARY(16) NOT NULL,
                    chunk_index INT NOT NULL,
                    title TEXT NOT NULL,
                    content MEDIUMTEXT NOT NULL,
                    start_page INT NOT NULL,
                    end_page INT NOT NULL,
                    word_count INT NOT NULL,
                    character_count INT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    chapter_title VARCHAR(255) NULL,
                    section_title VARCHAR(255) NULL,
                    chapter_number INT NULL,
                    section_number INT NULL,
                    chunk_type TINYINT NULL,
                    PRIMARY KEY (id),
                    CONSTRAINT fk_document_chunks_document
                        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
                ) ENGINE=InnoDB
                """);
    }

    private void insertLegacyRows() {
        insertDocument(
                "INSERT INTO documents (id, originalFilename, contentType, fileSize, filePath, status, uploadedAt, processedAt, user_id, title, author, totalPages, totalChunks, processingError) "
                        + "VALUES (UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?, ?, ?, ?, UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?, ?)",
                "text/plain"
        );
        jdbcTemplate.update(
                "INSERT INTO document_chunks (id, document_id, chunkIndex, title, content, startPage, endPage, wordCount, characterCount, createdAt, chapterTitle, sectionTitle, chapterNumber, sectionNumber, chunkType) "
                        + "VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chunkId.toString(), documentId.toString(), 2, "Legacy section", "Legacy content", 3, 4,
                15, 99, Timestamp.valueOf(LocalDateTime.of(2026, 8, 4, 12, 0)), "Chapter", "Section", 1, 2, "SECTION"
        );
    }

    private void insertCurrentRows() {
        insertDocument(
                "INSERT INTO documents (id, original_filename, content_type, file_size, file_path, status, uploaded_at, processed_at, user_id, title, author, total_pages, total_chunks, processing_error) "
                        + "VALUES (UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?, ?, ?, ?, UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?, ?)",
                "application/pdf"
        );
        jdbcTemplate.update(
                "INSERT INTO document_chunks (id, document_id, chunk_index, title, content, start_page, end_page, word_count, character_count, created_at, chapter_title, section_title, chapter_number, section_number, chunk_type) "
                        + "VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chunkId.toString(), documentId.toString(), 2, "Current section", "Current content", 3, 4,
                15, 99, Timestamp.valueOf(LocalDateTime.of(2026, 8, 4, 12, 0)), "Chapter", "Section", 1, 2, 3
        );
    }

    private void insertDocument(String sql, String contentType) {
        jdbcTemplate.update(
                sql,
                documentId.toString(),
                "document.txt",
                contentType,
                99L,
                "/tmp/document.txt",
                2,
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 4, 12, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 4, 12, 0)),
                UUID.randomUUID().toString(),
                "Document title",
                "Document author",
                4,
                1,
                null
        );
    }
}
