package uk.gegc.quizmaker.features.result.application;

import uk.gegc.quizmaker.features.attempt.domain.event.AttemptCompletedEvent;
import uk.gegc.quizmaker.features.result.domain.model.QuizAnalyticsSnapshot;

import java.util.UUID;

/**
 * Service for managing quiz analytics snapshots.
 * <p>
 * Provides methods to compute, retrieve, and update analytics snapshots for quizzes.
 * Snapshots are updated event-driven when attempts are completed, with fallback
 * recomputation for consistency.
 * </p>
 */
public interface QuizAnalyticsService {

    /**
     * Recompute and persist the analytics snapshot for a quiz.
     * <p>
     * Aggregates data from all completed attempts for the quiz and saves/updates
     * the snapshot. This is the authoritative computation method, used for
     * event-driven updates and scheduled refreshes.
     * </p>
     *
     * @param quizId the quiz ID
     * @return the updated snapshot
     */
    QuizAnalyticsSnapshot recomputeSnapshot(UUID quizId);

    /**
     * Get the analytics snapshot for a quiz, computing it if missing or stale.
     * <p>
     * Returns the existing snapshot if it exists; otherwise triggers a recomputation.
     * Future enhancement: add a "staleness threshold" parameter to recompute if
     * updatedAt is older than X minutes.
     * </p>
     *
     * @param quizId the quiz ID
     * @return the snapshot (existing or newly computed)
     */
    QuizAnalyticsSnapshot getOrComputeSnapshot(UUID quizId);

    /**
     * Handle attempt completion event by updating the quiz analytics snapshot.
     * <p>
     * This method is called when an {@link AttemptCompletedEvent} is published.
     * It triggers a recomputation of the snapshot for the quiz associated with
     * the completed attempt.
     * </p>
     *
     * @param event the attempt completed event
     */
    void handleAttemptCompleted(AttemptCompletedEvent event);
}

