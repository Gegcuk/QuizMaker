-- DocumentProcess feature: Phase 2
-- Structure table for AI-generated document structure

CREATE TABLE `document_nodes` (
  `id` BINARY(16) PRIMARY KEY,
  `document_id` BINARY(16) NOT NULL,
  `parent_id` BINARY(16),
  `idx` INT NOT NULL,
  `type` ENUM('BOOK','CHAPTER','SECTION','SUBSECTION','PARAGRAPH','UTTERANCE','OTHER') NOT NULL,
  `title` VARCHAR(512),
  `start_offset` INT NOT NULL,
  `end_offset` INT NOT NULL,
  `depth` SMALLINT NOT NULL,
  `ai_confidence` DECIMAL(4,3),
  `meta_json` JSON,
  CONSTRAINT `fk_document_nodes_document`
    FOREIGN KEY (`document_id`) REFERENCES `normalized_documents`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_document_nodes_parent`
    FOREIGN KEY (`parent_id`) REFERENCES `document_nodes`(`id`) ON DELETE CASCADE,
  KEY `ix_doc_start` (`document_id`, `start_offset`),
  KEY `ix_parent_id` (`parent_id`),
  KEY `ix_document_id` (`document_id`),
  CONSTRAINT `uq_doc_parent_idx` UNIQUE (`document_id`, `parent_id`, `idx`)
) ENGINE=InnoDB;

-- Update documents table to support STRUCTURED status
ALTER TABLE `normalized_documents`
MODIFY COLUMN `status` ENUM('PENDING','NORMALIZED','FAILED','STRUCTURED') NOT NULL;
