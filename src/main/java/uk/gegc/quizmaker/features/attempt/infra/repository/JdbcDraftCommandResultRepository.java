package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.DraftCommandResultRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.DraftCommandException.*;

@Repository
public class JdbcDraftCommandResultRepository implements DraftCommandResultRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcDraftCommandResultRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(DraftCommandResult r) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), Reason.UNAVAILABLE);
        jdbc.update("""
                INSERT INTO attempt_draft_command_result (command_id,attempt_id,form_item_id,draft_version,draft_status)
                VALUES (UUID_TO_BIN(:command),UUID_TO_BIN(:attempt),UUID_TO_BIN(:item),:version,:status)
                """, Map.of("command", r.commandId().toString(), "attempt", r.attemptId().toString(),
                "item", r.formItemId().toString(), "version", r.draftVersion(), "status", r.status().name()));
    }

    @Override public Optional<DraftCommandResult> read(UUID commandId) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), Reason.UNAVAILABLE);
        return jdbc.query("""
                SELECT BIN_TO_UUID(command_id) command_id,BIN_TO_UUID(attempt_id) attempt_id,
                       BIN_TO_UUID(form_item_id) form_item_id,draft_version,draft_status
                FROM attempt_draft_command_result WHERE command_id=UUID_TO_BIN(:command) FOR SHARE
                """, Map.of("command", commandId.toString()), (r, n) -> new DraftCommandResult(
                UUID.fromString(r.getString("command_id")), UUID.fromString(r.getString("attempt_id")),
                UUID.fromString(r.getString("form_item_id")), r.getLong("draft_version"),
                AnswerDraft.Status.valueOf(r.getString("draft_status")))).stream().findFirst();
    }
}
