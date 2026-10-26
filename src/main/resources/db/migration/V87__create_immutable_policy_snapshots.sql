-- Dark immutable evidence only. No legacy backfill or FK/binding to legacy attempts.
-- TEXT preserves exact canonical bytes; MySQL JSON would normalize the stored representation.
CREATE TABLE attempt_policy_snapshot (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    resolver_version SMALLINT NOT NULL,
    canonical_version SMALLINT NOT NULL,
    hash_algorithm VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_json MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    INDEX ix_policy_snapshot_revision (quiz_revision_id, id),
    CONSTRAINT fk_policy_snapshot_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_policy_snapshot_version CHECK (schema_version = 1 AND resolver_version = 1 AND canonical_version = 1),
    CONSTRAINT chk_policy_snapshot_algorithm CHECK (CAST(hash_algorithm AS BINARY) = CAST('SHA-256' AS BINARY)),
    CONSTRAINT chk_policy_snapshot_hash CHECK (REGEXP_LIKE(policy_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT chk_policy_snapshot_json CHECK (JSON_VALID(canonical_json) AND JSON_TYPE(CAST(canonical_json AS JSON)) = 'OBJECT')
) ENGINE=InnoDB;
