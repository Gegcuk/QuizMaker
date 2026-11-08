package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.*;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Reference;
import uk.gegc.quizmaker.features.attempt.domain.repository.AnswerDraftRepository;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.require;

@Repository
public class JdbcAnswerDraftRepository implements AnswerDraftRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAnswerDraftRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean lockSubject(UUID user, UUID attempt) {
        transaction();
        return !jdbc.query("""
                SELECT 1 FROM attempt_participant_subject s FORCE INDEX (ix_attempt_subject_user)
                JOIN users u ON u.user_id=s.user_id
                WHERE s.user_id=UUID_TO_BIN(:user) AND s.attempt_id=UUID_TO_BIN(:attempt)
                  AND s.schema_version=1 AND CAST(s.subject_type AS BINARY)='AUTHENTICATED_USER'
                  AND u.is_active=TRUE AND u.is_deleted=FALSE AND u.username <> 'anonymous' FOR SHARE
                """, Map.of("user", user.toString(), "attempt", attempt.toString()), (r, n) -> true).isEmpty();
    }

    @Override public Scope scope(Reference ref) {
        transaction();
        // One exact item, never the entire bank. Current reads also work inside an older RR snapshot.
        var scopes = jdbc.query("""
                SELECT q.*,b.response_contract_id,b.response_contract_version,b.scoring_contract_id,
                       b.scoring_contract_version,b.binding_hash
                FROM attempt_trace_stream t
                JOIN attempt_form f ON f.id=t.form_id
                JOIN attempt_form_item i ON i.form_id=f.id
                JOIN quiz_revision_items ri ON ri.quiz_revision_id=i.quiz_revision_id AND ri.id=i.source_item_id
                JOIN question_revisions q ON q.id=ri.question_revision_id
                JOIN question_revision_contract_bindings b ON b.question_revision_id=q.id
                WHERE t.attempt_id=UUID_TO_BIN(:attempt) AND t.form_id=UUID_TO_BIN(:form)
                  AND i.id=UUID_TO_BIN(:item) AND q.id=UUID_TO_BIN(:question)
                  AND t.schema_version=1 AND f.schema_version=1
                  AND b.response_contract_id=:contract AND b.response_contract_version=:contractVersion FOR SHARE
                """, parameters(ref), (r, n) -> {
            var binding = new QuestionContractBinding(uuid(r, "id"),
                    new ContractVersion(r.getString("response_contract_id"), r.getInt("response_contract_version")),
                    new ContractVersion(r.getString("scoring_contract_id"), r.getInt("scoring_contract_version")));
            require(binding.fingerprint().equals(r.getString("binding_hash")), CORRUPT);
            return new Scope(question(r, List.of()), binding);
        });
        require(scopes.size() == 1, NOT_FOUND);
        var scope = scopes.get(0);
        var options = jdbc.query("""
                SELECT * FROM question_option_revisions WHERE question_revision_id=UUID_TO_BIN(:question)
                ORDER BY authored_position FOR SHARE
                """, parameters(ref), (r, n) -> new QuestionRevision.Option(uuid(r, "id"),
                r.getString("source_key"), r.getInt("authored_position")));
        var q = scope.question();
        return new Scope(new QuestionRevision(q.id(), q.questionId(), q.schemaVersion(), q.type(), q.difficulty(),
                q.questionText(), q.content(), q.hint(), q.explanation(), q.attachmentUrl(), q.attachmentAssetId(),
                q.createdAt(), options), scope.binding());
    }

    @Override public Optional<AnswerDraft> read(Reference ref) {
        transaction();
        return jdbc.query("""
                SELECT * FROM attempt_answer_draft
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND form_item_id=UUID_TO_BIN(:item) FOR SHARE
                """, parameters(ref), (r, n) -> {
            require(r.getInt("schema_version") == 1 && (r.getInt("deleted") == 0 || r.getInt("deleted") == 1), CORRUPT);
            var saved = new Reference(uuid(r, "attempt_id"), uuid(r, "form_id"), uuid(r, "form_item_id"),
                    uuid(r, "question_revision_id"), new ContractVersion(r.getString("response_contract_id"),
                    r.getInt("response_contract_version")));
            require(ref.equals(saved), CORRUPT);
            return new AnswerDraft(saved, r.getLong("row_version"), r.getBoolean("deleted")
                    ? AnswerDraft.Status.DELETED : AnswerDraft.Status.PRESENT, r.getString("normalized_response"),
                    r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant());
        }).stream().findFirst();
    }

    @Override public boolean accepted(Reference ref) {
        transaction();
        return !jdbc.query("""
                SELECT 1 FROM attempt_accepted_response
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND form_item_id=UUID_TO_BIN(:item) FOR SHARE
                """, parameters(ref), (r, n) -> true).isEmpty();
    }

    @Override public void insert(AnswerDraft draft) {
        transaction();
        jdbc.update("""
                INSERT INTO attempt_answer_draft (attempt_id,form_item_id,schema_version,form_id,question_revision_id,
                    response_contract_id,response_contract_version,row_version,deleted,normalized_response,created_at,updated_at)
                VALUES (UUID_TO_BIN(:attempt),UUID_TO_BIN(:item),1,UUID_TO_BIN(:form),UUID_TO_BIN(:question),
                    :contract,:contractVersion,:version,:deleted,:value,:created,:updated)
                """, parameters(draft));
    }

    @Override public boolean compareAndSet(long expected, AnswerDraft draft) {
        transaction();
        return jdbc.update("""
                UPDATE attempt_answer_draft SET row_version=:version,deleted=:deleted,normalized_response=:value,updated_at=:updated
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND form_item_id=UUID_TO_BIN(:item) AND row_version=:expected
                """, parameters(draft).addValue("expected", expected)) == 1;
    }

    private static MapSqlParameterSource parameters(Reference r) {
        return new MapSqlParameterSource("attempt", r.attemptId().toString()).addValue("form", r.formId().toString())
                .addValue("item", r.formItemId().toString()).addValue("question", r.questionRevisionId().toString())
                .addValue("contract", r.responseContract().id()).addValue("contractVersion", r.responseContract().version());
    }
    private static MapSqlParameterSource parameters(AnswerDraft d) {
        return parameters(d.reference()).addValue("version", d.version()).addValue("deleted", d.status() == AnswerDraft.Status.DELETED)
                .addValue("value", d.normalizedResponse()).addValue("created", Timestamp.from(d.createdAt()))
                .addValue("updated", Timestamp.from(d.updatedAt()));
    }
    private static QuestionRevision question(ResultSet r, List<QuestionRevision.Option> options) throws SQLException {
        return new QuestionRevision(uuid(r, "id"), uuid(r, "question_id"), r.getInt("schema_version"),
                QuestionType.valueOf(r.getString("question_type")), Difficulty.valueOf(r.getString("difficulty")),
                r.getString("question_text"), r.getString("content"), r.getString("hint"), r.getString("explanation"),
                r.getString("attachment_url"), uuid(r, "attachment_asset_id"), r.getTimestamp("created_at").toInstant(), options);
    }
    private static UUID uuid(ResultSet r, String name) throws SQLException {
        byte[] b = r.getBytes(name);
        if (b == null) return null;
        require(b.length == 16, CORRUPT);
        var buffer = java.nio.ByteBuffer.wrap(b);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static void transaction() {
        // No repository transaction interceptor: the owning template sanitizes before rollback logging.
        require(TransactionSynchronizationManager.isActualTransactionActive(), UNAVAILABLE);
    }
}
