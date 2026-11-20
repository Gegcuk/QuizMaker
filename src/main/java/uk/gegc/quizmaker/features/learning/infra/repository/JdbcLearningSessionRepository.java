package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningSessionRepository;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningSessionRepository implements LearningSessionRepository {
    private static final String SELECT = """
            SELECT s.*,r.quiz_id AS retained_quiz_id,r.schema_version AS retained_schema_version
            FROM learning_session s JOIN quiz_revisions r ON r.id=s.quiz_revision_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final FsrsStateFormat format = new FsrsStateFormat();

    public JdbcLearningSessionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean sourceExists(LearningSession.Source source) {
        return safe(() -> !jdbc.query("""
                SELECT id FROM quiz_revisions WHERE id=:revision AND quiz_id=:quiz AND schema_version=:version
                """, new MapSqlParameterSource("revision", bytes(source.quizRevisionId()))
                .addValue("quiz", bytes(source.quizId())).addValue("version", source.revisionSchemaVersion()),
                (row, index) -> row.getBytes(1)).isEmpty());
    }

    @Override public boolean creationExists(UUID learnerId, UUID commandId) {
        return safe(() -> !jdbc.query("""
                SELECT 1 FROM learning_session WHERE learner_id=:learner AND create_command_id=:command
                """, new MapSqlParameterSource("learner", bytes(learnerId)).addValue("command", bytes(commandId)),
                (row, index) -> row.getInt(1)).isEmpty());
    }

    @Override public LearningSession insertOrRead(LearningSession candidate) {
        return safe(() -> {
            var parameters = parameters(candidate);
            // The unique learner/command key waits for a competing first creator. No missing-row gap
            // lock, overwrite, catch-and-continue transaction recovery or insert-then-delete loser.
            jdbc.update("""
                INSERT INTO learning_session
                    (id,schema_version,learner_id,create_command_id,source_kind,source_version,quiz_id,
                     quiz_revision_id,source_schema_version,strategy_id,configuration,status,version,created_at,updated_at)
                VALUES (:id,1,:learner,:command,'QUIZ_REVISION',:sourceVersion,:quiz,:revision,:sourceSchema,
                        'fsrs/v1',:configuration,'CREATED',1,:created,:created)
                ON DUPLICATE KEY UPDATE id=learning_session.id
                """, parameters);
            var rows = jdbc.query(SELECT + " WHERE s.learner_id=:learner AND s.create_command_id=:command"
                        + " FOR UPDATE OF s FOR SHARE OF r",
                parameters, (row, index) -> session(row));
            require(rows.size() == 1, CORRUPT);
            return rows.get(0);
        });
    }

    @Override public Optional<LearningSession> find(UUID trustedLearnerId, UUID sessionId) {
        return safe(() -> jdbc.query(SELECT + " WHERE s.id=:id AND s.learner_id=:learner",
                new MapSqlParameterSource("id", bytes(sessionId)).addValue("learner", bytes(trustedLearnerId)),
                (row, index) -> session(row)).stream().findFirst());
    }

    @Override public void recordCreation(LearningSession session) {
        safe(() -> {
            jdbc.update("""
                INSERT INTO learning_session_create_receipt
                    (session_id,schema_version,learner_id,command_id,recorded_at)
                VALUES (:id,1,:learner,:command,:created)
                """, parameters(session));
            return null;
        });
    }

    @Override public boolean hasCreationReceipt(LearningSession session) {
        return safe(() -> !jdbc.query("""
                SELECT schema_version,session_id,recorded_at FROM learning_session_create_receipt
                WHERE learner_id=:learner AND command_id=:command
                """, parameters(session), (row, index) -> {
                    require(row.getInt("schema_version") == 1, UNSUPPORTED_VERSION);
                    require(session.id().equals(uuid(row, "session_id"))
                            && session.createdAt().equals(row.getObject("recorded_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)), CORRUPT);
                    return true;
                }).isEmpty());
    }

    /** Redact before this target method exits through its MANDATORY transaction interceptor. */
    private static <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }

    private MapSqlParameterSource parameters(LearningSession session) {
        return new MapSqlParameterSource("id", bytes(session.id())).addValue("learner", bytes(session.learnerId()))
                .addValue("command", bytes(session.createCommandId())).addValue("sourceVersion", session.source().version())
                .addValue("quiz", bytes(session.source().quizId())).addValue("revision", bytes(session.source().quizRevisionId()))
                .addValue("sourceSchema", session.source().revisionSchemaVersion())
                .addValue("configuration", format.encodeManifest(session.configuration()))
                .addValue("created", LocalDateTime.ofInstant(session.createdAt(), ZoneOffset.UTC));
    }

    private LearningSession session(ResultSet row) throws SQLException {
        require(row.getInt("schema_version") == 1, UNSUPPORTED_VERSION);
        require("QUIZ_REVISION".equals(row.getString("source_kind"))
                && "fsrs/v1".equals(row.getString("strategy_id"))
                && "CREATED".equals(row.getString("status")), UNSUPPORTED_VERSION);
        var source = new LearningSession.Source(row.getInt("source_version"), uuid(row, "quiz_id"),
                uuid(row, "quiz_revision_id"), row.getInt("source_schema_version"));
        require(source.quizId().equals(uuid(row, "retained_quiz_id"))
                && source.revisionSchemaVersion() == row.getInt("retained_schema_version"), CORRUPT);
        try {
            return new LearningSession(uuid(row, "id"), 1, uuid(row, "learner_id"), uuid(row, "create_command_id"),
                    source, format.decodeManifest(row.getString("configuration")), LearningSession.Status.CREATED,
                    row.getLong("version"), row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        } catch (uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException failure) {
            throw new LearningSessionException(CORRUPT);
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] value = row.getBytes(column);
        require(value != null && value.length == 16, CORRUPT);
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
