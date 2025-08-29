-- Add anchor columns to document_nodes table for more reliable offset calculation
ALTER TABLE document_nodes 
ADD COLUMN start_anchor VARCHAR(1000) NULL,
ADD COLUMN end_anchor VARCHAR(1000) NULL;

-- Add indexes for anchor-based queries
CREATE INDEX ix_doc_nodes_start_anchor ON document_nodes(start_anchor(100));
CREATE INDEX ix_doc_nodes_end_anchor ON document_nodes(end_anchor(100));
