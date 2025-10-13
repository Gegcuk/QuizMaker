package uk.gegc.quizmaker.features.result.application.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import uk.gegc.quizmaker.features.attempt.domain.event.AttemptCompletedEvent;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.result.application.QuizAnalyticsService;
import uk.gegc.quizmaker.features.result.domain.model.QuizAnalyticsSnapshot;
import uk.gegc.quizmaker.features.result.domain.repository.QuizAnalyticsSnapshotRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link QuizAnalyticsService}.
 * <p>
 * Manages quiz analytics snapshots by:
 * <ul>
 *   <li>Listening to {@link AttemptCompletedEvent} and triggering snapshot updates</li>
 *   <li>Recomputing snapshots from raw attempt/answer data</li>
 *   <li>Providing cached snapshots for fast reads</li>
 * </ul>
 * </p>
 * <p>
 * Uses optimistic locking (@Version) to handle concurrent updates safely.
 * </p>
 */
@Slf4j
@Service
public class QuizAnalyticsServiceImpl implements QuizAnalyticsService {

    private final QuizAnalyticsSnapshotRepository snapshotRepository;
    private final AttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final QuizRepository quizRepository;
    
    // Self-reference to call @Transactional(REQUIRES_NEW) methods through proxy
    private final QuizAnalyticsService self;
    
    // Maximum age of snapshot in seconds before recomputation (0 = disabled)
    @Value("${quizmaker.analytics.snapshot.max-age-seconds:600}")
    private long maxAgeSeconds;

    public QuizAnalyticsServiceImpl(
            QuizAnalyticsSnapshotRepository snapshotRepository,
            AttemptRepository attemptRepository,
            QuestionRepository questionRepository,
            QuizRepository quizRepository,
            @Lazy QuizAnalyticsService self
    ) {
        this.snapshotRepository = snapshotRepository;
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
        this.quizRepository = quizRepository;
        this.self = self;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuizAnalyticsSnapshot recomputeSnapshot(UUID quizId) {
        log.debug("Recomputing analytics snapshot for quiz {}", quizId);

        // Note: Quiz existence is enforced by FK constraint on quiz_analytics_snapshot.quiz_id
        // No need for explicit check here - simplifies transaction boundaries

        // Get aggregate data (count, avg, max, min) from completed attempts
        List<Object[]> rows = attemptRepository.getAttemptAggregateData(quizId);
        Object[] agg = rows.isEmpty()
                ? new Object[]{0L, null, null, null}
                : rows.get(0);

        long attemptsCount = ((Number) agg[0]).longValue();
        double averageScore = agg[1] != null ? ((Number) agg[1]).doubleValue() : 0.0;
        double bestScore = agg[2] != null ? ((Number) agg[2]).doubleValue() : 0.0;
        double worstScore = agg[3] != null ? ((Number) agg[3]).doubleValue() : 0.0;

        // Compute pass rate: ratio of attempts with ≥50% correct answers
        // Use eager loading to avoid N+1 (one query fetches attempts + answers)
        List<Attempt> completed = attemptRepository.findCompletedWithAnswersByQuizId(quizId);

        int totalQuestions = (int) questionRepository.countByQuizId_Id(quizId);

        long passing = completed.stream()
                .filter(attempt -> {
                    if (totalQuestions == 0) {
                        return false;
                    }
                    long correctCount = attempt.getAnswers().stream()
                            .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect()))
                            .count();
                    return ((double) correctCount / totalQuestions) >= 0.5;
                })
                .count();

        double passRate = attemptsCount > 0
                ? ((double) passing / attemptsCount) * 100.0
                : 0.0;

        // Find or create snapshot
        QuizAnalyticsSnapshot snapshot = snapshotRepository.findByQuizId(quizId)
                .orElse(new QuizAnalyticsSnapshot());

        snapshot.setQuizId(quizId);
        snapshot.setAttemptsCount(attemptsCount);
        snapshot.setAverageScore(averageScore);
        snapshot.setBestScore(bestScore);
        snapshot.setWorstScore(worstScore);
        snapshot.setPassRate(passRate);
        snapshot.setUpdatedAt(Instant.now());

        QuizAnalyticsSnapshot saved = snapshotRepository.save(snapshot);

        log.info("Updated analytics snapshot for quiz {}: {} attempts, avg={}, pass rate={}%",
                quizId, attemptsCount, averageScore, passRate);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public QuizAnalyticsSnapshot getOrComputeSnapshot(UUID quizId) {
        return snapshotRepository.findByQuizId(quizId)
                .filter(snapshot -> !isStale(snapshot))
                .orElseGet(() -> {
                    log.debug("Snapshot for quiz {} is missing or stale, triggering recomputation", quizId);
                    // Call through proxy to start new transaction (REQUIRES_NEW)
                    return self.recomputeSnapshot(quizId);
                });
    }

    /**
     * Check if a snapshot is stale based on configured max age.
     *
     * @param snapshot the snapshot to check
     * @return true if snapshot is older than max age, false otherwise
     */
    private boolean isStale(QuizAnalyticsSnapshot snapshot) {
        if (maxAgeSeconds <= 0) {
            // Staleness checking disabled
            return false;
        }

        Duration age = Duration.between(snapshot.getUpdatedAt(), Instant.now());
        boolean stale = age.toSeconds() >= maxAgeSeconds;

        if (stale) {
            log.debug("Snapshot for quiz {} is stale (age: {}s, max: {}s)",
                    snapshot.getQuizId(), age.toSeconds(), maxAgeSeconds);
        }

        return stale;
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleAttemptCompleted(AttemptCompletedEvent event) {
        log.debug("Handling attempt completed event (after commit) for attempt {} on quiz {}",
                event.getAttemptId(), event.getQuizId());

        // Retry logic for concurrent updates (optimistic locking failures)
        int maxRetries = 3;
        int attempt = 0;
        
        while (attempt < maxRetries) {
            try {
                // Call through proxy to ensure REQUIRES_NEW transaction is applied
                self.recomputeSnapshot(event.getQuizId());
                log.debug("Successfully updated analytics snapshot for quiz {} on attempt {}",
                        event.getQuizId(), attempt + 1);
                return; // Success - exit
                
            } catch (org.springframework.dao.OptimisticLockingFailureException | 
                     org.springframework.dao.DataIntegrityViolationException e) {
                attempt++;
                if (attempt < maxRetries) {
                    log.debug("Optimistic locking conflict for quiz {} (attempt {}/{}), retrying...",
                            event.getQuizId(), attempt, maxRetries);
                    // Brief exponential backoff to reduce contention
                    try {
                        Thread.sleep(50L * attempt); // 50ms, 100ms, 150ms
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Retry interrupted for quiz {}", event.getQuizId());
                        return;
                    }
                } else {
                    log.error("Failed to update analytics snapshot for quiz {} after {} attempts due to {}",
                            event.getQuizId(), maxRetries, e.getClass().getSimpleName(), e);
                }
                
            } catch (Exception e) {
                log.error("Unexpected error updating analytics snapshot for quiz {} after attempt {} completion",
                        event.getQuizId(), event.getAttemptId(), e);
                return; // Don't retry on unexpected errors
            }
        }
        // Note: Snapshot will be recomputed on next read if still stale
    }
}

