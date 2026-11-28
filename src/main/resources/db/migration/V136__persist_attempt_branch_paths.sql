-- A trusted internal admission binds a fresh form to an exact scenario revision and participant.
-- Existing fixed forms and publication heads have no branch rows.
CREATE TABLE attempt_branch_path (
    form_id BINARY(16) PRIMARY KEY,
    attempt_id BINARY(16) NOT NULL UNIQUE,
    quiz_revision_id BINARY(16) NOT NULL,
    policy_snapshot_id BINARY(16) NOT NULL,
    current_node_id BINARY(16) NULL,
    form_version BIGINT NOT NULL DEFAULT 0,
    terminal_ready BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_branch_form_revision (form_id, quiz_revision_id),
    CONSTRAINT fk_branch_path_subject FOREIGN KEY (attempt_id) REFERENCES attempt_participant_subject(attempt_id),
    CONSTRAINT fk_branch_path_lifecycle FOREIGN KEY (attempt_id) REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT fk_branch_path_policy FOREIGN KEY (policy_snapshot_id) REFERENCES attempt_policy_snapshot(id),
    CONSTRAINT fk_branch_path_form FOREIGN KEY (form_id,quiz_revision_id) REFERENCES attempt_form(id,quiz_revision_id),
    CONSTRAINT fk_branch_path_graph FOREIGN KEY (quiz_revision_id) REFERENCES quiz_scenario_graphs(quiz_revision_id),
    CONSTRAINT fk_branch_current_node FOREIGN KEY (quiz_revision_id,current_node_id) REFERENCES quiz_scenario_nodes(quiz_revision_id,id),
    CONSTRAINT ck_branch_version CHECK (form_version>=0 AND terminal_ready IN (0,1))
) ENGINE=InnoDB;

CREATE TABLE attempt_branch_decision (
    form_id BINARY(16) NOT NULL,
    decision_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    expected_state_version BIGINT NOT NULL,
    expected_form_version BIGINT NOT NULL,
    source_node_id BINARY(16) NULL,
    target_node_id BINARY(16) NOT NULL,
    accepted_response_id BINARY(16) NULL,
    input_fingerprint BINARY(32) NOT NULL,
    edge_count INT NOT NULL,
    appended_item_id BINARY(16) NULL,
    trace_event_id BINARY(16) NOT NULL,
    PRIMARY KEY (form_id,decision_id),
    UNIQUE KEY uq_branch_step (form_id,expected_form_version),
    UNIQUE KEY uq_branch_step_revision (form_id,decision_id,quiz_revision_id),
    CONSTRAINT fk_branch_decision_path FOREIGN KEY (form_id,quiz_revision_id)
        REFERENCES attempt_branch_path(form_id,quiz_revision_id),
    CONSTRAINT fk_branch_decision_source FOREIGN KEY (quiz_revision_id,source_node_id) REFERENCES quiz_scenario_nodes(quiz_revision_id,id),
    CONSTRAINT fk_branch_decision_target FOREIGN KEY (quiz_revision_id,target_node_id) REFERENCES quiz_scenario_nodes(quiz_revision_id,id),
    CONSTRAINT fk_branch_decision_answer FOREIGN KEY (accepted_response_id) REFERENCES attempt_accepted_response(id),
    CONSTRAINT fk_branch_decision_append FOREIGN KEY (form_id,appended_item_id) REFERENCES attempt_form_item(form_id,id),
    CONSTRAINT fk_branch_decision_trace FOREIGN KEY (trace_event_id) REFERENCES attempt_trace_event(event_id),
    CONSTRAINT ck_branch_edge_count CHECK (edge_count>=0),
    CONSTRAINT ck_branch_expected_versions CHECK (expected_state_version>=0 AND expected_form_version>=0)
) ENGINE=InnoDB;

CREATE TABLE attempt_branch_edge (
    form_id BINARY(16) NOT NULL,
    decision_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    edge_position INT NOT NULL,
    edge_id BINARY(16) NOT NULL,
    PRIMARY KEY (form_id,decision_id,edge_position),
    CONSTRAINT fk_branch_edge_decision FOREIGN KEY (form_id,decision_id,quiz_revision_id)
        REFERENCES attempt_branch_decision(form_id,decision_id,quiz_revision_id),
    CONSTRAINT fk_branch_edge_revision FOREIGN KEY (quiz_revision_id,edge_id) REFERENCES quiz_scenario_edges(quiz_revision_id,id),
    CONSTRAINT ck_branch_edge_position CHECK (edge_position>=0)
) ENGINE=InnoDB;

ALTER TABLE attempt_trace_event DROP CHECK chk_trace_event_type,
    ADD CONSTRAINT chk_trace_event_type CHECK (CAST(event_type AS BINARY) IN
      ('ATTEMPT_STARTED','FORM_CREATED','QUESTION_DELIVERED','ANSWER_SUBMITTED','ANSWER_LOCKED',
       'ATTEMPT_SUBMITTED','AUTOMATIC_GRADE_PRODUCED','RESULT_RELEASED','DRAFT_SAVED','DRAFT_DELETED',
       'ITEM_NAVIGATED','ATTEMPT_EXPIRED','BRANCH_DECIDED'));

-- Block changes to retained evidence. Restrictive foreign keys preserve dependency order
-- for separately authorized erasure of the complete attempt.
CREATE TRIGGER branch_decision_no_update BEFORE UPDATE ON attempt_branch_decision
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable branch evidence';


CREATE TRIGGER branch_edge_no_update BEFORE UPDATE ON attempt_branch_edge
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable branch evidence';
