-- Dark #556. One initial publication per immutable result; its binding is the retained receipt.
-- Notification events/delivery remain #837/#800. No legacy backfill or retention change.
CREATE TABLE attempt_result_release (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    result_id BINARY(16) NOT NULL,
    released_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_release_result (result_id),
    CONSTRAINT fk_release_result FOREIGN KEY (result_id) REFERENCES attempt_automatic_result(id),
    CONSTRAINT fk_release_trace FOREIGN KEY (id) REFERENCES attempt_trace_event(event_id),
    CONSTRAINT chk_release_version CHECK (schema_version=1)
) ENGINE=InnoDB;
-- Preserve this table on application rollback; retained evidence requires a forward fix.
