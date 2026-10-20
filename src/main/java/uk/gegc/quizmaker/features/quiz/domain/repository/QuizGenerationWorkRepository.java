package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationAdmission;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationWork;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface QuizGenerationWorkRepository extends JpaRepository<QuizGenerationWork, UUID> {
    // Also supports Hibernate-created integration-test schemas. The production migration seeds it.
    @Modifying
    @Query(value = "INSERT INTO quiz_generation_admission (id, next_sequence) VALUES (1, 1) ON DUPLICATE KEY UPDATE id = 1", nativeQuery = true)
    void initializeAdmission();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from QuizGenerationAdmission a where a.id = 1")
    QuizGenerationAdmission lockAdmission();

    @Query("select count(w) from QuizGenerationWork w where w.state = 'RUNNING' and w.leaseUntil > :now")
    long countRunning(LocalDateTime now);

    @Query("select count(w) from QuizGenerationWork w where w.state = 'READY' or (w.state = 'RUNNING' and w.leaseUntil <= :now)")
    long countWaiting(LocalDateTime now);

    @Query("select count(w) from QuizGenerationWork w where w.state <> 'DONE'")
    long countOutstanding();

    @Query("select min(w.createdAt) from QuizGenerationWork w where w.state <> 'DONE'")
    LocalDateTime findOldestOutstanding();

    @Query("""
            select w.jobId from QuizGenerationWork w
            where w.state = 'READY' or (w.state = 'RUNNING' and w.leaseUntil <= :now)
            order by w.admissionSequence, w.createdAt, w.jobId
            """)
    List<UUID> findDue(LocalDateTime now, Pageable pageable);
}
