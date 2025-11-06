package uk.gegc.quizmaker.features.attempt.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @Query(value = """
            SELECT a
            FROM Attempt a
            JOIN FETCH a.quiz q
            JOIN FETCH a.user u
            WHERE (:quizId IS NULL OR q.id = :quizId)
              AND (:userId IS NULL OR u.id = :userId)
            """,
            countQuery = """
                    SELECT COUNT(a)
                    FROM Attempt a
                    WHERE (:quizId IS NULL OR a.quiz.id = :quizId)
                      AND (:userId IS NULL OR a.user.id = :userId)
                    """
    )
    Page<Attempt> findAllByQuizAndUserEager(
            @Param("quizId") UUID quizId,
            @Param("userId") UUID userId,
            Pageable pageable
    );

    @Query("""
            SELECT a
            FROM Attempt a
            LEFT JOIN FETCH a.answers ans
            LEFT JOIN FETCH ans.question q
            WHERE a.id = :id
            """)
    Optional<Attempt> findByIdWithAnswersAndQuestion(@Param("id") UUID id);

    @Query("""
            SELECT DISTINCT a
            FROM Attempt a
            LEFT JOIN FETCH a.answers ans
            LEFT JOIN FETCH ans.question q
            LEFT JOIN FETCH a.quiz quiz
            LEFT JOIN FETCH quiz.questions qlist
            WHERE a.id = :id
            """)
    Optional<Attempt> findByIdWithAllRelations(@Param("id") UUID id);

    @Query("""
            SELECT COUNT(a), AVG(a.totalScore), MAX(a.totalScore), MIN(a.totalScore)
            FROM Attempt a
            WHERE a.quiz.id = :quizId
              AND a.status = 'COMPLETED'
            """)
    List<Object[]> getAttemptAggregateData(@Param("quizId") UUID quizId);

    List<Attempt> findByQuiz_Id(UUID quizId);

    @Query("""
            SELECT DISTINCT a
            FROM Attempt a
            LEFT JOIN FETCH a.answers ans
            LEFT JOIN FETCH ans.question q
            WHERE a.quiz.id = :quizId
              AND a.status = 'COMPLETED'
            """)
    List<Attempt> findCompletedWithAnswersByQuizId(@Param("quizId") UUID quizId);

    /**
     * Load attempt with answers and their questions for answer submission flow.
     * NOTE: Do NOT include "quiz.questions" in attributePaths - causes cartesian product
     * with "answers" collection, resulting in duplicate answers and inflated scores!
     */
    @EntityGraph(attributePaths = {
            "answers",
            "answers.question",
            "quiz"
            // quiz.questions intentionally OMITTED to avoid cartesian product
    })
    @Query("SELECT a FROM Attempt a WHERE a.id = :id")
    Optional<Attempt> findFullyLoadedById(@Param("id") UUID id);

    @Query("""
            SELECT u.id, u.username, MAX(a.totalScore)
            FROM Attempt a
            JOIN a.user u
            WHERE a.quiz.id = :quizId
              AND a.status = 'COMPLETED'
            GROUP BY u.id, u.username
            ORDER BY MAX(a.totalScore) DESC
            """)
    List<Object[]> getLeaderboardData(@Param("quizId") UUID quizId);

    List<Attempt> findByStartedAtBetween(Instant start, Instant end);

    /**
     * Find attempts with quiz eagerly loaded for summary/enriched views.
     * Uses JOIN FETCH to avoid N+1 queries on quiz and category.
     * LEFT JOIN FETCH on category for defensive programming (handles null categories).
     * Answers are NOT fetched here to avoid pagination issues with collection fetch.
     * Use batchFetchAnswersForAttempts() after this to load answers efficiently.
     */
    @Query(value = """
            SELECT DISTINCT a
            FROM Attempt a
            JOIN FETCH a.quiz q
            LEFT JOIN FETCH q.category c
            JOIN FETCH a.user u
            WHERE (:quizId IS NULL OR q.id = :quizId)
              AND (:userId IS NULL OR u.id = :userId)
              AND (:status IS NULL OR a.status = :status)
            """,
            countQuery = """
                    SELECT COUNT(DISTINCT a)
                    FROM Attempt a
                    WHERE (:quizId IS NULL OR a.quiz.id = :quizId)
                      AND (:userId IS NULL OR a.user.id = :userId)
                      AND (:status IS NULL OR a.status = :status)
                    """
    )
    Page<Attempt> findAllWithQuizAndAnswersEager(
            @Param("quizId") UUID quizId,
            @Param("userId") UUID userId,
            @Param("status") AttemptStatus status,
            Pageable pageable
    );

    /**
     * Batch-fetch answers for multiple attempts in a single query.
     * This populates the persistence context so that attempt.getAnswers() doesn't trigger N+1 queries.
     * Call this after fetching a paginated list of attempts.
     */
    @Query("""
            SELECT DISTINCT a
            FROM Attempt a
            LEFT JOIN FETCH a.answers
            WHERE a.id IN :attemptIds
            """)
    List<Attempt> batchFetchAnswersForAttempts(@Param("attemptIds") List<UUID> attemptIds);
}
