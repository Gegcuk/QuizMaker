-- Create quiz_moderation_audit table for tracking quiz moderation actions
-- This table tracks all moderation actions (submit, approve, reject, unpublish) with audit trail

CREATE TABLE IF NOT EXISTS quiz_moderation_audit (
    id BINARY(16) NOT NULL,
    quiz_id BINARY(16) NOT NULL,
    moderator_id BINARY(16) NULL,
    action VARCHAR(20) NOT NULL,
    reason TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id VARCHAR(100) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_quiz_moderation_audit_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    CONSTRAINT fk_quiz_moderation_audit_moderator FOREIGN KEY (moderator_id) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- Create indexes for quiz_moderation_audit
CREATE INDEX idx_quiz_moderation_audit_quiz_id ON quiz_moderation_audit(quiz_id);
CREATE INDEX idx_quiz_moderation_audit_moderator_id ON quiz_moderation_audit(moderator_id);
CREATE INDEX idx_quiz_moderation_audit_created_at ON quiz_moderation_audit(created_at);
CREATE INDEX idx_quiz_moderation_audit_action ON quiz_moderation_audit(action);
