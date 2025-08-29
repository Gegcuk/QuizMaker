-- Increase anchor column sizes to accommodate longer AI-generated anchors
ALTER TABLE document_nodes 
MODIFY COLUMN start_anchor TEXT NULL,
MODIFY COLUMN end_anchor TEXT NULL;

-- Drop the old indexes since they were limited to 100 characters
DROP INDEX ix_doc_nodes_start_anchor ON document_nodes;
DROP INDEX ix_doc_nodes_end_anchor ON document_nodes;

-- Create new indexes for TEXT columns (MySQL allows indexing on TEXT columns with prefix length)
CREATE INDEX ix_doc_nodes_start_anchor ON document_nodes(start_anchor(255));
CREATE INDEX ix_doc_nodes_end_anchor ON document_nodes(end_anchor(255));
