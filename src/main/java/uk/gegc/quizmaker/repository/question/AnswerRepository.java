package uk.gegc.quizmaker.repository.question;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.model.question.Answer;

import java.util.UUID;

public interface AnswerRepository extends JpaRepository<Answer, UUID> {
    
    @Query("SELECT COUNT(a) FROM Answer a WHERE a.attempt.id = :attemptId")
    long countByAttemptId(@Param("attemptId") UUID attemptId);
}
