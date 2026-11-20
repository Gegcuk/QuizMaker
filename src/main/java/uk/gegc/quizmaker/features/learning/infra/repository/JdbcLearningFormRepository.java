package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningFormRepository;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningFormRepository implements LearningFormRepository {
    private final NamedParameterJdbcTemplate sql;
    public JdbcLearningFormRepository(NamedParameterJdbcTemplate sql) { this.sql = sql; }

    @Override public void lockSession(LearningSession session) {
        safe(() -> {
            require(sql.query("""
                SELECT id FROM learning_session WHERE id=UUID_TO_BIN(:session)
                AND learner_id=UUID_TO_BIN(:learner) AND quiz_revision_id=UUID_TO_BIN(:revision) FOR UPDATE
                """, parameters(session), (r, n) -> r.getBytes(1)).size() == 1, UNAVAILABLE);
            return null;
        });
    }

    @Override public Optional<LearningForm> find(LearningSession session) {
        return safe(() -> {
            var headers = sql.query("""
                SELECT BIN_TO_UUID(id) id,schema_version,outcome,item_count,fingerprint_version,
                       HEX(fingerprint) fingerprint,created_at,BIN_TO_UUID(quiz_revision_id) revision
                FROM learning_form WHERE session_id=UUID_TO_BIN(:session)
                """, parameters(session), (r, n) -> new Header(uuid(r, "id"), r.getInt("schema_version"),
                    r.getString("outcome"), r.getInt("item_count"), r.getInt("fingerprint_version"),
                    r.getString("fingerprint").toLowerCase(Locale.ROOT),
                    r.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), uuid(r, "revision")));
            if (headers.isEmpty()) return Optional.empty();
            require(headers.size() == 1, CORRUPT);
            var h = headers.get(0);
            require(session.source().quizRevisionId().equals(h.revision()), CORRUPT);
            var p = new MapSqlParameterSource("form", h.id().toString());
            var options = new HashMap<Integer, List<UUID>>();
            sql.query("""
                SELECT item_position,position,BIN_TO_UUID(option_id) option_id
                FROM learning_form_option WHERE form_id=UUID_TO_BIN(:form) ORDER BY item_position,position
                """, p, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                var ids = options.computeIfAbsent(r.getInt("item_position"), ignored -> new ArrayList<>());
                require(r.getInt("position") == ids.size(), CORRUPT);
                ids.add(uuid(r, "option_id"));
            });
            var items = sql.query("""
                SELECT position,BIN_TO_UUID(source_item_id) source_id,BIN_TO_UUID(question_revision_id) question_id,
                       question_schema_version,response_contract_id,response_contract_version,option_count
                FROM learning_form_item WHERE form_id=UUID_TO_BIN(:form) ORDER BY position
                """, p, (r, index) -> {
                require(r.getInt("position") == index, CORRUPT);
                var ids = options.remove(index);
                if (ids == null) ids = List.of();
                require(ids.size() == r.getInt("option_count"), CORRUPT);
                return new LearningForm.Item(uuid(r, "source_id"), uuid(r, "question_id"),
                        r.getInt("question_schema_version"), new ContractVersion(r.getString("response_contract_id"),
                        r.getInt("response_contract_version")), ids);
            });
            require(options.isEmpty() && items.size() == h.count(), CORRUPT);
            return Optional.of(new LearningForm(h.id(), h.version(), session.id(), session.source(), items,
                    LearningForm.Outcome.valueOf(h.outcome()), h.fingerprintVersion(), h.fingerprint(), h.time()));
        });
    }

    /** Select only requested metadata, in 500-ID batches; no whole-quiz/text/answer loading. */
    @Override public boolean matchesSourceItems(LearningSession session, List<LearningForm.Item> items) {
        return safe(() -> {
            for (int start = 0; start < items.size(); start += 500) {
                var batch = items.subList(start, Math.min(start + 500, items.size()));
                var expected = new HashMap<UUID, LearningForm.Item>();
                batch.forEach(item -> expected.put(item.sourceItemId(), item));
                var p = parameters(session).addValue("ids", batch.stream().map(item -> bytes(item.sourceItemId())).toList());
                var found = sql.query("""
                    SELECT BIN_TO_UUID(i.id) source_id,BIN_TO_UUID(i.question_revision_id) question_id,
                           q.schema_version,b.response_contract_id,b.response_contract_version
                    FROM quiz_revision_items i JOIN question_revisions q ON q.id=i.question_revision_id
                    JOIN question_revision_contract_bindings b ON b.question_revision_id=q.id
                    WHERE i.quiz_revision_id=UUID_TO_BIN(:revision) AND i.id IN (:ids)
                    """, p, (r, n) -> {
                    var item = expected.get(uuid(r, "source_id"));
                    return item != null && item.questionRevisionId().equals(uuid(r, "question_id"))
                            && item.questionSchemaVersion() == r.getInt("schema_version")
                            && item.response().id().equals(r.getString("response_contract_id"))
                            && item.response().version() == r.getInt("response_contract_version");
                });
                if (found.size() != batch.size() || found.contains(false)) return false;
                var optionSets = new HashMap<UUID, Set<UUID>>();
                sql.query("""
                    SELECT BIN_TO_UUID(i.id) source_id,BIN_TO_UUID(o.id) option_id
                    FROM quiz_revision_items i JOIN question_option_revisions o ON o.question_revision_id=i.question_revision_id
                    WHERE i.quiz_revision_id=UUID_TO_BIN(:revision) AND i.id IN (:ids)
                    """, p, (org.springframework.jdbc.core.RowCallbackHandler) r ->
                    optionSets.computeIfAbsent(uuid(r, "source_id"), ignored -> new HashSet<>()).add(uuid(r, "option_id")));
                for (var item : batch) {
                    if (!new HashSet<>(item.optionIds()).equals(optionSets.getOrDefault(item.sourceItemId(), Set.of()))) return false;
                }
            }
            return true;
        });
    }

    @Override public void insert(LearningForm form) {
        safe(() -> {
            var p = new MapSqlParameterSource("form", form.id().toString()).addValue("session", form.sessionId().toString())
                    .addValue("revision", form.source().quizRevisionId().toString()).addValue("outcome", form.outcome().name())
                    .addValue("count", form.items().size()).addValue("fingerprint", form.fingerprint())
                    .addValue("time", LocalDateTime.ofInstant(form.createdAt(), ZoneOffset.UTC));
            sql.update("""
                INSERT INTO learning_form(id,session_id,quiz_revision_id,schema_version,outcome,item_count,
                                          fingerprint_version,fingerprint,created_at)
                VALUES(UUID_TO_BIN(:form),UUID_TO_BIN(:session),UUID_TO_BIN(:revision),1,:outcome,:count,1,UNHEX(:fingerprint),:time)
                """, p);
            var itemRows = new ArrayList<SqlParameterSource>();
            var optionRows = new ArrayList<SqlParameterSource>();
            for (int index = 0; index < form.items().size(); index++) {
                var item = form.items().get(index);
                var row = new MapSqlParameterSource(p.getValues()).addValue("position", index)
                        .addValue("source", item.sourceItemId().toString()).addValue("question", item.questionRevisionId().toString())
                        .addValue("questionVersion", item.questionSchemaVersion()).addValue("contract", item.response().id())
                        .addValue("contractVersion", item.response().version()).addValue("options", item.optionIds().size());
                itemRows.add(row);
                for (int option = 0; option < item.optionIds().size(); option++) optionRows.add(
                        new MapSqlParameterSource(row.getValues()).addValue("optionPosition", option)
                            .addValue("option", item.optionIds().get(option).toString()));
            }
            batch("""
                    INSERT INTO learning_form_item(form_id,position,quiz_revision_id,source_item_id,question_revision_id,
                        question_schema_version,response_contract_id,response_contract_version,option_count)
                    VALUES(UUID_TO_BIN(:form),:position,UUID_TO_BIN(:revision),UUID_TO_BIN(:source),UUID_TO_BIN(:question),
                           :questionVersion,:contract,:contractVersion,:options)
                    """, itemRows);
            batch("""
                    INSERT INTO learning_form_option(form_id,item_position,position,option_id)
                    VALUES(UUID_TO_BIN(:form),:position,:optionPosition,UUID_TO_BIN(:option))
                    """, optionRows);
            return null;
        });
    }

    private void batch(String statement, List<SqlParameterSource> rows) {
        for (int start = 0; start < rows.size(); start += 500)
            sql.batchUpdate(statement, rows.subList(start, Math.min(start + 500, rows.size())).toArray(SqlParameterSource[]::new));
    }

    private static MapSqlParameterSource parameters(LearningSession session) {
        return new MapSqlParameterSource("session", session.id().toString()).addValue("learner", session.learnerId().toString())
                .addValue("revision", session.source().quizRevisionId().toString());
    }
    private record Header(UUID id, int version, String outcome, int count, int fingerprintVersion,
                          String fingerprint, Instant time, UUID revision) { }
    private static UUID uuid(ResultSet row, String name) throws SQLException { return UUID.fromString(row.getString(name)); }
    private static byte[] bytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    private static <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
        catch (LearningFormException failure) { throw new LearningFormException(failure.reason()); }
        catch (RuntimeException failure) { throw new LearningFormException(CORRUPT); }
    }
}
