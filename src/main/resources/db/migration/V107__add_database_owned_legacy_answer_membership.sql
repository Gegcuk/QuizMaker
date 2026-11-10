-- All legacy writers on all instances/jobs must remain stopped until V108 and
-- its exact reconciliation succeed. NULL is uninitialized, not an empty attempt.
ALTER TABLE attempts
    ADD COLUMN accepted_answer_projection JSON NULL DEFAULT NULL,
    ADD CONSTRAINT ck_attempt_answer_projection_object CHECK (
        accepted_answer_projection IS NULL OR JSON_TYPE(accepted_answer_projection) = 'OBJECT');
