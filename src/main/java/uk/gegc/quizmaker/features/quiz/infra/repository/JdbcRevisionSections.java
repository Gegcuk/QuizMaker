package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionSection;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** SQL helper joining its owning repository transaction; never an independently callable writer. */
final class JdbcRevisionSections {
    private static final int SQL_BATCH = 500;
    private final NamedParameterJdbcTemplate jdbc;

    JdbcRevisionSections(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    void insert(QuizRevision revision) {
        if (!revision.structured()) return;
        var rows = new ArrayList<MapSqlParameterSource>();
        var members = new ArrayList<MapSqlParameterSource>();
        for (int slot = 0; slot < revision.sections().size(); slot++) {
            var section = revision.sections().get(slot);
            rows.add(parameters(revision.id(), section.id()).addValue("version", section.schemaVersion())
                    .addValue("slot", slot).addValue("position", section.authoredPosition()).addValue("title", section.title())
                    .addValue("instructions", section.instructions()).addValue("count", section.itemIds().size()));
            for (int item = 0; item < section.itemIds().size(); item++) members.add(parameters(revision.id(), section.id())
                    .addValue("item", bytes(section.itemIds().get(item))).addValue("slot", item));
        }
        batch("""
                INSERT INTO quiz_revision_sections
                  (quiz_revision_id,id,schema_version,ordinal_slot,authored_position,title,instructions,item_count)
                VALUES (:root,:section,:version,:slot,:position,:title,:instructions,:count)
                """, rows);
        batch("""
                INSERT INTO quiz_revision_section_items (quiz_revision_id,section_id,item_id,ordinal_slot)
                VALUES (:root,:section,:item,:slot)
                """, members);
    }

    /** Caller supplies at most one root SQL batch, already filtered by its trusted owner. */
    Map<UUID, List<RevisionSection>> read(Map<UUID, Integer> expectedCounts, boolean current) {
        if (expectedCounts.isEmpty()) return Map.of();
        require(expectedCounts.values().stream().allMatch(count -> count > 0));
        var parameters = Map.of("roots", expectedCounts.keySet().stream().map(JdbcRevisionSections::bytes).toList());
        String lock = current ? " FOR SHARE" : "";
        var rows = jdbc.query("""
                SELECT * FROM quiz_revision_sections WHERE quiz_revision_id IN (:roots)
                ORDER BY quiz_revision_id,ordinal_slot
                """ + lock, parameters, (row, index) -> new Row(uuid(row, "quiz_revision_id"), uuid(row, "id"),
                row.getInt("schema_version"), row.getInt("ordinal_slot"), row.getInt("authored_position"),
                row.getString("title"), row.getString("instructions"), row.getInt("item_count")));
        var members = jdbc.query("""
                SELECT * FROM quiz_revision_section_items WHERE quiz_revision_id IN (:roots)
                ORDER BY quiz_revision_id,section_id,ordinal_slot
                """ + lock, parameters, (row, index) -> new Member(new Key(uuid(row, "quiz_revision_id"), uuid(row, "section_id")),
                uuid(row, "item_id"), row.getInt("ordinal_slot")));
        Map<Key, List<UUID>> membership = new HashMap<>();
        for (var member : members) {
            var items = membership.computeIfAbsent(member.key(), ignored -> new ArrayList<>());
            require(member.slot() == items.size());
            items.add(member.itemId());
        }
        Map<UUID, List<RevisionSection>> result = new HashMap<>();
        for (var row : rows) {
            var sections = result.computeIfAbsent(row.root(), ignored -> new ArrayList<>());
            require(row.slot() == sections.size() && (sections.isEmpty()
                    || sections.get(sections.size() - 1).authoredPosition() < row.position()));
            var itemIds = membership.remove(new Key(row.root(), row.id()));
            if (itemIds == null) itemIds = List.of();
            require(row.count() == itemIds.size());
            sections.add(new RevisionSection(row.id(), row.version(), row.position(), row.title(), row.instructions(), itemIds));
        }
        require(membership.isEmpty() && result.keySet().equals(expectedCounts.keySet()));
        expectedCounts.forEach((root, count) -> require(result.get(root).size() == count));
        return result;
    }

    private void batch(String statement, List<MapSqlParameterSource> rows) {
        for (int offset = 0; offset < rows.size(); offset += SQL_BATCH)
            jdbc.batchUpdate(statement, rows.subList(offset, Math.min(offset + SQL_BATCH, rows.size())).toArray(MapSqlParameterSource[]::new));
    }

    private static MapSqlParameterSource parameters(UUID root, UUID section) {
        return new MapSqlParameterSource("root", bytes(root)).addValue("section", bytes(section));
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] value = row.getBytes(column);
        require(value != null && value.length == 16);
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static void require(boolean valid) {
        if (!valid) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
    }
    private record Key(UUID root, UUID section) { }
    private record Row(UUID root, UUID id, int version, int slot, int position, String title, String instructions, int count) { }
    private record Member(Key key, UUID itemId, int slot) { }
}
