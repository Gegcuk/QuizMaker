package uk.gegc.quizmaker.features.question.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.question.domain.model.Answer;

import java.util.UUID;
import java.util.List;

@Repository
public interface AnswerRepository extends JpaRepository<Answer, UUID> {
    
    @Query("SELECT COUNT(a) FROM Answer a WHERE a.attempt.id = :attemptId")
    long countByAttemptId(@Param("attemptId") UUID attemptId);

    /** Current read, not the caller's earlier REPEATABLE_READ snapshot. Root attempt is locked first. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Answer a WHERE a.attempt.id = :attemptId ORDER BY a.id")
    List<Answer> findCurrentByAttemptIdForUpdate(@Param("attemptId") UUID attemptId);
    
    @Modifying
    @Query("DELETE FROM Answer a WHERE a.attempt.id = :attemptId")
    void deleteByAttemptId(@Param("attemptId") UUID attemptId);
}
