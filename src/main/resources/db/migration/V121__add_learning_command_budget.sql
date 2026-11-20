-- D33. Operational state only: one expiring balance per learner, no command history.
-- Absent means full. Existing sessions and their durable replay receipts need no backfill.
CREATE TABLE learning_command_budget (
    learner_id BINARY(16) NOT NULL PRIMARY KEY,
    credit_units BIGINT NOT NULL,
    accounted_us BIGINT NOT NULL,
    INDEX ix_learning_budget_age (accounted_us),
    CONSTRAINT fk_learning_budget_user FOREIGN KEY (learner_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_budget_units CHECK (credit_units BETWEEN 0 AND 1200000000)
) ENGINE=InnoDB;
