package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcQuestionSourceScopeRepository implements QuestionSourceScopeRepository {
    private final NamedParameterJdbcTemplate sql;

    public JdbcQuestionSourceScopeRepository(NamedParameterJdbcTemplate sql) { this.sql = sql; }

    @Override public Set<String> lockPermissionNames(UUID actorId) {
        return Set.copyOf(sql.query("""
                SELECT p.permission_name FROM users u JOIN user_roles ur ON ur.user_id=u.user_id
                JOIN role_permissions rp ON rp.role_id=ur.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE u.user_id=UUID_TO_BIN(:actor) AND u.is_active=TRUE AND u.is_deleted=FALSE FOR SHARE
                """, Map.of("actor", actorId.toString()), (rs, row) -> rs.getString(1)));
    }

    @Override public List<SourceScope> lockSources(Collection<UUID> questionIds) {
        return sources(questionIds, " FOR SHARE");
    }

    @Override public List<SourceScope> lockForConfirmation(Collection<UUID> questionIds) {
        return sources(questionIds, " FOR UPDATE");
    }

    private List<SourceScope> sources(Collection<UUID> questionIds, String lock) {
        var ids = questionIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        List<SourceScope> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 500) {
            var batch = ids.subList(start, Math.min(start + 500, ids.size())).stream().map(id ->
                    ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()).toList();
            // Only question rows: never acquire another quiz's lock after locking shared questions.
            // Current SQL also bypasses RR snapshots and stale managed JPA entities.
            result.addAll(sql.query("""
                    SELECT BIN_TO_UUID(q.id) question_id, BIN_TO_UUID(q.source_owner_id) owner_id
                    FROM questions q
                    WHERE q.id IN (:ids) AND q.is_deleted=FALSE
                    ORDER BY q.id
                    """ + lock, Map.of("ids", batch), (rs, row) -> new SourceScope(UUID.fromString(rs.getString("question_id")),
                    uuid(rs.getString("owner_id")))));
        }
        return List.copyOf(result);
    }

    @Override public Optional<UUID> lockActor(String principal) {
        UUID id;
        try { id = UUID.fromString(principal); } catch (IllegalArgumentException invalid) { id = null; }
        var parameters = new MapSqlParameterSource("principal", principal)
                .addValue("id", id == null ? null : id.toString());
        var actors = sql.query("""
                SELECT BIN_TO_UUID(user_id) FROM users
                WHERE (username=:principal OR email=:principal OR user_id=UUID_TO_BIN(:id))
                  AND is_active=TRUE AND is_deleted=FALSE FOR SHARE
                """, parameters, (rs, row) -> UUID.fromString(rs.getString(1)));
        return actors.size() == 1 ? Optional.of(actors.get(0)) : Optional.empty();
    }

    @Override public boolean lockActiveOwner(UUID ownerId) {
        return !sql.query("""
                SELECT user_id FROM users WHERE user_id=UUID_TO_BIN(:owner)
                AND is_active=TRUE AND is_deleted=FALSE FOR SHARE
                """, Map.of("owner", ownerId.toString()), (rs, row) -> true).isEmpty();
    }

    @Override public void confirmLegacy(UUID questionId, UUID ownerId, UUID moderatorId, Instant at, String evidence) {
        var parameters = new MapSqlParameterSource("question", questionId.toString())
                .addValue("owner", ownerId.toString()).addValue("moderator", moderatorId.toString())
                .addValue("at", Timestamp.from(at)).addValue("evidence", evidence);
        if (sql.update("""
                UPDATE questions SET source_owner_id=UUID_TO_BIN(:owner)
                WHERE id=UUID_TO_BIN(:question) AND source_owner_id IS NULL AND is_deleted=FALSE
                """, parameters) != 1) throw new IllegalStateException("Question source confirmation conflicted");
        sql.update("""
                INSERT INTO question_source_confirmations(question_id, owner_id, confirmed_by, confirmed_at, evidence)
                VALUES(UUID_TO_BIN(:question), UUID_TO_BIN(:owner), UUID_TO_BIN(:moderator), :at, :evidence)
                """, parameters);
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
