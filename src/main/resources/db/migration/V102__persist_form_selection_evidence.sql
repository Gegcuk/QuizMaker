-- Additive dark selection metadata. Existing forms retain explicitly absent evidence; no backfill.
-- Exact selected IDs and order remain authoritative in attempt_form / attempt_form_item.
CREATE TABLE attempt_form_selection (
    form_id BINARY(16) NOT NULL PRIMARY KEY,
    evidence_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    CONSTRAINT fk_form_selection_form FOREIGN KEY (form_id) REFERENCES attempt_form(id),
    CONSTRAINT chk_form_selection_json CHECK (JSON_VALID(evidence_json))
) ENGINE=InnoDB;
