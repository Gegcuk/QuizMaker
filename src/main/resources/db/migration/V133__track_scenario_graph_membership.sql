-- Count successful child inserts at their own revision key. This keeps the seal
-- independent of the physical ranges of the child indexes, even at REPEATABLE READ.
CREATE TABLE quiz_scenario_graph_counts (
    quiz_revision_id BINARY(16) NOT NULL PRIMARY KEY,
    inserted_items INT NOT NULL DEFAULT 0,
    inserted_nodes INT NOT NULL DEFAULT 0,
    inserted_edges INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_scenario_graph_counts FOREIGN KEY (quiz_revision_id)
        REFERENCES quiz_scenario_graphs (quiz_revision_id),
    CONSTRAINT chk_scenario_graph_counts CHECK (inserted_items >= 0 AND inserted_nodes >= 0 AND inserted_edges >= 0)
) ENGINE=InnoDB;

-- The existing BEFORE INSERT guards lock the graph header and allow only open graphs.
-- AFTER INSERT only sees successful writes, including individual rows of a JDBC batch.
CREATE TRIGGER scenario_item_count_insert AFTER INSERT ON quiz_revision_items
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM quiz_revisions WHERE id=NEW.quiz_revision_id AND schema_version=4 FOR SHARE) THEN
        UPDATE quiz_scenario_graph_counts SET inserted_items=inserted_items+1
        WHERE quiz_revision_id=NEW.quiz_revision_id;
    END IF;
END;

CREATE TRIGGER scenario_node_count_insert AFTER INSERT ON quiz_scenario_nodes
FOR EACH ROW
    UPDATE quiz_scenario_graph_counts SET inserted_nodes=inserted_nodes+1
    WHERE quiz_revision_id=NEW.quiz_revision_id;

CREATE TRIGGER scenario_edge_count_insert AFTER INSERT ON quiz_scenario_edges
FOR EACH ROW
    UPDATE quiz_scenario_graph_counts SET inserted_edges=inserted_edges+1
    WHERE quiz_revision_id=NEW.quiz_revision_id;

-- Start tracking new headers only after all child triggers are present. Older
-- headers have no count row, so the seal can distinguish them from a new empty graph.
CREATE TRIGGER scenario_graph_count_insert AFTER INSERT ON quiz_scenario_graphs
FOR EACH ROW
    INSERT INTO quiz_scenario_graph_counts (quiz_revision_id) VALUES (NEW.quiz_revision_id);

-- Install the replacement first so no deployment step leaves the seal unguarded.
CREATE TRIGGER scenario_graph_guard_seal BEFORE UPDATE ON quiz_scenario_graphs
FOR EACH ROW
BEGIN
    DECLARE items INT;
    DECLARE nodes INT;
    DECLARE edges INT;

    IF OLD.entry_node_id IS NOT NULL OR NEW.entry_node_id IS NULL
        OR NEW.quiz_revision_id <> OLD.quiz_revision_id OR NEW.schema_version <> OLD.schema_version
        OR NEW.item_count <> OLD.item_count OR NEW.node_count <> OLD.node_count OR NEW.edge_count <> OLD.edge_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid scenario graph seal';
    END IF;

    -- The header's exclusive lock excludes child writers. Read the current point row
    -- so an outer transaction's earlier snapshot cannot hide committed child inserts.
    SELECT inserted_items, inserted_nodes, inserted_edges INTO items, nodes, edges
    FROM quiz_scenario_graph_counts WHERE quiz_revision_id=OLD.quiz_revision_id FOR SHARE;

    IF items IS NULL THEN
        -- An open header predating tracking can still be assembled and sealed.
        -- Such headers are never committed by the transactional publication service.
        SELECT COUNT(*) INTO items FROM quiz_revision_items WHERE quiz_revision_id=OLD.quiz_revision_id FOR SHARE;
        SELECT COUNT(*) INTO nodes FROM quiz_scenario_nodes WHERE quiz_revision_id=OLD.quiz_revision_id FOR SHARE;
        SELECT COUNT(*) INTO edges FROM quiz_scenario_edges WHERE quiz_revision_id=OLD.quiz_revision_id FOR SHARE;
    END IF;

    IF NEW.item_count <> items OR NEW.node_count <> nodes OR NEW.edge_count <> edges THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid scenario graph seal';
    END IF;
END;

DROP TRIGGER scenario_graph_seal;
