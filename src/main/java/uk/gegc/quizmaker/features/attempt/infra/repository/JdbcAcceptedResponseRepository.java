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
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAcceptedResponseRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean lockOwnBinding(UUID userId, UUID attemptId) {
        // Do not touch another participant's root, even when that root is busy.
        return !jdbc.query("""
                SELECT 1 FROM attempt_participant_subject FORCE INDEX (ix_attempt_subject_user)
                WHERE user_id=UUID_TO_BIN(:user) AND attempt_id=UUID_TO_BIN(:attempt)
                  AND schema_version=1 AND CAST(subject_type AS BINARY)='AUTHENTICATED_USER' FOR SHARE
                """, Map.of("user", userId.toString(), "attempt", attemptId.toString()), (row, n) -> true).isEmpty();
    }
    @Override public boolean exists(UUID attemptId, UUID formItemId) {
        return !jdbc.query("""
                SELECT 1 FROM attempt_accepted_response
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND form_item_id=UUID_TO_BIN(:item) FOR SHARE
                """, Map.of("attempt", attemptId.toString(), "item", formItemId.toString()), (row, n) -> true).isEmpty();
    }
    @Override public void insert(Value v) {
        jdbc.update("""
                INSERT INTO attempt_accepted_response (id,schema_version,attempt_id,form_id,form_item_id,
                    question_revision_id,response_contract_id,response_contract_version,normalized_response,command_id,accepted_at)
                VALUES (UUID_TO_BIN(:id),1,UUID_TO_BIN(:attempt),UUID_TO_BIN(:form),UUID_TO_BIN(:item),
                    UUID_TO_BIN(:question),:contract,:version,:response,UUID_TO_BIN(:command),:time)
                """, new MapSqlParameterSource().addValue("id", v.id().toString()).addValue("attempt", v.attemptId().toString())
                .addValue("form", v.formId().toString()).addValue("item", v.formItemId().toString())
                .addValue("question", v.questionRevisionId().toString()).addValue("contract", v.contract().id())
                .addValue("version", v.contract().version()).addValue("response", v.normalizedResponse())
                .addValue("command", v.commandId().toString()).addValue("time", Timestamp.from(v.acceptedAt())));
    }

    @Override public List<Reference> references(UUID attemptId) {
        try {
            return jdbc.query("""
                    SELECT BIN_TO_UUID(form_id) form_id, BIN_TO_UUID(form_item_id) item_id,
                           BIN_TO_UUID(question_revision_id) question_id, response_contract_id, response_contract_version
                    FROM attempt_accepted_response WHERE attempt_id=UUID_TO_BIN(:attempt)
                    ORDER BY form_item_id FOR SHARE
                    """, Map.of("attempt", attemptId.toString()), (row, n) -> new Reference(
                    UUID.fromString(row.getString("form_id")), UUID.fromString(row.getString("item_id")),
                    UUID.fromString(row.getString("question_id")), new uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion(
                    row.getString("response_contract_id"), row.getInt("response_contract_version"))));
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }
}
