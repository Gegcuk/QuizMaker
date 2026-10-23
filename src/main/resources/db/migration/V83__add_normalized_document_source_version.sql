-- Bounded optimistic build identity; existing documents begin at version zero.
ALTER TABLE normalized_documents
    ADD COLUMN source_version BIGINT NOT NULL DEFAULT 0;
