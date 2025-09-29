-- Ensure tables have correct column names for Hibernate naming strategy

-- Drop and recreate tables in correct order (child tables first)
DROP TABLE IF EXISTS document_chunks;
DROP TABLE IF EXISTS documents;
CREATE TABLE documents (
    id BINARY(16) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    user_id BINARY(16) NOT NULL,
    title VARCHAR(255) NULL,
    author VARCHAR(255) NULL,
    total_pages INT NULL,
    total_chunks INT NULL,
    processing_error TEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_documents_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- Create indexes for documents
CREATE INDEX idx_documents_user_id ON documents(user_id);
CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_uploaded_at ON documents(uploaded_at);

-- Recreate document_chunks table with correct structure
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
    CONSTRAINT fk_document_chunks_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Create indexes for document_chunks
CREATE INDEX idx_document_chunks_document_id ON document_chunks(document_id);
CREATE INDEX idx_document_chunks_chunk_index ON document_chunks(document_id, chunk_index);
CREATE INDEX idx_document_chunks_created_at ON document_chunks(created_at);
