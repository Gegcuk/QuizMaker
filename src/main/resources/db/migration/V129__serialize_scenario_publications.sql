-- The publication key is acquired before writing any revision rows. A committed key points
-- to the sealed graph, including graphs already published with the V128 storage format.
CREATE TABLE quiz_scenario_publication_keys (
    quiz_revision_id BINARY(16) NOT NULL PRIMARY KEY,
    published_revision_id BINARY(16) NULL,
    CONSTRAINT fk_scenario_publication_graph FOREIGN KEY (published_revision_id)
        REFERENCES quiz_scenario_graphs(quiz_revision_id),
    CONSTRAINT chk_scenario_publication_revision CHECK
        (published_revision_id IS NULL OR published_revision_id = quiz_revision_id)
) ENGINE=InnoDB;

INSERT INTO quiz_scenario_publication_keys (quiz_revision_id, published_revision_id)
SELECT quiz_revision_id, quiz_revision_id FROM quiz_scenario_graphs WHERE entry_node_id IS NOT NULL;
