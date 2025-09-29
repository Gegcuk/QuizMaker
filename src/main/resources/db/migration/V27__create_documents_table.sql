-- Create documents table for file uploads and processing

CREATE TABLE IF NOT EXISTS documents (
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

-- Create indexes
CREATE INDEX idx_documents_user_id ON documents(user_id);
CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_uploaded_at ON documents(uploaded_at);
