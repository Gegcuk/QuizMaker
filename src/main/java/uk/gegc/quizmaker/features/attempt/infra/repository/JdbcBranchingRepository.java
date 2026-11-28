package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.attempt.application.BranchingAttemptService.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FormFingerprint;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.attempt.domain.repository.BranchingRepository;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException.require;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException.Reason.*;

/** All calls participate in the application transaction holding the attempt lifecycle lock. */
@Repository
public class JdbcBranchingRepository implements BranchingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcBranchingRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void bind(Admission a) {
        requireTransaction();
        var p = ids(a.formId(), a.revisionId()).addValue("attempt", bytes(a.attemptId()))
                .addValue("quiz", bytes(a.quizId())).addValue("owner", bytes(a.ownerId()))
                .addValue("policy", bytes(a.policySnapshotId())).addValue("user", bytes(a.participantId()));
        require(jdbc.update("""
                INSERT INTO attempt_form(id,schema_version,quiz_revision_id,item_count)
                SELECT :form,1,:revision,0 FROM attempt_participant_subject u
                JOIN attempt_lifecycle_state l ON l.attempt_id=u.attempt_id
                JOIN quiz_revisions r ON r.id=:revision AND r.quiz_id=:quiz AND r.owner_id=:owner AND r.schema_version=4
                JOIN attempt_policy_snapshot p ON p.id=:policy AND p.quiz_revision_id=:revision
                WHERE u.attempt_id=:attempt AND u.user_id=:user AND u.subject_type='AUTHENTICATED_USER'
                  AND l.activity_kind='GRADED' AND l.execution_state='IN_PROGRESS' AND l.row_version=1
                  AND NOT EXISTS (SELECT 1 FROM attempt_trace_stream t WHERE t.attempt_id=u.attempt_id)
                """, p) == 1, DENIED);
        jdbc.update("""
                INSERT INTO attempt_branch_path(form_id,attempt_id,quiz_revision_id,policy_snapshot_id)
                VALUES (:form,:attempt,:revision,:policy)
                """, p);
    }

    @Override public Head head(UUID attempt, UUID form, UUID revision, UUID policy) {
        requireTransaction();
        var head = jdbc.query("""
                SELECT p.current_node_id,p.form_version,p.terminal_ready,f.item_count,n.node_type,
                       d.target_node_id,d.appended_item_id,d.expected_form_version
                FROM attempt_branch_path p JOIN attempt_form f ON f.id=p.form_id
                LEFT JOIN quiz_scenario_nodes n ON n.quiz_revision_id=p.quiz_revision_id AND n.id=p.current_node_id
                LEFT JOIN attempt_branch_decision d ON d.form_id=p.form_id
                  AND d.expected_form_version=p.form_version-IF(p.terminal_ready,0,1)
                WHERE p.form_id=:form AND p.attempt_id=:attempt AND p.quiz_revision_id=:revision
                  AND p.policy_snapshot_id=:policy
                """, ids(form, revision).addValue("attempt", bytes(attempt)).addValue("policy", bytes(policy)), (r, n) -> {
            UUID node = uuid(r, "current_node_id");
            long version = r.getLong("form_version");
            boolean terminal = r.getBoolean("terminal_ready");
            require(version == r.getInt("item_count"), CORRUPT);
            if (node == null) require(version == 0 && !terminal && uuid(r, "target_node_id") == null, CORRUPT);
            else require(node.equals(uuid(r, "target_node_id")) && (terminal ? "TERMINAL" : "ITEM").equals(r.getString("node_type"))
                    && terminal == (uuid(r, "appended_item_id") == null), CORRUPT);
            return new Head(node, version, terminal);
        }).stream().findFirst().orElseThrow(() -> new BranchingException(DENIED));
        var valid = jdbc.queryForObject("""
                SELECT COUNT(*) FROM attempt_branch_decision d
                JOIN attempt_branch_path p ON p.form_id=d.form_id
                JOIN quiz_scenario_nodes n ON n.quiz_revision_id=d.quiz_revision_id AND n.id=d.target_node_id
                JOIN attempt_trace_event t ON t.event_id=d.trace_event_id AND t.attempt_id=p.attempt_id AND t.form_id=p.form_id
                LEFT JOIN attempt_form_item i ON i.form_id=d.form_id AND i.id=d.appended_item_id
                LEFT JOIN attempt_branch_decision prev ON prev.form_id=d.form_id AND prev.expected_form_version=d.expected_form_version-1
                LEFT JOIN attempt_accepted_response a ON a.id=d.accepted_response_id
                WHERE d.form_id=:form AND d.quiz_revision_id=:revision AND t.event_type='BRANCH_DECIDED'
                  AND t.command_id=d.decision_id AND t.item_id <=> d.appended_item_id
                  AND d.source_node_id <=> prev.target_node_id
                  AND (d.expected_form_version=0 OR prev.appended_item_id IS NOT NULL)
                  AND (d.accepted_response_id IS NULL OR (a.attempt_id=p.attempt_id AND a.form_id=p.form_id AND a.form_item_id=prev.appended_item_id))
                  AND ((n.node_type='ITEM' AND i.source_item_id=n.item_id AND i.position=d.expected_form_version AND i.position<:size)
                    OR (n.node_type='TERMINAL' AND d.appended_item_id IS NULL AND d.expected_form_version=:size AND p.terminal_ready))
                  AND d.edge_count=(SELECT COUNT(*) FROM attempt_branch_edge e WHERE e.form_id=d.form_id AND e.decision_id=d.decision_id)
                """, ids(form, revision).addValue("size", head.formVersion()), Long.class);
        require(valid == head.formVersion() + (head.terminalReady() ? 1 : 0), CORRUPT);
        return head;
    }

    @Override public Optional<Saved> decision(UUID attempt, UUID form, UUID decision) {
        requireTransaction();
        return jdbc.query("""
                SELECT d.*,i.position,i.source_item_id,n.item_id AS target_item,e.event_type,e.command_id
                FROM attempt_branch_decision d JOIN attempt_branch_path p ON p.form_id=d.form_id
                JOIN quiz_scenario_nodes n ON n.quiz_revision_id=d.quiz_revision_id AND n.id=d.target_node_id
                LEFT JOIN attempt_form_item i ON i.form_id=d.form_id AND i.id=d.appended_item_id
                LEFT JOIN attempt_trace_event e ON e.attempt_id=p.attempt_id AND e.event_id=d.trace_event_id AND e.form_id=d.form_id
                WHERE d.form_id=:form AND d.decision_id=:decision AND p.attempt_id=:attempt
                """, new MapSqlParameterSource("form", bytes(form)).addValue("decision", bytes(decision))
                        .addValue("attempt", bytes(attempt)), (r, n) -> {
            var item = uuid(r, "appended_item_id");
            long expected = r.getLong("expected_form_version");
            require("BRANCH_DECIDED".equals(r.getString("event_type")) && decision.equals(uuid(r, "command_id")), CORRUPT);
            require(item == null ? uuid(r, "target_item") == null : r.getInt("position") == expected
                    && uuid(r, "target_item").equals(uuid(r, "source_item_id")), CORRUPT);
            return new Saved(new Command(attempt, decision, r.getLong("expected_state_version"), expected),
                    new Result(item == null ? Outcome.TERMINAL_READY : Outcome.APPENDED, item, item == null ? expected : expected + 1),
                    uuid(r, "target_node_id"), uuid(r, "accepted_response_id"));
        }).stream().findFirst();
    }

    @Override public Optional<Answer> answer(UUID attempt, UUID form, UUID item) {
        requireTransaction();
        return jdbc.query("""
                SELECT id,question_revision_id,response_contract_id,response_contract_version,normalized_response
                FROM attempt_accepted_response WHERE attempt_id=:attempt AND form_id=:form AND form_item_id=:item AND schema_version=1
                """, new MapSqlParameterSource("attempt", bytes(attempt)).addValue("form", bytes(form)).addValue("item", bytes(item)),
                (r, n) -> new Answer(uuid(r, "id"), uuid(r, "question_revision_id"),
                        new ContractVersion(r.getString("response_contract_id"), r.getInt("response_contract_version")),
                        r.getString("normalized_response"))).stream().findFirst();
    }

    @Override public void appendItem(FixedForm prefix, FormOptionOrder order, FixedForm.Item item, FormOptionOrder.Item options) {
        requireTransaction();
        var items = new ArrayList<>(prefix.items()); items.add(item);
        var orders = new ArrayList<>(order.items()); orders.add(options);
        var fingerprint = FormFingerprint.compute(new FixedForm(prefix.id(), 1, prefix.quizRevisionId(), items),
                new FormOptionOrder(prefix.id(), 1, order.policy(), orders));
        var p = ids(prefix.id(), prefix.quizRevisionId()).addValue("item", bytes(item.id()))
                .addValue("source", bytes(item.sourceItemId())).addValue("position", item.position()).addValue("hash", fingerprint.digest());
        jdbc.update("""
                INSERT INTO attempt_form_item(form_id,id,quiz_revision_id,source_item_id,position)
                VALUES(:form,:item,:revision,:source,:position)
                """, p);
        JdbcFormOptionOrderRepository.writeItems(jdbc, prefix.id(), prefix.quizRevisionId(), List.of(options));
        require(jdbc.update("UPDATE attempt_form SET item_count=item_count+1 WHERE id=:form AND item_count=:position", p) == 1, CORRUPT);
        require(jdbc.update("""
                UPDATE attempt_form_option_order SET item_count=item_count+1,form_fingerprint=:hash
                WHERE form_id=:form AND item_count=:position AND fingerprint_version=1
                """, p) == 1, CORRUPT);
    }

    @Override public void record(UUID form, UUID revision, Head before, Saved saved, List<UUID> edges, byte[] fingerprint, UUID trace) {
        requireTransaction();
        var c = saved.command();
        var p = ids(form, revision).addValue("decision", bytes(c.decisionId())).addValue("state", c.expectedStateVersion())
                .addValue("expected", c.expectedFormVersion()).addValue("source", bytes(before.currentNode()))
                .addValue("target", bytes(saved.node())).addValue("answer", bytes(saved.evidenceId()))
                .addValue("edges", edges.size()).addValue("hash", fingerprint).addValue("item", bytes(saved.result().formItemId())).addValue("trace", bytes(trace))
                .addValue("next", saved.result().formVersion()).addValue("terminal", saved.result().outcome() == Outcome.TERMINAL_READY);
        jdbc.update("""
                INSERT INTO attempt_branch_decision(form_id,decision_id,quiz_revision_id,expected_state_version,expected_form_version,
                       source_node_id,target_node_id,accepted_response_id,input_fingerprint,edge_count,appended_item_id,trace_event_id)
                VALUES(:form,:decision,:revision,:state,:expected,:source,:target,:answer,:hash,:edges,:item,:trace)
                """, p);
        var batch = new MapSqlParameterSource[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            batch[i] = new MapSqlParameterSource(p.getValues()).addValue("position", i).addValue("edge", bytes(edges.get(i)));
        }
        if (batch.length > 0) jdbc.batchUpdate("""
                INSERT INTO attempt_branch_edge(form_id,decision_id,quiz_revision_id,edge_position,edge_id)
                VALUES(:form,:decision,:revision,:position,:edge)
                """, batch);
        require(jdbc.update("""
                UPDATE attempt_branch_path SET current_node_id=:target,form_version=:next,terminal_ready=:terminal
                WHERE form_id=:form AND form_version=:expected AND current_node_id <=> :source AND terminal_ready=FALSE
                """, p) == 1, STALE);
    }

    private static void requireTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), UNAVAILABLE);
    }

    private static MapSqlParameterSource ids(UUID form, UUID revision) {
        return new MapSqlParameterSource("form", bytes(form)).addValue("revision", bytes(revision));
    }
    private static byte[] bytes(UUID id) {
        return id == null ? null : ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(ResultSet r, String column) throws SQLException {
        var raw = r.getBytes(column);
        if (raw == null) return null;
        var b = ByteBuffer.wrap(raw); return new UUID(b.getLong(), b.getLong());
    }
}
