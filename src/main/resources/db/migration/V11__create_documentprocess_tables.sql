-- DocumentProcess feature tables
-- Phase 1: documents table for text normalization and storage

CREATE TABLE normalized_documents (
  id BINARY(16) PRIMARY KEY,
  original_name VARCHAR(255),
  mime VARCHAR(100),
  source ENUM('UPLOAD','TEXT') NOT NULL,
  language VARCHAR(32),
  normalized_text LONGTEXT,
  char_count INT,
  status ENUM('PENDING','NORMALIZED','FAILED') NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE INDEX ix_doc_created ON documents(created_at);
