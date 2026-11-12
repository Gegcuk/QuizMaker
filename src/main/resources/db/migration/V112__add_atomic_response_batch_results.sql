-- Additive acknowledgement projection. Receipt cleanup may remove it, never accepted evidence.
CREATE TABLE attempt_response_batch_result (
    command_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    attempt_id BINARY(16) NOT NULL,
    state_version BIGINT NOT NULL,
    item_count SMALLINT NOT NULL,
    items_json MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    CONSTRAINT fk_response_batch_receipt FOREIGN KEY (command_id)
        REFERENCES attempt_command_receipt(id) ON DELETE CASCADE,
    CONSTRAINT fk_response_batch_attempt FOREIGN KEY (attempt_id)
        REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT chk_response_batch_version CHECK (schema_version=1 AND state_version>0),
    CONSTRAINT chk_response_batch_items CHECK (item_count BETWEEN 1 AND 500
        AND JSON_VALID(items_json) AND JSON_TYPE(items_json)='ARRAY'
        AND JSON_LENGTH(items_json)=item_count AND OCTET_LENGTH(items_json)<=131072)
) ENGINE=InnoDB;
