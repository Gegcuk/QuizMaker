-- #848: preserve the existing one-final-answer-per-attempt/question invariant.
-- Inspect the complete #608 inventory before rollout. Exact and conflicting duplicates
-- both require reconciliation; never delete answers or recalculate historical results here.
-- MySQL atomic DDL rejects this statement if duplicates exist, retaining all original rows.
ALTER TABLE answers
    ADD CONSTRAINT uq_answers_attempt_question UNIQUE (attempt_id, question_id);
