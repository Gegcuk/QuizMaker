-- #531: storage foundation only. No writer, publication pointer, access grant or legacy backfill.
-- Retained snapshots deliberately have no FK/cascade to mutable users, organizations or share links.
CREATE TABLE assignments (
    id BINARY(16) NOT NULL PRIMARY KEY,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE assignment_drafts (
    id BINARY(16) NOT NULL PRIMARY KEY,
    assignment_id BINARY(16) NOT NULL,
    version INT NOT NULL,
    schema_version SMALLINT NOT NULL,
    status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BINARY(16) NOT NULL,
    scope_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id BINARY(16) NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    content_schema_version SMALLINT NOT NULL,
    policy_schema_version SMALLINT NOT NULL,
    preset_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preset_version INT NOT NULL,
    audience_schema_version SMALLINT NOT NULL,
    audience_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    audience_source_version_id BINARY(16) NOT NULL,
    audience_members JSON NOT NULL,
    opens_at DATETIME(6) NULL,
    closes_at DATETIME(6) NULL,
    capacity_limit INT NULL,
    capacity_enforcement VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NULL,
    guest_access BOOLEAN NOT NULL,
    guest_display_name VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    guest_email VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_assignment_draft_number (assignment_id, version),
    INDEX ix_assignment_draft_owner (owner_id, assignment_id),
    INDEX ix_assignment_draft_organization (organization_id, assignment_id),
    INDEX ix_assignment_draft_content (quiz_revision_id),
    CONSTRAINT fk_assignment_draft_identity FOREIGN KEY (assignment_id) REFERENCES assignments(id),
    CONSTRAINT fk_assignment_draft_content FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_assignment_draft_version CHECK (schema_version = 1 AND version > 0),
    CONSTRAINT chk_assignment_draft_status CHECK (BINARY status = 'DRAFT'),
    CONSTRAINT chk_assignment_draft_scope CHECK ((BINARY scope_kind = 'PERSONAL' AND organization_id IS NULL)
        OR (BINARY scope_kind = 'ORGANIZATION' AND organization_id IS NOT NULL)),
    CONSTRAINT chk_assignment_draft_content CHECK (content_schema_version = 1),
    CONSTRAINT chk_assignment_draft_policy CHECK (policy_schema_version = 1 AND preset_version = 1
        AND BINARY preset_id = 'FREE_PRACTICE'),
    CONSTRAINT chk_assignment_draft_audience CHECK (audience_schema_version = 1 AND BINARY audience_kind IN ('ROSTER','GROUP')
        AND JSON_SCHEMA_VALID('{"type":"array","uniqueItems":true,"items":{"type":"string","pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}}', audience_members)),
    CONSTRAINT chk_assignment_draft_window CHECK (opens_at IS NULL OR closes_at IS NULL OR opens_at < closes_at),
    CONSTRAINT chk_assignment_draft_capacity CHECK ((capacity_limit IS NULL AND capacity_enforcement IS NULL)
        OR (capacity_limit IS NOT NULL AND capacity_limit BETWEEN 1 AND 10000
            AND capacity_enforcement IS NOT NULL AND BINARY capacity_enforcement IN ('HARD','SOFT'))),
    CONSTRAINT chk_assignment_draft_guest CHECK (guest_access IN (0,1)
        AND BINARY guest_display_name IN ('NONE','OPTIONAL','REQUIRED')
        AND BINARY guest_email IN ('NONE','OPTIONAL','REQUIRED','REQUIRED_VERIFIED')
        AND (guest_access = 1 OR (BINARY guest_display_name = 'NONE' AND BINARY guest_email = 'NONE')))
) ENGINE=InnoDB;

CREATE TABLE assignment_versions (
    id BINARY(16) NOT NULL PRIMARY KEY,
    assignment_id BINARY(16) NOT NULL,
    version INT NOT NULL,
    schema_version SMALLINT NOT NULL,
    status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BINARY(16) NOT NULL,
    scope_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id BINARY(16) NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    content_schema_version SMALLINT NOT NULL,
    policy_schema_version SMALLINT NOT NULL,
    preset_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preset_version INT NOT NULL,
    audience_schema_version SMALLINT NOT NULL,
    audience_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    audience_source_version_id BINARY(16) NOT NULL,
    audience_members JSON NOT NULL,
    opens_at DATETIME(6) NULL,
    closes_at DATETIME(6) NULL,
    capacity_limit INT NULL,
    capacity_enforcement VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NULL,
    guest_access BOOLEAN NOT NULL,
    guest_display_name VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    guest_email VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_assignment_version_number (assignment_id, version),
    INDEX ix_assignment_version_owner (owner_id, assignment_id),
    INDEX ix_assignment_version_organization (organization_id, assignment_id),
    INDEX ix_assignment_version_content (quiz_revision_id),
    CONSTRAINT fk_assignment_version_identity FOREIGN KEY (assignment_id) REFERENCES assignments(id),
    CONSTRAINT fk_assignment_version_content FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_assignment_version_version CHECK (schema_version = 1 AND version > 0),
    CONSTRAINT chk_assignment_version_status CHECK (BINARY status = 'PUBLISHED'),
    CONSTRAINT chk_assignment_version_scope CHECK ((BINARY scope_kind = 'PERSONAL' AND organization_id IS NULL)
        OR (BINARY scope_kind = 'ORGANIZATION' AND organization_id IS NOT NULL)),
    CONSTRAINT chk_assignment_version_content CHECK (content_schema_version = 1),
    CONSTRAINT chk_assignment_version_policy CHECK (policy_schema_version = 1 AND preset_version = 1
        AND BINARY preset_id = 'FREE_PRACTICE'),
    CONSTRAINT chk_assignment_version_audience CHECK (audience_schema_version = 1 AND BINARY audience_kind IN ('ROSTER','GROUP')
        AND JSON_SCHEMA_VALID('{"type":"array","uniqueItems":true,"items":{"type":"string","pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}}', audience_members)),
    CONSTRAINT chk_assignment_version_window CHECK (opens_at IS NULL OR closes_at IS NULL OR opens_at < closes_at),
    CONSTRAINT chk_assignment_version_capacity CHECK ((capacity_limit IS NULL AND capacity_enforcement IS NULL)
        OR (capacity_limit IS NOT NULL AND capacity_limit BETWEEN 1 AND 10000
            AND capacity_enforcement IS NOT NULL AND BINARY capacity_enforcement IN ('HARD','SOFT'))),
    CONSTRAINT chk_assignment_version_guest CHECK (guest_access IN (0,1)
        AND BINARY guest_display_name IN ('NONE','OPTIONAL','REQUIRED')
        AND BINARY guest_email IN ('NONE','OPTIONAL','REQUIRED','REQUIRED_VERIFIED')
        AND (guest_access = 1 OR (BINARY guest_display_name = 'NONE' AND BINARY guest_email = 'NONE')))
) ENGINE=InnoDB;

-- Published membership/configuration is in the same row, so no late child insert can alter its meaning.
-- No delete is exposed here; future authorized retention/erasure needs a separately reviewed migration.
CREATE TRIGGER assignment_version_no_update BEFORE UPDATE ON assignment_versions
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Published assignment is immutable';

CREATE TRIGGER assignment_version_no_delete BEFORE DELETE ON assignment_versions
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Published assignment is immutable';
