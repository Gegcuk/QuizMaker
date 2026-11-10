-- No counter: committed starts/lifecycle remain authoritative. This row only serializes a scope
-- and freezes its author-authorized limits. Unlimited scopes still require the one-active guard.
CREATE TABLE attempt_allowance_scope (
    user_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    publication_version BIGINT NOT NULL,
    schema_version SMALLINT NOT NULL,
    maximum_attempts BIGINT NULL,
    cooldown_seconds BIGINT NOT NULL,
    cooldown_nanos INT NOT NULL,
    start_ids JSON NOT NULL,
    PRIMARY KEY (user_id, revision_id, publication_version),
    CONSTRAINT fk_allowance_revision FOREIGN KEY (revision_id) REFERENCES quiz_publications(revision_id),
    CONSTRAINT ck_allowance_scope CHECK (schema_version = 1 AND publication_version > 0
        AND (maximum_attempts IS NULL OR maximum_attempts >= 0)
        AND cooldown_seconds >= 0 AND cooldown_nanos BETWEEN 0 AND 999999999
        AND JSON_TYPE(start_ids) = 'ARRAY')
) ENGINE=InnoDB;

-- Durable context identity survives receipt cleanup; no guessed legacy assignment/publication.
CREATE TABLE attempt_start_binding (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    start_event_id BINARY(16) NOT NULL UNIQUE,
    user_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    publication_version BIGINT NOT NULL,
    schema_version SMALLINT NOT NULL,
    INDEX ix_start_binding_scope (user_id, revision_id, publication_version, attempt_id),
    CONSTRAINT fk_start_binding_event FOREIGN KEY (start_event_id) REFERENCES attempt_trace_event(event_id),
    CONSTRAINT fk_start_binding_lifecycle FOREIGN KEY (attempt_id) REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT fk_start_binding_publication FOREIGN KEY (revision_id) REFERENCES quiz_publications(revision_id),
    CONSTRAINT ck_start_binding CHECK (schema_version = 1 AND publication_version > 0)
) ENGINE=InnoDB;

-- Copy only exact committed canonical START metadata, checked against immutable trace/form identity.
-- Older target roots without that evidence remain unclassified and are denied, never counted as zero.
INSERT INTO attempt_start_binding (attempt_id,start_event_id,user_id,revision_id,publication_version,schema_version)
SELECT r.attempt_id,e.event_id,r.user_id,r.target_id,r.expected_version,1
FROM attempt_command_receipt r
JOIN attempt_participant_subject s ON s.attempt_id=r.attempt_id AND s.user_id=r.user_id AND s.schema_version=1
JOIN attempt_trace_stream t ON t.attempt_id=r.attempt_id AND t.schema_version=1
JOIN attempt_trace_event e ON e.attempt_id=r.attempt_id AND e.event_type='ATTEMPT_STARTED' AND e.schema_version=1
JOIN attempt_form f ON f.id=t.form_id AND f.quiz_revision_id=r.target_id
JOIN quiz_publications p ON p.revision_id=r.target_id AND p.quiz_id=r.context_id
WHERE r.schema_version=1 AND r.operation='START' AND r.status='COMMITTED' AND r.expected_version>0;

-- V102's only supported saved attempt-limit contract is UNLIMITED with zero cooldown.
-- Preserve its exact context identities; runtime verification still rejects malformed snapshots.
INSERT INTO attempt_allowance_scope
    (user_id,revision_id,publication_version,schema_version,maximum_attempts,cooldown_seconds,cooldown_nanos,start_ids)
SELECT user_id,revision_id,publication_version,1,NULL,0,0,JSON_ARRAYAGG(BIN_TO_UUID(attempt_id))
FROM attempt_start_binding GROUP BY user_id,revision_id,publication_version;

ALTER TABLE attempt_start_binding ADD CONSTRAINT fk_start_binding_scope
    FOREIGN KEY (user_id,revision_id,publication_version)
    REFERENCES attempt_allowance_scope(user_id,revision_id,publication_version);

-- Immutable upgrade classification. Missing exact receipt context must never become empty history.
CREATE TABLE attempt_unclassified_publication (
    user_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    PRIMARY KEY (user_id,revision_id),
    CONSTRAINT fk_unclassified_publication FOREIGN KEY (revision_id) REFERENCES quiz_publications(revision_id)
) ENGINE=InnoDB;

INSERT INTO attempt_unclassified_publication (user_id,revision_id)
SELECT DISTINCT s.user_id,f.quiz_revision_id FROM attempt_trace_stream t
JOIN attempt_participant_subject s ON s.attempt_id=t.attempt_id
JOIN attempt_form f ON f.id=t.form_id
LEFT JOIN attempt_start_binding b ON b.attempt_id=t.attempt_id
WHERE b.attempt_id IS NULL;
