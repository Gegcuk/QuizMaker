-- Each key coordinates first insertion or adoption of one immutable question revision.
-- Once set, the option count is the cardinality for exact primary-key reads of its options.
CREATE TABLE quiz_scenario_question_keys (
    question_revision_id BINARY(16) NOT NULL PRIMARY KEY,
    option_count INT NULL,
    CONSTRAINT chk_scenario_question_option_count CHECK (option_count >= 0)
) ENGINE=InnoDB;

INSERT INTO quiz_scenario_question_keys (question_revision_id, option_count)
SELECT sealed.question_revision_id, COUNT(o.id)
FROM (
    SELECT DISTINCT i.question_revision_id
    FROM quiz_scenario_graphs g
    JOIN quiz_revision_items i ON i.quiz_revision_id=g.quiz_revision_id
    WHERE g.entry_node_id IS NOT NULL
) sealed
LEFT JOIN question_option_revisions o ON o.question_revision_id=sealed.question_revision_id
GROUP BY sealed.question_revision_id;
