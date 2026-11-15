-- #621: additive, internal publication storage. No legacy conversion or role grants.
INSERT IGNORE INTO permissions(permission_name,description,resource,action) VALUES
    ('ASSIGNMENT_CREATE','Create and edit owned assignment drafts','assignment','create'),
    ('ASSIGNMENT_PUBLISH','Publish owned assignment versions','assignment','publish');

ALTER TABLE assignment_drafts ADD COLUMN participant_designations JSON NULL,
    ADD UNIQUE KEY uq_assignment_draft_identity (assignment_id, id);
ALTER TABLE assignment_versions ADD COLUMN participant_designations JSON NULL,
    ADD UNIQUE KEY uq_assignment_version_identity (assignment_id, id);

CREATE TABLE assignment_publication_heads (
    assignment_id BINARY(16) NOT NULL PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    scope_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id BINARY(16) NULL,
    row_version BIGINT NOT NULL,
    draft_id BINARY(16) NULL,
    publication_id BINARY(16) NULL,
    published_draft_id BINARY(16) NULL,
    CONSTRAINT fk_assignment_head_identity FOREIGN KEY (assignment_id) REFERENCES assignments(id),
    CONSTRAINT fk_assignment_head_draft FOREIGN KEY (assignment_id, draft_id) REFERENCES assignment_drafts(assignment_id, id),
    CONSTRAINT fk_assignment_head_publication FOREIGN KEY (assignment_id, publication_id) REFERENCES assignment_versions(assignment_id, id),
    CONSTRAINT fk_assignment_head_published_draft FOREIGN KEY (assignment_id, published_draft_id) REFERENCES assignment_drafts(assignment_id, id),
    CONSTRAINT chk_assignment_head_version CHECK (row_version >= 0),
    CONSTRAINT chk_assignment_head_scope CHECK ((BINARY scope_kind = 'PERSONAL' AND organization_id IS NULL)
        OR (BINARY scope_kind = 'ORGANIZATION' AND organization_id IS NOT NULL)),
    CONSTRAINT chk_assignment_head_publication CHECK ((publication_id IS NULL) = (published_draft_id IS NULL))
) ENGINE=InnoDB;

-- Draft edits append another snapshot; receipts must always replay the original capture.
CREATE TRIGGER assignment_draft_no_update BEFORE UPDATE ON assignment_drafts
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Assignment draft snapshot is immutable';

CREATE TABLE assignment_publication_receipts (
    assignment_id BINARY(16) NOT NULL,
    actor_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    operation VARCHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT NOT NULL,
    draft_id BINARY(16) NULL,
    publication_id BINARY(16) NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (assignment_id, actor_id, command_id),
    CONSTRAINT fk_assignment_receipt_head FOREIGN KEY (assignment_id) REFERENCES assignment_publication_heads(assignment_id),
    CONSTRAINT fk_assignment_receipt_draft FOREIGN KEY (assignment_id, draft_id) REFERENCES assignment_drafts(assignment_id, id),
    CONSTRAINT fk_assignment_receipt_publication FOREIGN KEY (assignment_id, publication_id) REFERENCES assignment_versions(assignment_id, id),
    CONSTRAINT chk_assignment_receipt_result CHECK (result_version > 0 AND
        ((BINARY operation IN ('CREATE','SAVE') AND draft_id IS NOT NULL AND publication_id IS NULL)
        OR (BINARY operation = 'PUBLISH' AND publication_id IS NOT NULL AND draft_id IS NULL)))
) ENGINE=InnoDB;
