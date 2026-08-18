-- New normalized documents are private to their authenticated uploader.
-- Existing rows remain unowned and are deliberately inaccessible until removed
-- or assigned by a separately approved, evidence-based operator process.
ALTER TABLE normalized_documents
    ADD COLUMN owner_id BINARY(16) NULL;

CREATE INDEX ix_normalized_documents_owner_id
    ON normalized_documents(owner_id);

ALTER TABLE normalized_documents
    ADD CONSTRAINT fk_normalized_documents_owner
        FOREIGN KEY (owner_id) REFERENCES users(user_id)
        ON DELETE SET NULL;
