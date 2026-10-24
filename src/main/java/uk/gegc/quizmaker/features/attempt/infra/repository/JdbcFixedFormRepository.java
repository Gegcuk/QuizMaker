package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcFixedFormRepository implements FixedFormRepository {
    // SQL parameter batching, not a product limit on forms or quiz size.
    private static final int SQL_BATCH = 500;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFixedFormRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void createOrReplay(UUID ownerId, FixedForm form) {
        if (ownerId == null) throw new FixedFormException(REVISION_UNAVAILABLE);
        if (form == null) throw new FixedFormException(INVALID_FORM);
        var parameters = new MapSqlParameterSource("owner", bytes(ownerId))
                .addValue("id", bytes(form.id())).addValue("revision", bytes(form.quizRevisionId()))
                .addValue("version", form.schemaVersion()).addValue("count", form.items().size());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM quiz_revisions WHERE id=:revision AND owner_id=:owner)
                """, parameters, Boolean.class))) throw new FixedFormException(REVISION_UNAVAILABLE);
        var sources = form.items().stream().map(FixedForm.Item::sourceItemId).toList();
        for (int start = 0; start < sources.size(); start += SQL_BATCH) {
            var part = sources.subList(start, Math.min(start + SQL_BATCH, sources.size()));
            parameters.addValue("sources", part.stream().map(JdbcFixedFormRepository::bytes).toList());
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM quiz_revision_items WHERE quiz_revision_id=:revision AND id IN (:sources)
                    """, parameters, Integer.class);
            if (!Objects.equals(count, part.size())) throw new FixedFormException(REVISION_UNAVAILABLE);
        }
        // The unique root key serializes callers, including a concurrent identical first insert.
        // Never overwrite metadata. item_count prevents an apparent replay from appending items.
        jdbc.update("""
                INSERT INTO attempt_form (id,schema_version,quiz_revision_id,item_count)
                VALUES (:id,:version,:revision,:count)
                ON DUPLICATE KEY UPDATE id=attempt_form.id
                """, parameters);
        var root = jdbc.query("SELECT id,schema_version,quiz_revision_id,item_count FROM attempt_form WHERE id=:id FOR UPDATE",
                parameters, (row, index) -> root(row)).get(0);
        if (!root.revision().equals(form.quizRevisionId())) {
            Boolean owned = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM quiz_revisions WHERE id=:revision AND owner_id=:owner)",
                    new MapSqlParameterSource("revision", bytes(root.revision())).addValue("owner", bytes(ownerId)), Boolean.class);
            if (!Boolean.TRUE.equals(owned)) throw new FixedFormException(FORM_UNAVAILABLE);
            throw new FixedFormException(FORM_CONFLICT);
        }
        if (root.version() != form.schemaVersion() || root.count() != form.items().size()) {
            throw new FixedFormException(FORM_CONFLICT);
        }
        var rows = form.items().stream().sorted(Comparator.comparing(item -> item.id().toString()))
                .map(item -> new MapSqlParameterSource("form", bytes(form.id())).addValue("id", bytes(item.id()))
                        .addValue("revision", bytes(form.quizRevisionId())).addValue("source", bytes(item.sourceItemId()))
                        .addValue("position", item.position())).toList();
        for (int start = 0; start < rows.size(); start += SQL_BATCH) {
            jdbc.batchUpdate("""
                    INSERT INTO attempt_form_item (form_id,id,quiz_revision_id,source_item_id,position)
                    VALUES (:form,:id,:revision,:source,:position)
                    ON DUPLICATE KEY UPDATE id=attempt_form_item.id
                    """, rows.subList(start, Math.min(start + SQL_BATCH, rows.size())).toArray(MapSqlParameterSource[]::new));
        }
        // Current reads also work when the outer REPEATABLE READ transaction has an older snapshot.
        var saved = jdbc.query("SELECT id,source_item_id,position FROM attempt_form_item WHERE form_id=:id ORDER BY position FOR UPDATE",
                parameters, (row, index) -> item(row));
        if (!saved.equals(form.items())) throw new FixedFormException(FORM_CONFLICT);
    }

    @Override
    public List<FixedForm> findByIds(UUID ownerId, Collection<UUID> formIds) {
        if (ownerId == null) throw new FixedFormException(FORM_UNAVAILABLE);
        if (formIds == null || formIds.stream().anyMatch(Objects::isNull)) throw new FixedFormException(INVALID_FORM);
        var ids = formIds.stream().distinct().toList();
        Map<UUID, FixedForm> result = new HashMap<>();
        for (int start = 0; start < ids.size(); start += SQL_BATCH) {
            var part = ids.subList(start, Math.min(start + SQL_BATCH, ids.size()));
            var parameters = new MapSqlParameterSource("owner", bytes(ownerId))
                    .addValue("ids", part.stream().map(JdbcFixedFormRepository::bytes).toList());
            var roots = jdbc.query("""
                    SELECT f.id,f.schema_version,f.quiz_revision_id,f.item_count FROM attempt_form f
                    JOIN quiz_revisions r ON r.id=f.quiz_revision_id WHERE r.owner_id=:owner AND f.id IN (:ids)
                    ORDER BY f.id
                    """, parameters, (row, index) -> root(row));
            if (roots.isEmpty()) continue;
            parameters.addValue("ids", roots.stream().map(root -> bytes(root.id())).toList());
            Map<UUID, List<FixedForm.Item>> items = new HashMap<>();
            jdbc.query("SELECT form_id,id,source_item_id,position FROM attempt_form_item WHERE form_id IN (:ids) ORDER BY form_id,position",
                    parameters, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                            items.computeIfAbsent(uuid(row, "form_id"), ignored -> new ArrayList<>()).add(item(row)));
            for (var root : roots) {
                var children = items.getOrDefault(root.id(), List.of());
                try {
                    if (root.count() != children.size()) throw new FixedFormException(INVALID_STORED_FORM);
                    result.put(root.id(), new FixedForm(root.id(), root.version(), root.revision(), children));
                } catch (FixedFormException failure) { throw new FixedFormException(INVALID_STORED_FORM); }
            }
        }
        return ids.stream().filter(result::containsKey).map(result::get).toList();
    }

    private static Root root(ResultSet row) throws SQLException {
        return new Root(uuid(row, "id"), row.getInt("schema_version"), uuid(row, "quiz_revision_id"), row.getInt("item_count"));
    }

    private static FixedForm.Item item(ResultSet row) throws SQLException {
        try { return new FixedForm.Item(uuid(row, "id"), uuid(row, "source_item_id"), row.getInt("position")); }
        catch (FixedFormException failure) { throw new FixedFormException(INVALID_STORED_FORM); }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        var buffer = ByteBuffer.wrap(row.getBytes(column));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record Root(UUID id, int version, UUID revision, int count) { }
}
