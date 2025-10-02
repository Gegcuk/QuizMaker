package uk.gegc.quizmaker.features.quiz.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;

/**
 * Scheduler for cleaning up stale pending quiz generation jobs.
 * Runs periodically to identify and fail jobs that have been stuck in PENDING state
 * longer than the configured activation timeout, and releases their billing reservations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizGenerationJobCleanupScheduler {

    private final QuizGenerationJobService jobService;

    /**
     * Cleanup stale pending jobs at a fixed delay interval.
     * The delay is configurable via quiz.jobs.cleanup-fixed-delay-seconds property.
     * Default: 60 seconds (1 minute)
     */
    @Scheduled(fixedDelayString = "${quiz.jobs.cleanup-fixed-delay-seconds:60}000")
    public void cleanupStalePendingJobs() {
        log.debug("Running scheduled cleanup of stale pending jobs");
        try {
            jobService.cleanupStalePendingJobs();
        } catch (Exception e) {
            log.error("Error during scheduled cleanup of stale pending jobs", e);
            // Don't propagate exception to prevent scheduler from stopping
        }
    }
}

