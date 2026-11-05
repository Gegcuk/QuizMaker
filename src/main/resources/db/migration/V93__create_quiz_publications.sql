-- Dark, prospective publication only. No legacy backfill, mutable-row cascades or cleanup.
ALTER TABLE quiz_revisions ADD UNIQUE KEY uq_revision_quiz_identity (quiz_id, id);
ALTER TABLE saved_image_bindings ADD UNIQUE KEY uq_saved_binding_identity (id, asset_id, image_version_id);
CREATE TABLE quiz_publications (
    revision_id BINARY(16) NOT NULL PRIMARY KEY,
    quiz_id BINARY(16) NOT NULL,
    published_by BINARY(16) NOT NULL,
    canonical_version SMALLINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_publication_content (quiz_id, canonical_version, content_hash),
    UNIQUE KEY uq_publication_quiz_revision (quiz_id, revision_id),
    INDEX ix_publication_history (quiz_id, published_at, revision_id),
    CONSTRAINT fk_publication_revision FOREIGN KEY (quiz_id, revision_id) REFERENCES quiz_revisions(quiz_id, id),
    CONSTRAINT ck_publication_format CHECK (canonical_version = 1),
    CONSTRAINT ck_publication_hash CHECK (content_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB;

CREATE TABLE quiz_publication_heads (
    quiz_id BINARY(16) NOT NULL PRIMARY KEY,
    revision_id BINARY(16) NOT NULL,
    publication_version BIGINT NOT NULL,
    CONSTRAINT fk_publication_head FOREIGN KEY (quiz_id, revision_id)
        REFERENCES quiz_publications(quiz_id, revision_id),
    CONSTRAINT ck_publication_head_version CHECK (publication_version > 0)
) ENGINE=InnoDB;

CREATE TABLE quiz_publication_receipts (
    quiz_id BINARY(16) NOT NULL,
    actor_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_id BINARY(16) NOT NULL,
    publication_version BIGINT NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (quiz_id, actor_id, command_id),
    CONSTRAINT fk_publication_receipt FOREIGN KEY (quiz_id, revision_id)
        REFERENCES quiz_publications(quiz_id, revision_id),
    CONSTRAINT ck_publication_receipt_version CHECK (publication_version > 0),
    CONSTRAINT ck_publication_request_hash CHECK (request_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB;

CREATE TABLE quiz_revision_images (
    quiz_revision_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    asset_id BINARY(16) NOT NULL,
    binding_id BINARY(16) NOT NULL,
    image_version_id BINARY(16) NOT NULL,
    PRIMARY KEY (quiz_revision_id, question_revision_id, asset_id),
    CONSTRAINT fk_publication_image_quiz FOREIGN KEY (quiz_revision_id) REFERENCES quiz_publications(revision_id),
    CONSTRAINT fk_publication_image_question FOREIGN KEY (question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT fk_publication_image_binding FOREIGN KEY (binding_id, asset_id, image_version_id)
        REFERENCES saved_image_bindings(id, asset_id, image_version_id),
    CONSTRAINT fk_publication_image_version FOREIGN KEY (image_version_id, asset_id) REFERENCES saved_image_versions(id, asset_id)
) ENGINE=InnoDB;
