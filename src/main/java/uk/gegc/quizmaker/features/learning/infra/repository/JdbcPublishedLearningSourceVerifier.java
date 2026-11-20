package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.learning.application.LearningSessionSourceVerifier;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.util.UUID;

/** First Learning authority: the current author's own actually published revision. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcPublishedLearningSourceVerifier implements LearningSessionSourceVerifier {
    private final JdbcTemplate jdbc;

    public JdbcPublishedLearningSourceVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean allows(UUID trustedLearnerId, Source source) {
        if (trustedLearnerId == null || source == null) return false;
        try {
            // Same effective publication-history permissions as QuizRevisionPublicationService.
            // Only the actual owner qualifies here, including when that owner has elevated rights.
            // Keep one positive permission path and mutable identity/ownership stable through commit.
            return !jdbc.query("""
                    SELECT 1 FROM users u
                    JOIN quizzes q ON q.creator_id=u.user_id AND q.is_deleted=FALSE
                    JOIN quiz_publications p ON p.quiz_id=q.quiz_id AND p.canonical_version=1
                    JOIN quiz_revisions r ON r.id=p.revision_id AND r.quiz_id=q.quiz_id AND r.owner_id=u.user_id
                    JOIN user_roles ur ON ur.user_id=u.user_id
                    JOIN role_permissions rp ON rp.role_id=ur.role_id
                    JOIN permissions perm ON perm.permission_id=rp.permission_id
                    WHERE u.user_id=UUID_TO_BIN(?) AND u.is_active=TRUE AND u.is_deleted=FALSE
                      AND u.username<>'anonymous' AND q.quiz_id=UUID_TO_BIN(?)
                      AND r.id=UUID_TO_BIN(?) AND r.schema_version=?
                      AND perm.permission_name IN ('QUIZ_UPDATE','QUIZ_MODERATE','QUIZ_ADMIN')
                    LIMIT 1 FOR SHARE OF u,q,ur,rp,perm
                    """, (row, index) -> row.getInt(1), trustedLearnerId.toString(), source.quizId().toString(),
                    source.quizRevisionId().toString(), source.revisionSchemaVersion()).isEmpty();
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }
}
