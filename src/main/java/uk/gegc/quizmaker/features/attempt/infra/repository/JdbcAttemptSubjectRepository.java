package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubjectRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAttemptSubjectRepository implements AttemptSubjectRepository {
    private static final String SELECT = "SELECT attempt_id,schema_version,subject_type,user_id FROM attempt_participant_subject WHERE attempt_id=:attempt";
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAttemptSubjectRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public AttemptSubject bindOrReplay(UUID trustedUserId, AttemptSubject subject) {
        if (trustedUserId == null || subject == null || !trustedUserId.equals(subject.userId())) {
            throw new AttemptSubjectException(SUBJECT_UNAVAILABLE);
        }
        var parameters = new MapSqlParameterSource("attempt", bytes(subject.attemptId()))
                .addValue("user", bytes(trustedUserId)).addValue("version", subject.schemaVersion())
                .addValue("type", subject.type().name());
        // Lock eligibility through commit so deactivation/deletion cannot race a fresh binding.
        // The reserved legacy sentinel must never be promoted into an authenticated identity.
        var eligible = jdbc.query("""
                SELECT user_id FROM users WHERE user_id=:user AND is_active=TRUE AND is_deleted=FALSE
                AND username <> 'anonymous' FOR SHARE
                """, parameters, (row, index) -> row.getBytes("user_id"));
        if (eligible.isEmpty()) throw new AttemptSubjectException(SUBJECT_UNAVAILABLE);
        // A no-op duplicate update serializes first writers without catching a failed flush,
        // mutating the winner, or taking gap locks on a preliminary missing-row read.
        jdbc.update("""
                INSERT INTO attempt_participant_subject (attempt_id,schema_version,subject_type,user_id)
                VALUES (:attempt,:version,:type,:user)
                ON DUPLICATE KEY UPDATE attempt_id=attempt_participant_subject.attempt_id
                """, parameters);
        var saved = jdbc.query(SELECT + " FOR UPDATE", parameters, (row, index) -> subject(row)).get(0);
        if (!saved.equals(subject)) throw new AttemptSubjectException(SUBJECT_CONFLICT);
        return saved;
    }

    @Override
    public AttemptSubjectLookup read(UUID attemptId) {
        if (attemptId == null) throw new AttemptSubjectException(INVALID_SUBJECT);
        var rows = jdbc.query(SELECT, new MapSqlParameterSource("attempt", bytes(attemptId)),
                (row, index) -> subject(row));
        return rows.isEmpty() ? new AttemptSubjectLookup.LegacyOrUnbound() : new AttemptSubjectLookup.Bound(rows.get(0));
    }

    private static AttemptSubject subject(ResultSet row) throws SQLException {
        int version = row.getInt("schema_version");
        if (version != 1) throw new AttemptSubjectException(UNSUPPORTED_VERSION);
        if (!"AUTHENTICATED_USER".equals(row.getString("subject_type"))) throw new AttemptSubjectException(UNSUPPORTED_TYPE);
        return new AttemptSubject(uuid(row, "attempt_id"), version, AttemptSubject.Type.AUTHENTICATED_USER, uuid(row, "user_id"));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] value = row.getBytes(column);
        if (value == null || value.length != 16) throw new AttemptSubjectException(INVALID_STORED_SUBJECT);
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
