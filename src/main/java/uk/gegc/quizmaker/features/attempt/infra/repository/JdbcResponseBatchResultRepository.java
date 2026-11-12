package uk.gegc.quizmaker.features.attempt.infra.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.repository.ResponseBatchResultRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.CORRUPT;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcResponseBatchResultRepository implements ResponseBatchResultRepository {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcResponseBatchResultRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void insert(UUID commandId, BatchResult result) {
        try {
            var array = JSON.createArrayNode();
            result.responses().forEach(item -> array.addObject().put("formItemId", item.formItemId().toString())
                    .put("acceptedResponseId", item.acceptedResponseId().toString()));
            jdbc.update("""
                    INSERT INTO attempt_response_batch_result
                      (command_id,schema_version,attempt_id,state_version,item_count,items_json)
                    VALUES (UUID_TO_BIN(:command),1,UUID_TO_BIN(:attempt),:version,:count,:items)
                    """, Map.of("command", commandId.toString(), "attempt", result.attemptId().toString(),
                    "version", result.stateVersion(), "count", result.responses().size(), "items", array.toString()));
        } catch (org.springframework.dao.DataAccessException failure) { throw uk.gegc.quizmaker.shared.exception.SafeDataAccess.sanitize(failure); }
    }
    @Override public BatchResult read(UUID commandId, UUID attemptId, long stateVersion) {
        try {
            var rows = jdbc.query("""
                    SELECT schema_version,BIN_TO_UUID(attempt_id) attempt_id,state_version,item_count,items_json
                    FROM attempt_response_batch_result WHERE command_id=UUID_TO_BIN(:command) FOR SHARE
                    """, Map.of("command", commandId.toString()), (row, n) -> {
                try {
                    if (row.getInt("schema_version") != 1 || !attemptId.toString().equals(row.getString("attempt_id"))
                            || row.getLong("state_version") != stateVersion) throw new IllegalArgumentException();
                    var tree = JSON.readTree(row.getString("items_json"));
                    if (!tree.isArray() || tree.isEmpty() || tree.size() > 500 || tree.size() != row.getInt("item_count"))
                        throw new IllegalArgumentException();
                    var result = new ArrayList<AcceptedItem>();
                    var items = new HashSet<UUID>(); var answers = new HashSet<UUID>();
                    for (var value : tree) {
                        if (!value.isObject() || value.size() != 2) throw new IllegalArgumentException();
                        var item = UUID.fromString(value.path("formItemId").textValue());
                        var answer = UUID.fromString(value.path("acceptedResponseId").textValue());
                        if (!items.add(item) || !answers.add(answer)) throw new IllegalArgumentException();
                        result.add(new AcceptedItem(item, answer));
                    }
                    return new BatchResult(attemptId, result, stateVersion, false);
                } catch (java.io.IOException | RuntimeException invalid) { throw new ResponseAcceptanceException(CORRUPT); }
            });
            if (rows.size() != 1) throw new ResponseAcceptanceException(CORRUPT);
            var result = rows.get(0);
            var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("attempt", attemptId.toString());
            var placeholders = new ArrayList<String>();
            for (int i = 0; i < result.responses().size(); i++) {
                placeholders.add("UUID_TO_BIN(:id" + i + ")");
                parameters.addValue("id" + i, result.responses().get(i).formItemId().toString());
            }
            var verified = jdbc.query("""
                    SELECT r.schema_version, BIN_TO_UUID(r.id) id, BIN_TO_UUID(r.form_item_id) item, BIN_TO_UUID(r.command_id) command_id
                    FROM attempt_accepted_response r FORCE INDEX (uq_accepted_attempt_item)
                    JOIN attempt_form_item f ON f.form_id=r.form_id AND f.id=r.form_item_id
                    WHERE r.attempt_id=UUID_TO_BIN(:attempt) AND r.form_item_id IN (
                    """ + String.join(",", placeholders) + ") ORDER BY f.position FOR SHARE", parameters, (row, n) -> {
                var item = UUID.fromString(row.getString("item"));
                if (row.getInt("schema_version") != 1 || !uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.itemCommand(commandId,item)
                        .toString().equals(row.getString("command_id"))) throw new ResponseAcceptanceException(CORRUPT);
                return new AcceptedItem(item, UUID.fromString(row.getString("id")));
            });
            if (!verified.equals(result.responses())) throw new ResponseAcceptanceException(CORRUPT);
            return result;
        } catch (org.springframework.dao.DataAccessException failure) { throw uk.gegc.quizmaker.shared.exception.SafeDataAccess.sanitize(failure); }
    }
}
