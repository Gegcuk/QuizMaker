package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Joins the complete revision transaction; no independently callable case/member writer. */
final class JdbcRevisionCases {
    private static final int SQL_BATCH = 500;
    private final NamedParameterJdbcTemplate jdbc;

    JdbcRevisionCases(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    void insert(QuizRevision revision) {
        var rows = new ArrayList<MapSqlParameterSource>();
        var members = new ArrayList<MapSqlParameterSource>();
        for (int slot = 0; slot < revision.caseBlocks().size(); slot++) {
            var block = revision.caseBlocks().get(slot);
            rows.add(parameters(revision.id(), block.id()).addValue("section", bytes(block.sectionId()))
                    .addValue("version", block.schemaVersion()).addValue("slot", slot).addValue("position", block.authoredPosition())
                    .addValue("stimulus", bytes(block.stimulusRevisionId())).addValue("atomic", block.atomic())
                    .addValue("count", block.itemIds().size()));
            for (int item = 0; item < block.itemIds().size(); item++) members.add(parameters(revision.id(), block.id())
                    .addValue("section", bytes(block.sectionId())).addValue("item", bytes(block.itemIds().get(item))).addValue("slot", item));
        }
        batch("""
                INSERT INTO quiz_revision_cases
                  (quiz_revision_id,id,section_id,schema_version,ordinal_slot,authored_position,stimulus_revision_id,atomic_selection,item_count)
                VALUES (:root,:case,:section,:version,:slot,:position,:stimulus,:atomic,:count)
                """, rows);
        batch("""
                INSERT INTO quiz_revision_case_items (quiz_revision_id,case_id,section_id,item_id,ordinal_slot)
                VALUES (:root,:case,:section,:item,:slot)
                """, members);
    }

    Map<UUID, List<RevisionCaseBlock>> read(Map<UUID, Integer> expectedCounts, boolean current) {
        if (expectedCounts.isEmpty()) return Map.of();
        require(expectedCounts.values().stream().allMatch(count -> count > 0));
        var parameters = Map.of("roots", expectedCounts.keySet().stream().map(JdbcRevisionCases::bytes).toList());
        String lock = current ? " FOR SHARE" : "";
        var rows = jdbc.query("""
                SELECT * FROM quiz_revision_cases WHERE quiz_revision_id IN (:roots)
                ORDER BY quiz_revision_id,ordinal_slot
                """ + lock, parameters, (row, index) -> new Row(uuid(row, "quiz_revision_id"), uuid(row, "id"),
                uuid(row, "section_id"), row.getInt("schema_version"), row.getInt("ordinal_slot"), row.getInt("authored_position"),
                uuid(row, "stimulus_revision_id"), row.getBoolean("atomic_selection"), row.getInt("item_count")));
        var members = jdbc.query("""
                SELECT * FROM quiz_revision_case_items WHERE quiz_revision_id IN (:roots)
                ORDER BY quiz_revision_id,case_id,ordinal_slot
                """ + lock, parameters, (row, index) -> new Member(new Key(uuid(row, "quiz_revision_id"), uuid(row, "case_id")),
                uuid(row, "section_id"), uuid(row, "item_id"), row.getInt("ordinal_slot")));
        Map<Key, List<Member>> membership = new HashMap<>();
        for (var member : members) {
            var items = membership.computeIfAbsent(member.key(), ignored -> new ArrayList<>());
            require(member.slot() == items.size());
            items.add(member);
        }
        Map<UUID, List<RevisionCaseBlock>> result = new HashMap<>();
        for (var row : rows) {
            var blocks = result.computeIfAbsent(row.root(), ignored -> new ArrayList<>());
            var items = membership.remove(new Key(row.root(), row.id()));
            require(row.slot() == blocks.size() && items != null && row.count() == items.size());
            require(items.stream().allMatch(item -> item.sectionId().equals(row.sectionId())));
            blocks.add(new RevisionCaseBlock(row.id(), row.version(), row.sectionId(), row.position(), row.stimulus(), row.atomic(),
                    items.stream().map(Member::itemId).toList()));
        }
        require(membership.isEmpty() && result.keySet().equals(expectedCounts.keySet()));
        expectedCounts.forEach((root, count) -> require(result.get(root).size() == count));
        return result;
    }

    private void batch(String statement, List<MapSqlParameterSource> rows) {
        for (int offset = 0; offset < rows.size(); offset += SQL_BATCH)
            jdbc.batchUpdate(statement, rows.subList(offset, Math.min(offset + SQL_BATCH, rows.size())).toArray(MapSqlParameterSource[]::new));
    }
    private static MapSqlParameterSource parameters(UUID root, UUID block) {
        return new MapSqlParameterSource("root", bytes(root)).addValue("case", bytes(block));
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
    private record Key(UUID root, UUID block) { }
    private record Row(UUID root, UUID id, UUID sectionId, int version, int slot, int position, UUID stimulus, boolean atomic, int count) { }
    private record Member(Key key, UUID sectionId, UUID itemId, int slot) { }
}
