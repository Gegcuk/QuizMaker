package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationWork;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface QuizGenerationWorkRepository extends JpaRepository<QuizGenerationWork, UUID> {
    @Query("select count(w) from QuizGenerationWork w where w.state <> 'DONE'")
    long countOutstanding();

    @Query("select min(w.createdAt) from QuizGenerationWork w where w.state <> 'DONE'")
    LocalDateTime findOldestOutstanding();

    @Query("""
            select w.jobId from QuizGenerationWork w
            where w.state = 'READY' or (w.state = 'RUNNING' and w.leaseUntil <= :now)
            order by w.createdAt, w.jobId
            """)
    List<UUID> findDue(LocalDateTime now, Pageable pageable);
}
