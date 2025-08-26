-- Create document structure extraction jobs table
-- Implementation of Day 7 — REST API + Jobs from the chunk processing improvement plan

CREATE TABLE document_structure_jobs (
    id                          BINARY(16) PRIMARY KEY,
    document_id                 BINARY(16) NOT NULL,
    username                    VARCHAR(50) NOT NULL,
    status                      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    strategy                    VARCHAR(20) NOT NULL DEFAULT 'AI',
    started_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at                TIMESTAMP NULL,
    error_message               TEXT,
    error_code                  VARCHAR(50),
    nodes_extracted             INTEGER DEFAULT 0,
    progress_percentage         DECIMAL(5,2) DEFAULT 0.0,
    current_phase               VARCHAR(100),
    extraction_time_seconds     BIGINT,
    source_version_hash         VARCHAR(64),
    canonical_text_length       INTEGER,
    pre_segmentation_windows    INTEGER,
    outline_nodes_extracted     INTEGER,
    alignment_success_rate      DECIMAL(5,4),
    
    -- Foreign key constraints
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE,
    
    -- Check constraints
    CONSTRAINT chk_document_structure_jobs_status 
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_document_structure_jobs_strategy 
        CHECK (strategy IN ('AI', 'REGEX', 'HYBRID')),
    CONSTRAINT chk_document_structure_jobs_progress 
        CHECK (progress_percentage >= 0.0 AND progress_percentage <= 100.0),
    CONSTRAINT chk_document_structure_jobs_alignment_rate 
        CHECK (alignment_success_rate IS NULL OR (alignment_success_rate >= 0.0 AND alignment_success_rate <= 1.0)),
    CONSTRAINT chk_document_structure_jobs_nodes_extracted 
        CHECK (nodes_extracted >= 0)
);

-- Indexes for performance
CREATE INDEX idx_document_structure_jobs_document_id ON document_structure_jobs (document_id);
CREATE INDEX idx_document_structure_jobs_username ON document_structure_jobs (username);
CREATE INDEX idx_document_structure_jobs_status ON document_structure_jobs (status);
CREATE INDEX idx_document_structure_jobs_started_at ON document_structure_jobs (started_at);
CREATE INDEX idx_document_structure_jobs_completed_at ON document_structure_jobs (completed_at);

-- Composite indexes for common queries
CREATE INDEX idx_document_structure_jobs_username_started_at ON document_structure_jobs (username, started_at DESC);
CREATE INDEX idx_document_structure_jobs_document_id_started_at ON document_structure_jobs (document_id, started_at DESC);
CREATE INDEX idx_document_structure_jobs_status_started_at ON document_structure_jobs (status, started_at);
