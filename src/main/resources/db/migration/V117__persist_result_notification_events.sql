-- #837 initial producer: real immediate first release only. No historical backfill or dispatch.
-- Semantic ownership follows the retained result; no contacts, answers, score values or reason text.
CREATE TABLE attempt_result_notification_event (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    event_kind VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id BINARY(16) NOT NULL,
    source_version BIGINT NOT NULL,
    target_id BINARY(16) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    category_mask SMALLINT NOT NULL,
    UNIQUE KEY uq_result_notification_source (event_kind,source_kind,source_id,source_version,target_id),
    CONSTRAINT fk_notification_release FOREIGN KEY (source_id) REFERENCES attempt_result_release(id),
    CONSTRAINT fk_notification_result FOREIGN KEY (target_id) REFERENCES attempt_automatic_result(id),
    CONSTRAINT chk_notification_v1 CHECK (schema_version=1 AND event_kind='RESULT_OPENED'
        AND source_kind='RESULT_RELEASE' AND source_version=1 AND id=source_id AND category_mask IN (1,3))
) ENGINE=InnoDB;
-- Insert-only facts are not deleted on read/ack. #800 owns current recipient/access, delivery and
-- dedup. Retain on application rollback; no cascade, cleanup schedule or retention change.
