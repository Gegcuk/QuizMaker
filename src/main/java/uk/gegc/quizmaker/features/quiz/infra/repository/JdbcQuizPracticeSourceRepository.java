package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPracticeSourceRepository;

import java.util.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcQuizPracticeSourceRepository implements QuizPracticeSourceRepository {
    private final NamedParameterJdbcTemplate sql;
    public JdbcQuizPracticeSourceRepository(NamedParameterJdbcTemplate sql) { this.sql = sql; }

    @Override public boolean lockActiveUser(UUID id) {
        return !sql.query("""
                SELECT user_id FROM users WHERE user_id=UUID_TO_BIN(:id)
                  AND is_active=TRUE AND is_deleted=FALSE AND username <> 'anonymous' FOR SHARE
                """, Map.of("id", id.toString()), (rs, row) -> rs.getBytes(1)).isEmpty();
    }
    @Override public Set<String> lockPermissions(UUID id) {
        return new HashSet<>(sql.query("""
                SELECT p.permission_name FROM user_roles ur
                JOIN role_permissions rp ON rp.role_id=ur.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE ur.user_id=UUID_TO_BIN(:id) FOR SHARE
                """, Map.of("id", id.toString()), (rs, row) -> rs.getString(1)));
    }
    @Override public Optional<Source> lockQuiz(UUID quizId) {
        // Publication takes the same quiz lock exclusively before changing its head. Locking reads
        // also avoid a stale REPEATABLE_READ snapshot when a caller performed earlier reads.
        var parameters = Map.of("id", quizId.toString());
        return sql.query("""
                SELECT BIN_TO_UUID(creator_id) owner_id, is_timer_enabled FROM quizzes
                WHERE quiz_id=UUID_TO_BIN(:id) AND is_deleted=FALSE FOR SHARE
                """, parameters, (rs, row) -> new Source(UUID.fromString(rs.getString(1)),
                (Boolean) rs.getObject(2), null, 0)).stream().findFirst();
    }
    @Override public Optional<Source> lockSource(UUID quizId) {
        var quiz = lockQuiz(quizId);
        if (quiz.isEmpty()) return Optional.empty();
        var head = sql.query("""
                SELECT BIN_TO_UUID(h.revision_id), h.publication_version FROM quiz_publication_heads h
                JOIN quiz_publications p ON p.revision_id=h.revision_id AND p.quiz_id=h.quiz_id
                JOIN quiz_revisions r ON r.id=h.revision_id
                WHERE h.quiz_id=UUID_TO_BIN(:id) AND r.owner_id=UUID_TO_BIN(:owner) FOR SHARE
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", quizId.toString())
                .addValue("owner", quiz.get().ownerId().toString()), (rs, row) ->
                new Source(quiz.get().ownerId(), quiz.get().timerEnabled(), UUID.fromString(rs.getString(1)), rs.getLong(2)));
        return head.stream().findFirst();
    }
}
