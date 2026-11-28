package uk.gegc.quizmaker.features.quiz.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageReconciliationService;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProviderUsageReconciliationScheduler {
    private final ProviderUsageReconciliationService reconciliation;
    private UUID after;

    @Scheduled(fixedDelayString = "${quiz.jobs.execution.scan-seconds:10}000")
    public void reconcileInterruptedAttempts() {
        try {
            after = reconciliation.reconcilePage(after);
        } catch (RuntimeException ignored) {
            log.warn("Provider usage reconciliation could not complete");
        }
    }
}
