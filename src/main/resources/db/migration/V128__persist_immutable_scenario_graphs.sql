-- Format 4 marks internal scenario content. Existing formats and rows retain their shape.
ALTER TABLE quiz_revisions
    DROP CHECK chk_quiz_revision_version,
    ADD CONSTRAINT chk_quiz_revision_version CHECK
        ((schema_version IN (1, 4) AND section_count IS NULL AND case_count IS NULL)
         OR (schema_version = 2 AND section_count IS NOT NULL AND section_count > 0 AND case_count IS NULL)
         OR (schema_version = 3 AND section_count IS NOT NULL AND section_count > 0
             AND case_count IS NOT NULL AND case_count > 0));

CREATE TABLE quiz_scenario_nodes (
    quiz_revision_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    authored_position INT NOT NULL,
    node_type VARBINARY(16) NOT NULL,
    item_id BINARY(16) NULL,
    PRIMARY KEY (quiz_revision_id, id),
    UNIQUE KEY uq_scenario_node_position (quiz_revision_id, authored_position),
    CONSTRAINT fk_scenario_node_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT fk_scenario_node_item FOREIGN KEY (quiz_revision_id, item_id)
        REFERENCES quiz_revision_items(quiz_revision_id, id),
    CONSTRAINT chk_scenario_node_shape CHECK (authored_position >= 0 AND
        ((node_type = 'ITEM' AND item_id IS NOT NULL)
         OR (node_type IN ('ROUTING', 'TERMINAL') AND item_id IS NULL)))
) ENGINE=InnoDB;

CREATE TABLE quiz_scenario_edges (
    quiz_revision_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    from_node_id BINARY(16) NOT NULL,
    to_node_id BINARY(16) NOT NULL,
    authored_position INT NOT NULL,
    edge_type VARBINARY(16) NOT NULL,
    condition_version INT NULL,
    condition_payload MEDIUMTEXT CHARACTER SET utf8mb4 NULL,
    PRIMARY KEY (quiz_revision_id, id),
    UNIQUE KEY uq_scenario_edge_position (quiz_revision_id, from_node_id, authored_position),
    CONSTRAINT fk_scenario_edge_source FOREIGN KEY (quiz_revision_id, from_node_id)
        REFERENCES quiz_scenario_nodes(quiz_revision_id, id),
    CONSTRAINT fk_scenario_edge_target FOREIGN KEY (quiz_revision_id, to_node_id)
        REFERENCES quiz_scenario_nodes(quiz_revision_id, id),
    CONSTRAINT chk_scenario_edge_shape CHECK (authored_position >= 0 AND
        ((edge_type = 'CONDITIONAL' AND condition_version IS NOT NULL AND condition_version > 0
            AND condition_payload IS NOT NULL AND CHAR_LENGTH(condition_payload) > 0)
         OR (edge_type = 'DEFAULT' AND condition_version IS NULL AND condition_payload IS NULL)))
) ENGINE=InnoDB;

-- The header exists while its children are assembled. Binding an entry node seals it.
CREATE TABLE quiz_scenario_graphs (
    quiz_revision_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    entry_node_id BINARY(16) NULL,
    item_count INT NOT NULL,
    node_count INT NOT NULL,
    edge_count INT NOT NULL,
    CONSTRAINT fk_scenario_graph_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT fk_scenario_graph_entry FOREIGN KEY (quiz_revision_id, entry_node_id)
        REFERENCES quiz_scenario_nodes(quiz_revision_id, id),
    CONSTRAINT chk_scenario_graph_shape CHECK (schema_version = 1 AND item_count >= 0 AND node_count > 0 AND edge_count >= 0)
) ENGINE=InnoDB;

