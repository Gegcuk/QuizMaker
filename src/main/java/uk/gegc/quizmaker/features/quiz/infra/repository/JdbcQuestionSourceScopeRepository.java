package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository;

import java.nio.ByteBuffer;
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
        var ids = questionIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        List<SourceScope> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 500) {
            var batch = ids.subList(start, Math.min(start + 500, ids.size())).stream().map(id ->
                    ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()).toList();
            // Current locking reads bypass both an earlier RR snapshot and managed JPA collections.
            // Do not hide deleted source quizzes: deletion must not turn a foreign question into owned content.
            result.addAll(sql.query("""
                    SELECT BIN_TO_UUID(q.id) question_id, BIN_TO_UUID(qq.quiz_id) quiz_id,
                           BIN_TO_UUID(source.creator_id) owner_id
                    FROM questions q LEFT JOIN quiz_questions qq ON qq.question_id=q.id
                    LEFT JOIN quizzes source ON source.quiz_id=qq.quiz_id
                    WHERE q.id IN (:ids) AND q.is_deleted=FALSE
                    ORDER BY q.id, qq.quiz_id FOR SHARE
                    """, Map.of("ids", batch), (rs, row) -> new SourceScope(UUID.fromString(rs.getString("question_id")),
                    uuid(rs.getString("quiz_id")), uuid(rs.getString("owner_id")))));
        }
        return List.copyOf(result);
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
