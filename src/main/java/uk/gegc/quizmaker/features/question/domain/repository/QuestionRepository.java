package uk.gegc.quizmaker.features.question.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.question.domain.model.Question;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {
    Page<Question> findAllByQuizId_Id(UUID quizId, Pageable page);
    
    Page<Question> findAllByQuizId_IdIn(List<UUID> quizIds, Pageable page);
    
    // Methods for validating question-quiz membership from Question side
    boolean existsByIdAndQuizId_Id(UUID questionId, UUID quizId);
    
    long countByQuizId_Id(UUID quizId);

    /**
     * Batch fetch question counts for multiple quizzes to avoid N+1 queries.
     * Returns a map of quizId -> questionCount.
     */
    @Query("""
            SELECT q.quiz.id as quizId, COUNT(q) as count
            FROM Question q
            WHERE q.quiz.id IN :quizIds
            GROUP BY q.quiz.id
            """)
    List<Object[]> countQuestionsByQuizIds(@Param("quizIds") List<UUID> quizIds);
    
    /**
     * Batch fetch question counts for multiple quizzes to avoid N+1 queries.
     * Returns a map of quizId -> questionCount.
     */
    @Query("""
            SELECT q.id, COUNT(qq.id)
            FROM Quiz q
            LEFT JOIN q.questions qq
            WHERE q.id IN :quizIds
            GROUP BY q.id
            """)
    List<Object[]> countQuestionsForQuizzes(@Param("quizIds") List<UUID> quizIds);
    
    List<Question> findAllByQuizId_IdOrderById(UUID quizId);
}