CREATE TRIGGER scenario_graph_insert_guard BEFORE INSERT ON quiz_scenario_graphs
FOR EACH ROW
BEGIN
    DECLARE format_version SMALLINT;
    SELECT schema_version INTO format_version FROM quiz_revisions WHERE id = NEW.quiz_revision_id FOR SHARE;
    IF format_version IS NULL OR format_version <> 4 OR NEW.entry_node_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid scenario graph insert';
    END IF;
END;

CREATE TRIGGER scenario_graph_seal BEFORE UPDATE ON quiz_scenario_graphs
FOR EACH ROW
BEGIN
    -- The update holds the exact header row exclusively. Child inserts share that row and
    -- the application writes all children in this transaction, before attempting the seal.
    IF OLD.entry_node_id IS NOT NULL OR NEW.entry_node_id IS NULL
        OR NEW.quiz_revision_id <> OLD.quiz_revision_id OR NEW.schema_version <> OLD.schema_version
        OR NEW.item_count <> OLD.item_count OR NEW.node_count <> OLD.node_count OR NEW.edge_count <> OLD.edge_count
        OR NEW.item_count <> (SELECT COUNT(*) FROM quiz_revision_items WHERE quiz_revision_id = NEW.quiz_revision_id)
        OR NEW.node_count <> (SELECT COUNT(*) FROM quiz_scenario_nodes WHERE quiz_revision_id = NEW.quiz_revision_id)
        OR NEW.edge_count <> (SELECT COUNT(*) FROM quiz_scenario_edges WHERE quiz_revision_id = NEW.quiz_revision_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid scenario graph seal';
    END IF;
END;

CREATE TRIGGER scenario_node_insert_guard BEFORE INSERT ON quiz_scenario_nodes
FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM quiz_scenario_graphs
        WHERE quiz_revision_id = NEW.quiz_revision_id AND entry_node_id IS NULL FOR SHARE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid scenario node insert';
    END IF;
END;

CREATE TRIGGER scenario_edge_insert_guard BEFORE INSERT ON quiz_scenario_edges
FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM quiz_scenario_graphs
        WHERE quiz_revision_id = NEW.quiz_revision_id AND entry_node_id IS NULL FOR SHARE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid scenario edge insert';
    END IF;
END;

CREATE TRIGGER scenario_item_insert_guard BEFORE INSERT ON quiz_revision_items
FOR EACH ROW
BEGIN
    DECLARE format_version SMALLINT;
    SELECT schema_version INTO format_version FROM quiz_revisions WHERE id = NEW.quiz_revision_id FOR SHARE;
    IF format_version = 4
        AND NOT EXISTS (SELECT 1 FROM quiz_scenario_graphs
            WHERE quiz_revision_id = NEW.quiz_revision_id AND entry_node_id IS NULL FOR SHARE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario revision membership is sealed';
    END IF;
END;

CREATE TRIGGER scenario_item_no_update BEFORE UPDATE ON quiz_revision_items
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM quiz_revisions WHERE id IN (OLD.quiz_revision_id, NEW.quiz_revision_id) AND schema_version = 4 FOR SHARE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario revision membership is immutable';
    END IF;
END;

CREATE TRIGGER scenario_item_no_delete BEFORE DELETE ON quiz_revision_items
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM quiz_revisions WHERE id = OLD.quiz_revision_id AND schema_version = 4 FOR SHARE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario revision membership is immutable';
    END IF;
END;

CREATE TRIGGER scenario_graph_no_delete BEFORE DELETE ON quiz_scenario_graphs
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario graph is immutable';
CREATE TRIGGER scenario_node_no_update BEFORE UPDATE ON quiz_scenario_nodes
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario node is immutable';
CREATE TRIGGER scenario_node_no_delete BEFORE DELETE ON quiz_scenario_nodes
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario node is immutable';
CREATE TRIGGER scenario_edge_no_update BEFORE UPDATE ON quiz_scenario_edges
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario edge is immutable';
CREATE TRIGGER scenario_edge_no_delete BEFORE DELETE ON quiz_scenario_edges
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario edge is immutable';
