package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcQuizPublicationRepository implements QuizPublicationRepository {
    private final NamedParameterJdbcTemplate sql;
    private static final String REVISION = """
            BIN_TO_UUID(p.revision_id) revision_id, BIN_TO_UUID(p.quiz_id) quiz_id,
            BIN_TO_UUID(p.published_by) published_by, p.canonical_version, p.content_hash, p.published_at
            """;
    public JdbcQuizPublicationRepository(NamedParameterJdbcTemplate sql) { this.sql = sql; }

    @Override public Optional<Actor> actor(String principal) {
        UUID id;
        try { id = UUID.fromString(principal); } catch (IllegalArgumentException failure) { id = null; }
        var parameters = new MapSqlParameterSource("principal", principal).addValue("id", id == null ? null : id.toString());
        var users = sql.query("""
                SELECT BIN_TO_UUID(user_id) id FROM users
                WHERE (username=:principal OR email=:principal OR user_id=UUID_TO_BIN(:id))
                  AND is_active=TRUE AND is_deleted=FALSE FOR SHARE
                """, parameters, (rs, row) -> uuid(rs, "id"));
        if (users.size() != 1) return Optional.empty();
        // Lock membership and permission rows through commit; use current SQL, never cached User.roles.
        var names = sql.query("""
                SELECT p.permission_name FROM user_roles ur
                JOIN role_permissions rp ON rp.role_id=ur.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE ur.user_id=UUID_TO_BIN(:id) FOR SHARE
                """, Map.of("id", users.get(0).toString()), (rs, row) -> rs.getString(1));
        return Optional.of(new Actor(users.get(0), new HashSet<>(names)));
    }

    @Override public Optional<Draft> draft(UUID quizId, boolean exclusive) {
        return sql.query("""
                SELECT BIN_TO_UUID(quiz_id) id, BIN_TO_UUID(creator_id) owner_id, title, description,
                       visibility, estimated_time_min FROM quizzes
                WHERE quiz_id=UUID_TO_BIN(:quiz) AND is_deleted=FALSE
                """ + (exclusive ? " FOR UPDATE" : " FOR SHARE"), ids(quizId), (rs, row) ->
                new Draft(uuid(rs, "id"), uuid(rs, "owner_id"), rs.getString("title"),
                        rs.getString("description"), rs.getString("visibility"), rs.getInt("estimated_time_min")))
                .stream().findFirst();
    }

    @Override public List<Question> lockQuestions(UUID quizId) {
        var members = sql.query("""
                SELECT BIN_TO_UUID(question_id) id FROM quiz_questions
                WHERE quiz_id=UUID_TO_BIN(:quiz) ORDER BY question_id FOR UPDATE
                """, ids(quizId), (rs, row) -> uuid(rs, "id"));
        List<Question> result = new ArrayList<>();
        for (int start = 0; start < members.size(); start += 500) {
            var part = members.subList(start, Math.min(start + 500, members.size())).stream().map(id ->
                    java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()).toList();
            result.addAll(sql.query("""
                    SELECT BIN_TO_UUID(id) id, type, difficulty, question, content, hint, explanation,
                           attachment_url, BIN_TO_UUID(attachment_asset_id) attachment_asset_id
                    FROM questions WHERE id IN (:ids) AND is_deleted=FALSE ORDER BY id FOR UPDATE
                    """, Map.of("ids", part), (rs, row) -> new Question(uuid(rs, "id"),
                    QuestionType.valueOf(rs.getString("type")), Difficulty.valueOf(rs.getString("difficulty")),
                    rs.getString("question"), rs.getString("content"), rs.getString("hint"),
                    rs.getString("explanation"), rs.getString("attachment_url"), uuid(rs, "attachment_asset_id"))));
        }
        return result;
    }

    @Override public Optional<Current> current(UUID quizId) {
        return sql.query("SELECT h.publication_version, " + REVISION + """
                 FROM quiz_publication_heads h JOIN quiz_publications p ON p.revision_id=h.revision_id
                 WHERE h.quiz_id=UUID_TO_BIN(:quiz)
                """, ids(quizId), (rs, row) -> new Current(rs.getLong("publication_version"), revision(rs))).stream().findFirst();
    }

    @Override public Optional<Receipt> receipt(UUID quizId, UUID actorId, UUID commandId) {
        return sql.query("SELECT r.request_hash, r.publication_version, " + REVISION + """
                 FROM quiz_publication_receipts r JOIN quiz_publications p ON p.revision_id=r.revision_id
                 WHERE r.quiz_id=UUID_TO_BIN(:quiz) AND r.actor_id=UUID_TO_BIN(:actor) AND r.command_id=UUID_TO_BIN(:command)
                """, ids(quizId).addValue("actor", actorId.toString()).addValue("command", commandId.toString()),
                (rs, row) -> new Receipt(rs.getString("request_hash"), new Current(rs.getLong("publication_version"), revision(rs))))
                .stream().findFirst();
    }

    @Override public Optional<Revision> findContent(UUID quizId, String hash) {
        return sql.query("SELECT " + REVISION + " FROM quiz_publications p WHERE p.quiz_id=UUID_TO_BIN(:quiz) AND p.content_hash=:hash AND p.canonical_version=1",
                ids(quizId).addValue("hash", hash), (rs, row) -> revision(rs)).stream().findFirst();
    }

    @Override public Optional<Revision> revision(UUID quizId, UUID revisionId) {
        return sql.query("SELECT " + REVISION + " FROM quiz_publications p WHERE p.quiz_id=UUID_TO_BIN(:quiz) AND p.revision_id=UUID_TO_BIN(:revision)",
                ids(quizId).addValue("revision", revisionId.toString()), (rs, row) -> revision(rs)).stream().findFirst();
    }

    @Override public void insert(Revision revision, List<Image> images) {
        sql.update("""
                INSERT INTO quiz_publications (revision_id, quiz_id, published_by, canonical_version, content_hash, published_at)
                VALUES (UUID_TO_BIN(:revision), UUID_TO_BIN(:quiz), UUID_TO_BIN(:actor), :format, :hash, :time)
                """, ids(revision.quizId()).addValue("revision", revision.id().toString())
                .addValue("actor", revision.publishedBy().toString()).addValue("format", revision.canonicalVersion())
                .addValue("hash", revision.contentHash()).addValue("time", Timestamp.from(revision.publishedAt())));
        for (int start = 0; start < images.size(); start += 500) {
            var parameters = images.subList(start, Math.min(start + 500, images.size())).stream().map(image ->
                    new MapSqlParameterSource("quiz", revision.id().toString()).addValue("question", image.questionRevisionId().toString())
                            .addValue("asset", image.assetId().toString()).addValue("binding", image.bindingId().toString())
                            .addValue("version", image.versionId().toString())).toArray(MapSqlParameterSource[]::new);
            sql.batchUpdate("""
                    INSERT INTO quiz_revision_images (quiz_revision_id, question_revision_id, asset_id, binding_id, image_version_id)
                    VALUES (UUID_TO_BIN(:quiz), UUID_TO_BIN(:question), UUID_TO_BIN(:asset), UUID_TO_BIN(:binding), UUID_TO_BIN(:version))
                    """, parameters);
        }
    }

    @Override public void pointTo(UUID quizId, Current publication) {
        sql.update("""
                INSERT INTO quiz_publication_heads (quiz_id, revision_id, publication_version)
                VALUES (UUID_TO_BIN(:quiz), UUID_TO_BIN(:revision), :version)
                ON DUPLICATE KEY UPDATE revision_id=VALUES(revision_id), publication_version=VALUES(publication_version)
                """, ids(quizId).addValue("revision", publication.revision().id().toString()).addValue("version", publication.version()));
    }

    @Override public void record(Command command, UUID actorId, String hash, Current publication, Instant recordedAt) {
        sql.update("""
                INSERT INTO quiz_publication_receipts (quiz_id, actor_id, command_id, request_hash, revision_id, publication_version, recorded_at)
                VALUES (UUID_TO_BIN(:quiz), UUID_TO_BIN(:actor), UUID_TO_BIN(:command), :hash, UUID_TO_BIN(:revision), :version, :time)
                """, ids(command.quizId()).addValue("actor", actorId.toString()).addValue("command", command.commandId().toString())
                .addValue("hash", hash).addValue("revision", publication.revision().id().toString())
                .addValue("version", publication.version()).addValue("time", Timestamp.from(recordedAt)));
    }

    @Override public Page<Revision> history(UUID quizId, int page, int size) {
        var parameters = ids(quizId).addValue("size", size).addValue("offset", (long) page * size);
        var content = sql.query("SELECT " + REVISION + """
                 FROM quiz_publications p WHERE p.quiz_id=UUID_TO_BIN(:quiz)
                 ORDER BY p.published_at DESC, p.revision_id DESC LIMIT :size OFFSET :offset
                """, parameters, (rs, row) -> revision(rs));
        Long count = sql.queryForObject("SELECT COUNT(*) FROM quiz_publications WHERE quiz_id=UUID_TO_BIN(:quiz)", parameters, Long.class);
        return new PageImpl<>(content, PageRequest.of(page, size), Objects.requireNonNull(count));
    }

    @Override public List<Image> images(UUID revisionId) {
        return sql.query("""
                SELECT BIN_TO_UUID(i.question_revision_id) question_id, BIN_TO_UUID(i.asset_id) asset_id,
                       BIN_TO_UUID(i.binding_id) binding_id, BIN_TO_UUID(i.image_version_id) version_id,
                       v.object_key, v.mime_type, v.size_bytes, v.width, v.height
                FROM quiz_revision_images i LEFT JOIN saved_image_versions v ON v.id=i.image_version_id AND v.ready=TRUE
                WHERE i.quiz_revision_id=UUID_TO_BIN(:quiz) ORDER BY i.question_revision_id, i.asset_id
                """, ids(revisionId), (rs, row) -> new Image(uuid(rs, "question_id"), uuid(rs, "asset_id"),
                uuid(rs, "binding_id"), uuid(rs, "version_id"), rs.getString("object_key"), rs.getString("mime_type"),
                rs.getLong("size_bytes"), rs.getInt("width"), rs.getInt("height")));
    }

    private static Revision revision(ResultSet rs) throws SQLException {
        return new Revision(uuid(rs, "revision_id"), uuid(rs, "quiz_id"), uuid(rs, "published_by"),
                rs.getInt("canonical_version"), rs.getString("content_hash"), rs.getTimestamp("published_at").toInstant());
    }
    private static MapSqlParameterSource ids(UUID quizId) { return new MapSqlParameterSource("quiz", quizId.toString()); }
    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        String value = rs.getString(name); return value == null ? null : UUID.fromString(value);
    }
}
