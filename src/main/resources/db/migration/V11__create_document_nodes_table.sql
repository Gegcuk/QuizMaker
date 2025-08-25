-- Create document_nodes table for document structure processing
-- This is a thin "coordinates of chunks" layer without touching existing document_chunks

CREATE TABLE document_nodes (
    id BINARY(16) PRIMARY KEY,
    document_id BINARY(16) NOT NULL,
    parent_id BINARY(16) NULL,
    level SMALLINT NOT NULL CHECK (level >= 0), -- 0=doc, 1=part, 2=chapter, 3=section, 4=subsection, 5=paragraph
    type VARCHAR(32) NOT NULL, -- enum-like: DOCUMENT|PART|CHAPTER|SECTION|SUBSECTION|PARAGRAPH|OTHER
    title TEXT NULL,
    start_offset INT NOT NULL CHECK (start_offset >= 0),
    end_offset INT NOT NULL CHECK (end_offset >= start_offset),
    start_anchor TEXT NULL,
    end_anchor TEXT NULL,
    ordinal INT NOT NULL CHECK (ordinal >= 0),
    strategy VARCHAR(16) NOT NULL DEFAULT 'AI', -- REGEX|AI|HYBRID
    confidence DECIMAL(3,2) NULL,
    source_version_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- Generated column to normalize NULL parent_id for uniqueness enforcement
    parent_id_norm BINARY(16) GENERATED ALWAYS AS (IF(parent_id IS NULL, UNHEX(REPEAT('00',16)), parent_id)) STORED,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES document_nodes(id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX idx_document_nodes_document_start_offset ON document_nodes(document_id, start_offset);
CREATE INDEX idx_document_nodes_source_version_hash ON document_nodes(source_version_hash);

-- FK lookup index for efficient child fetches
CREATE INDEX idx_document_nodes_parent ON document_nodes(parent_id);

-- Range scan index for overlapping queries (document_id, start_offset, end_offset)
CREATE INDEX idx_document_nodes_range_scan ON document_nodes(document_id, start_offset, end_offset);

-- Type query index for frequent type-based queries
CREATE INDEX idx_document_nodes_doc_type_start ON document_nodes(document_id, type, start_offset);

-- Unique constraint for ordinal scope (sibling ordering within same parent) - uses normalized parent_id
CREATE UNIQUE INDEX uk_document_nodes_parent_ord ON document_nodes(document_id, parent_id_norm, ordinal);
