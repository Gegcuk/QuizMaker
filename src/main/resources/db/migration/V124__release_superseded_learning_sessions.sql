ALTER TABLE learning_session_start
    ADD COLUMN superseded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_learning_start_superseded CHECK (superseded IN (0,1));

-- Reconcile V123 sessions already made stale by a committed public response.
-- Require the actual event that advanced the captured step and its public acknowledgement.
UPDATE learning_cycle_session step_capture
JOIN learning_schedule_cycle cycle_row ON cycle_row.id=step_capture.cycle_id
JOIN learning_session session_row ON session_row.id=step_capture.session_id
    AND session_row.learner_id=cycle_row.learner_id AND session_row.quiz_id=cycle_row.quiz_id
    AND session_row.quiz_revision_id=cycle_row.quiz_revision_id
JOIN learning_session_start session_start ON session_start.session_id=session_row.id
JOIN learning_form_item item ON item.form_id=session_start.form_id
    AND item.question_revision_id=cycle_row.question_revision_id AND item.position>=session_start.reviewed_items
JOIN learning_review_event event_row ON event_row.cycle_id=step_capture.cycle_id
    AND event_row.sequence=CAST(step_capture.captured_sequence AS DECIMAL(20,0))+1
    AND event_row.learner_id=cycle_row.learner_id
JOIN learning_session_response winner ON winner.learner_id=event_row.learner_id
    AND winner.command_id=event_row.command_id AND winner.session_id<>session_start.session_id
JOIN learning_cycle_session member_capture ON member_capture.session_id=session_start.session_id
    AND member_capture.released=0
SET session_start.superseded=TRUE, member_capture.released=TRUE
WHERE step_capture.released=0 AND session_start.review_mode IN ('DUE','EARLY');
