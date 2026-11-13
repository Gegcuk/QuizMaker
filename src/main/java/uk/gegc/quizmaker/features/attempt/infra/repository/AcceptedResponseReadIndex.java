package uk.gegc.quizmaker.features.attempt.infra.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.Reference;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.CORRUPT;

/** Transactional source-maintained metadata; never locks accepted-row ranges or loads answer payloads. */
final class AcceptedResponseReadIndex {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AcceptedResponseReadIndex() { }

    record Stored(UUID id, UUID commandId, Reference reference, int position) {
        @Override public String toString() { return "AcceptedResponseIdentity[redacted]"; }
    }
    private record Raw(String form, String identities, String references) { }

    static List<Stored> all(NamedParameterJdbcTemplate jdbc, UUID attemptId) {
        var rows = jdbc.query("""
                SELECT BIN_TO_UUID(form_id) form_id,response_index,response_reference_index
                FROM attempt_trace_stream FORCE INDEX (PRIMARY)
                WHERE attempt_id=UUID_TO_BIN(:attempt) FOR SHARE
                """, Map.of("attempt", attemptId.toString()), (row, n) -> new Raw(row.getString("form_id"),
                row.getString("response_index"), row.getString("response_reference_index")));
        if (rows.size() != 1) throw corrupt();
        try {
            var raw = rows.get(0);
            UUID form = UUID.fromString(raw.form());
            var identities = JSON.readTree(raw.identities());
            var references = JSON.readTree(raw.references());
            envelope(identities, "ids");
            envelope(references, "responses");
            var ids = new HashSet<UUID>();
            var fields = identities.path("ids").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                UUID id = uuid(entry.getKey());
                if (!entry.getValue().isInt() || entry.getValue().intValue() != 1 || !ids.add(id)) throw corrupt();
            }
            var result = new ArrayList<Stored>();
            var found = new HashSet<UUID>();
            var items = new HashSet<UUID>();
            var positions = new HashSet<Integer>();
            fields = references.path("responses").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                UUID id = uuid(entry.getKey());
                var value = entry.getValue();
                if (!value.isObject() || value.size() != 8 || integer(value, "schemaVersion") != 1) throw corrupt();
                UUID item = uuid(text(value, "formItemId"));
                int position = integer(value, "position");
                if (!form.equals(uuid(text(value, "formId"))) || position < 0 || !found.add(id)
                        || !items.add(item) || !positions.add(position)) throw corrupt();
                result.add(new Stored(id, uuid(text(value, "commandId")), new Reference(form, item,
                        uuid(text(value, "questionRevisionId")), new ContractVersion(text(value, "responseContractId"),
                        integer(value, "responseContractVersion"))), position));
            }
            if (!found.equals(ids)) throw corrupt();
            result.sort(Comparator.comparing(value -> value.reference().formItemId().toString()));
            return List.copyOf(result);
        } catch (java.io.IOException | RuntimeException invalid) {
            throw corrupt();
        }
    }

    static List<Stored> selected(NamedParameterJdbcTemplate jdbc, UUID attemptId, Collection<UUID> ids) {
        var requested = new HashSet<>(ids);
        if (requested.size() != ids.size()) throw corrupt();
        var result = all(jdbc, attemptId).stream().filter(value -> requested.contains(value.id())).toList();
        if (result.size() != requested.size()) throw corrupt();
        return result;
    }

    private static void envelope(JsonNode value, String field) {
        if (value == null || !value.isObject() || value.size() != 2 || integer(value, "version") != 1
                || !value.path(field).isObject()) throw corrupt();
    }
    private static int integer(JsonNode value, String field) {
        if (!value.path(field).isInt()) throw corrupt();
        return value.path(field).intValue();
    }
    private static String text(JsonNode value, String field) {
        if (!value.path(field).isTextual()) throw corrupt();
        return value.path(field).textValue();
    }
    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw corrupt();
        return id;
    }
    private static ResponseAcceptanceException corrupt() { return new ResponseAcceptanceException(CORRUPT); }
}
