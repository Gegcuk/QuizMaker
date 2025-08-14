package uk.gegc.quizmaker.features.question.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.question.domain.model.Answer;

import java.util.UUID;

@Repository
public interface AnswerRepository extends JpaRepository<Answer, UUID> {
    
    @Query("SELECT COUNT(a) FROM Answer a WHERE a.attempt.id = :attemptId")
    long countByAttemptId(@Param("attemptId") UUID attemptId);
    
    @Modifying
    @Query("DELETE FROM Answer a WHERE a.attempt.id = :attemptId")
    void deleteByAttemptId(@Param("attemptId") UUID attemptId);
}
