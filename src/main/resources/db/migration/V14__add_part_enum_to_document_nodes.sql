-- Add PART enum value to document_nodes type column
-- This supports the new simplified structure generation that focuses on major elements only

ALTER TABLE document_nodes 
MODIFY COLUMN type ENUM('PART','BOOK','CHAPTER','SECTION','SUBSECTION','PARAGRAPH','UTTERANCE','OTHER') NOT NULL;
