package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JdbcQuizRevisionRepository implements QuizRevisionRepository {
    // SQL parameter batching only; this does not limit the size of a published quiz.
    private static final int SQL_BATCH = 500;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcQuizRevisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(QuizRevision revision) {
        Objects.requireNonNull(revision, "revision must not be null");
        Map<UUID, QuestionRevision> questions = new LinkedHashMap<>();
        revision.items().forEach(item -> questions.put(item.question().id(), item.question()));
        Map<UUID, QuestionRevision> existing = readQuestions(new ArrayList<>(questions.keySet()));
        List<QuestionRevision> additions = new ArrayList<>();
        questions.forEach((id, question) -> {
            if (!existing.containsKey(id)) additions.add(question);
            else if (!existing.get(id).equals(question)) {
                throw new RevisionIntegrityException(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
            }
        });
        batch("""
                INSERT INTO question_revisions
                (id, question_id, schema_version, question_type, difficulty, question_text, content,
                 hint, explanation, attachment_url, attachment_asset_id, created_at)
                VALUES (:id, :questionId, :version, :type, :difficulty, :text, :content,
                        :hint, :explanation, :url, :assetId, :created)
                """, additions.stream().map(this::questionParameters).toList());
        List<MapSqlParameterSource> options = new ArrayList<>();
        additions.forEach(question -> question.options().forEach(option -> options.add(new MapSqlParameterSource()
                .addValue("id", bytes(option.id())).addValue("questionId", bytes(question.id()))
                .addValue("key", option.sourceKey()).addValue("position", option.authoredPosition()))));
        batch("""
                INSERT INTO question_option_revisions (id, question_revision_id, source_key, authored_position)
                VALUES (:id, :questionId, :key, :position)
                """, options);
        jdbc.update("""
                INSERT INTO quiz_revisions (id, quiz_id, owner_id, schema_version, title, description, created_at)
                VALUES (:id, :quizId, :ownerId, :version, :title, :description, :created)
                """, new MapSqlParameterSource().addValue("id", bytes(revision.id()))
                .addValue("quizId", bytes(revision.quizId())).addValue("ownerId", bytes(revision.ownerId()))
                .addValue("version", revision.schemaVersion()).addValue("title", revision.title())
                .addValue("description", revision.description())
                .addValue("created", Timestamp.from(revision.createdAt())));
        batch("""
                INSERT INTO quiz_revision_items (id, quiz_revision_id, question_revision_id, authored_position)
                VALUES (:id, :quizId, :questionId, :position)
                """, revision.items().stream().map(item -> new MapSqlParameterSource()
                .addValue("id", bytes(item.id())).addValue("quizId", bytes(revision.id()))
                .addValue("questionId", bytes(item.question().id()))
                .addValue("position", item.authoredPosition())).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuizRevision> findById(UUID ownerId, UUID revisionId) {
        return QuizRevisionRepository.super.findById(ownerId, revisionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizRevision> findByIds(UUID ownerId, Collection<UUID> revisionIds) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(revisionIds, "revisionIds must not be null");
        List<UUID> ids = revisionIds.stream().map(Objects::requireNonNull).distinct().toList();
        Map<UUID, QuizRevision> result = new HashMap<>();
        try {
            for (int start = 0; start < ids.size(); start += SQL_BATCH) {
                List<UUID> part = ids.subList(start, Math.min(start + SQL_BATCH, ids.size()));
                Map<String, Object> parameters = Map.of("owner", bytes(ownerId), "ids", binaryIds(part));
                List<QuizRevision> roots = jdbc.query("""
                        SELECT * FROM quiz_revisions WHERE owner_id = :owner AND id IN (:ids)
                        """, parameters, (rs, row) -> new QuizRevision(uuid(rs, "id"), uuid(rs, "quiz_id"),
                        uuid(rs, "owner_id"), rs.getInt("schema_version"), rs.getString("title"), rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant(), List.of()));
                if (roots.isEmpty()) continue;
                Map<String, Object> scoped = Map.of("ids", binaryIds(roots.stream().map(QuizRevision::id).toList()));
                List<ItemRow> items = jdbc.query("""
                        SELECT i.id AS item_id, i.quiz_revision_id, i.authored_position AS item_position, q.*
                        FROM quiz_revision_items i JOIN question_revisions q ON q.id = i.question_revision_id
                        WHERE i.quiz_revision_id IN (:ids) ORDER BY i.quiz_revision_id, i.authored_position
                        """, scoped, (rs, row) -> new ItemRow(uuid(rs, "quiz_revision_id"), uuid(rs, "item_id"),
                        rs.getInt("item_position"), question(rs)));
                Map<UUID, List<QuestionRevision.Option>> options = readOptions("""
                        EXISTS (SELECT 1 FROM quiz_revision_items i
                                WHERE i.question_revision_id = o.question_revision_id AND i.quiz_revision_id IN (:ids))
                        """, scoped);
                Map<UUID, List<QuizRevision.Item>> byRoot = new HashMap<>();
                items.forEach(item -> byRoot.computeIfAbsent(item.rootId(), ignored -> new ArrayList<>())
                        .add(new QuizRevision.Item(item.id(), item.position(),
                                withOptions(item.question(), options.getOrDefault(item.question().id(), List.of())))));
                roots.forEach(root -> result.put(root.id(), new QuizRevision(root.id(), root.quizId(), root.ownerId(),
                        root.schemaVersion(), root.title(), root.description(), root.createdAt(), byRoot.getOrDefault(root.id(), List.of()))));
            }
        } catch (IllegalArgumentException exception) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        }
        return ids.stream().filter(result::containsKey).map(result::get).toList();
    }

    private Map<UUID, QuestionRevision> readQuestions(List<UUID> ids) {
        Map<UUID, QuestionRevision> result = new HashMap<>();
        for (int start = 0; start < ids.size(); start += SQL_BATCH) {
            Map<String, Object> parameters = Map.of("ids", binaryIds(ids.subList(start, Math.min(start + SQL_BATCH, ids.size()))));
            List<QuestionRevision> questions = jdbc.query("SELECT * FROM question_revisions WHERE id IN (:ids)",
                    parameters, (rs, row) -> question(rs));
            Map<UUID, List<QuestionRevision.Option>> options = readOptions("o.question_revision_id IN (:ids)", parameters);
            questions.forEach(question -> result.put(question.id(),
                    withOptions(question, options.getOrDefault(question.id(), List.of()))));
        }
        return result;
    }

    private Map<UUID, List<QuestionRevision.Option>> readOptions(String predicate, Map<String, ?> parameters) {
        Map<UUID, List<QuestionRevision.Option>> result = new HashMap<>();
        jdbc.query("SELECT o.* FROM question_option_revisions o WHERE " + predicate + " ORDER BY o.authored_position",
                parameters, (rs, row) -> {
                    result.computeIfAbsent(uuid(rs, "question_revision_id"), ignored -> new ArrayList<>())
                            .add(new QuestionRevision.Option(uuid(rs, "id"), rs.getString("source_key"),
                                    rs.getInt("authored_position")));
                    return row;
                });
        return result;
    }

    private QuestionRevision question(ResultSet rs) throws SQLException {
        try {
            return new QuestionRevision(uuid(rs, "id"), uuid(rs, "question_id"), rs.getInt("schema_version"),
                    QuestionType.valueOf(rs.getString("question_type")), Difficulty.valueOf(rs.getString("difficulty")),
                    rs.getString("question_text"), rs.getString("content"), rs.getString("hint"), rs.getString("explanation"),
                    rs.getString("attachment_url"), uuid(rs, "attachment_asset_id"), rs.getTimestamp("created_at").toInstant(), List.of());
        } catch (IllegalArgumentException exception) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        }
    }

    private static QuestionRevision withOptions(QuestionRevision question, List<QuestionRevision.Option> options) {
        return new QuestionRevision(question.id(), question.questionId(), question.schemaVersion(), question.type(),
                question.difficulty(), question.questionText(), question.content(), question.hint(), question.explanation(),
                question.attachmentUrl(), question.attachmentAssetId(), question.createdAt(), options);
    }

    private MapSqlParameterSource questionParameters(QuestionRevision question) {
        return new MapSqlParameterSource().addValue("id", bytes(question.id()))
                .addValue("questionId", bytes(question.questionId())).addValue("version", question.schemaVersion())
                .addValue("type", question.type().name()).addValue("difficulty", question.difficulty().name())
                .addValue("text", question.questionText()).addValue("content", question.content())
                .addValue("hint", question.hint()).addValue("explanation", question.explanation())
                .addValue("url", question.attachmentUrl()).addValue("assetId", bytes(question.attachmentAssetId()))
                .addValue("created", Timestamp.from(question.createdAt()));
    }

    private void batch(String sql, List<MapSqlParameterSource> rows) {
        for (int start = 0; start < rows.size(); start += SQL_BATCH) {
            jdbc.batchUpdate(sql, rows.subList(start, Math.min(start + SQL_BATCH, rows.size()))
                    .toArray(MapSqlParameterSource[]::new));
        }
    }

    private static List<byte[]> binaryIds(List<UUID> ids) {
        return ids.stream().map(JdbcQuizRevisionRepository::bytes).toList();
    }

    private static byte[] bytes(UUID id) {
        return id == null ? null : ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        byte[] value = rs.getBytes(column);
        if (value == null) return null;
        if (value.length != 16) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record ItemRow(UUID rootId, UUID id, int position, QuestionRevision question) { }
}
