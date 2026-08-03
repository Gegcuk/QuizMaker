package uk.gegc.quizmaker.features.quiz.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationIdempotencyService;

/** Removes expired idempotency records after their documented replay window. */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizGenerationOperationCleanupScheduler {

    private final QuizGenerationIdempotencyService idempotencyService;

    @Scheduled(fixedDelayString = "${quiz.generation.operations.cleanup-fixed-delay-seconds:3600}000")
    public void purgeExpiredOperations() {
        try {
            int deleted = idempotencyService.purgeExpiredOperations();
            if (deleted > 0) {
                log.info("Purged {} expired quiz-generation idempotency operations", deleted);
            }
        } catch (Exception exception) {
            log.error("Unable to purge expired quiz-generation idempotency operations", exception);
        }
    }
}
