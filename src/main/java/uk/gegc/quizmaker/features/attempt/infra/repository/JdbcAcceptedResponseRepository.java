package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.dao.DataAccessException;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAcceptedResponseRepository implements AcceptedResponseRepository {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAcceptedResponseRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean lockOwnBinding(UUID userId, UUID attemptId) {
        // Do not touch another participant's root, even when that root is busy.
        try { return !jdbc.query("""
                SELECT 1 FROM attempt_participant_subject FORCE INDEX (ix_attempt_subject_user)
                WHERE user_id=UUID_TO_BIN(:user) AND attempt_id=UUID_TO_BIN(:attempt)
                  AND schema_version=1 AND CAST(subject_type AS BINARY)='AUTHENTICATED_USER' FOR SHARE
                """, Map.of("user", userId.toString(), "attempt", attemptId.toString()), (row, n) -> true).isEmpty();
        } catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
    @Override public boolean exists(UUID attemptId, UUID formItemId) {
        try { return !jdbc.query("""
                SELECT 1 FROM attempt_accepted_response
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND form_item_id=UUID_TO_BIN(:item) FOR SHARE
                """, Map.of("attempt", attemptId.toString(), "item", formItemId.toString()), (row, n) -> true).isEmpty();
        } catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
    @Override public void insert(Value v) {
        try { jdbc.update("""
                INSERT INTO attempt_accepted_response (id,schema_version,attempt_id,form_id,form_item_id,
                    question_revision_id,response_contract_id,response_contract_version,normalized_response,command_id,accepted_at)
                VALUES (UUID_TO_BIN(:id),1,UUID_TO_BIN(:attempt),UUID_TO_BIN(:form),UUID_TO_BIN(:item),
                    UUID_TO_BIN(:question),:contract,:version,:response,UUID_TO_BIN(:command),:time)
                """, new MapSqlParameterSource().addValue("id", v.id().toString()).addValue("attempt", v.attemptId().toString())
                .addValue("form", v.formId().toString()).addValue("item", v.formItemId().toString())
                .addValue("question", v.questionRevisionId().toString()).addValue("contract", v.contract().id())
                .addValue("version", v.contract().version()).addValue("response", v.normalizedResponse())
                .addValue("command", v.commandId().toString()).addValue("time", Timestamp.from(v.acceptedAt())));
        } catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }

    @Override public List<Reference> references(UUID attemptId) {
        try {
            // An existing stream is the current-read anchor, including for an empty accepted set.
            // Reading a missing answer range under RR would lock a shared gap across attempts.
            var roots = jdbc.query("""
                    SELECT response_index FROM attempt_trace_stream WHERE attempt_id=UUID_TO_BIN(:attempt) FOR SHARE
                    """, Map.of("attempt", attemptId.toString()), (row, n) -> row.getString("response_index"));
            if (roots.size() != 1) throw corrupt();
            var ids = responseIds(roots.get(0));
            if (ids.isEmpty()) return List.of();
            var found = new java.util.HashSet<UUID>();
            // Drive existing primary-key lookups from the trigger-maintained ID set. No absent
            // range, answer payload, new connection, isolation change or independent transaction.
            var result = jdbc.query("""
                    SELECT BIN_TO_UUID(r.id) id, BIN_TO_UUID(r.form_id) form_id, BIN_TO_UUID(r.form_item_id) item_id,
                           BIN_TO_UUID(r.question_revision_id) question_id, r.response_contract_id, r.response_contract_version
                    FROM JSON_TABLE(:ids, '$[*]' COLUMNS (id CHAR(36) PATH '$')) known
                    STRAIGHT_JOIN attempt_accepted_response r FORCE INDEX (PRIMARY) ON r.id=UUID_TO_BIN(known.id)
                    WHERE r.attempt_id=UUID_TO_BIN(:attempt) ORDER BY r.form_item_id FOR SHARE OF r
                    """, Map.of("attempt", attemptId.toString(), "ids", JSON.writeValueAsString(ids.stream().map(UUID::toString).toList())), (row, n) -> {
                if (!found.add(UUID.fromString(row.getString("id")))) throw corrupt();
                return new Reference(UUID.fromString(row.getString("form_id")), UUID.fromString(row.getString("item_id")),
                        UUID.fromString(row.getString("question_id")), new uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion(
                        row.getString("response_contract_id"), row.getInt("response_contract_version")));
            });
            if (!found.equals(ids)) throw corrupt();
            return result;
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        } catch (java.io.IOException failure) { throw corrupt(); }
    }

    private static java.util.Set<UUID> responseIds(String raw) {
        try {
            var root = JSON.readTree(raw);
            if (!root.isObject() || root.size() != 2 || !root.path("version").isInt() || root.path("version").intValue() != 1
                    || !root.path("ids").isObject()) throw corrupt();
            var result = new java.util.HashSet<UUID>();
            var fields = root.path("ids").fields();
            while (fields.hasNext()) {
                var entry = fields.next(); var id = UUID.fromString(entry.getKey());
                if (!id.toString().equals(entry.getKey()) || !entry.getValue().isInt() || entry.getValue().intValue() != 1
                        || !result.add(id)) throw corrupt();
            }
            return result;
        } catch (java.io.IOException | RuntimeException failure) { throw corrupt(); }
    }

    private static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException corrupt() {
        return new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.CORRUPT);
    }
}
