package uk.gegc.quizmaker.repository.attempt;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.attempt.Attempt;

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
            SELECT a
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

    @EntityGraph(attributePaths = {
            "answers",
            "answers.question",
            "quiz",
            "quiz.questions"
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
}
