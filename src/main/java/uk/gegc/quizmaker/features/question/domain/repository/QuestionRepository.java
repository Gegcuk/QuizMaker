package uk.gegc.quizmaker.features.question.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
    
    List<Question> findAllByQuizId_IdOrderById(UUID quizId);
}
