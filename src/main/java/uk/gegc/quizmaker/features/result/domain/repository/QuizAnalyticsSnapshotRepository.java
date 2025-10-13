package uk.gegc.quizmaker.features.result.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.result.domain.model.QuizAnalyticsSnapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link QuizAnalyticsSnapshot}.
 * <p>
 * Provides CRUD operations for quiz analytics snapshots. Spring Data JPA automatically
 * implements standard repository methods.
 * </p>
 */
@Repository
public interface QuizAnalyticsSnapshotRepository extends JpaRepository<QuizAnalyticsSnapshot, UUID> {

    /**
     * Find analytics snapshot by quiz ID.
     *
     * @param quizId the quiz ID
     * @return Optional containing the snapshot if found
     */
    Optional<QuizAnalyticsSnapshot> findByQuizId(UUID quizId);
}

