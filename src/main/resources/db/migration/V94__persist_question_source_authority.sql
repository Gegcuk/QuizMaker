-- No attribution from mutable memberships: unverified legacy rows remain unknown.
ALTER TABLE questions ADD COLUMN source_owner_id BINARY(16) NULL;

CREATE TABLE question_source_confirmations (
    question_id BINARY(16) PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    confirmed_by BINARY(16) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    evidence VARCHAR(500) NOT NULL
) ENGINE=InnoDB;
-- No cascading ownership/evidence deletion with authoring associations or accounts.

DELIMITER $$
CREATE TRIGGER questions_source_owner_immutable BEFORE UPDATE ON questions
FOR EACH ROW
BEGIN
    IF OLD.source_owner_id IS NOT NULL AND NOT (OLD.source_owner_id <=> NEW.source_owner_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Question source authority is immutable';
    END IF;
END$$
DELIMITER ;
