-- Create document_chunks table for document processing chunks

CREATE TABLE IF NOT EXISTS document_chunks (
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
    chunk_type VARCHAR(20) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_document_chunks_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Create indexes
CREATE INDEX idx_document_chunks_document_id ON document_chunks(document_id);
CREATE INDEX idx_document_chunks_chunk_index ON document_chunks(document_id, chunk_index);
CREATE INDEX idx_document_chunks_created_at ON document_chunks(created_at);
