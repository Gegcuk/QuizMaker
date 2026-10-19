package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizOwnerFreshness;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface QuizOwnerFreshnessRepository extends JpaRepository<QuizOwnerFreshness, UUID> {
    // Flush the new parent quiz before the FK-constrained native insert.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO quiz_owner_freshness (quiz_id, is_new) VALUES (:quizId, true)
            ON DUPLICATE KEY UPDATE quiz_id = quiz_owner_freshness.quiz_id
            """, nativeQuery = true)
    void registerGeneratedQuiz(@Param("quizId") UUID quizId);

    @Modifying
    @Query("update QuizOwnerFreshness f set f.isNew = false where f.quizId = :quizId and f.isNew = true")
    int acknowledge(@Param("quizId") UUID quizId);

    @Query("""
            select f.quizId from QuizOwnerFreshness f, Quiz q
            where f.quizId = q.id and q.creator.id = :ownerId
              and f.isNew = true and f.quizId in :quizIds
            """)
    Set<UUID> findNewQuizIds(@Param("ownerId") UUID ownerId, @Param("quizIds") Collection<UUID> quizIds);
}
