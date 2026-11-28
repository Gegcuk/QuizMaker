package uk.gegc.quizmaker.features.quiz.application.generation;

import java.util.UUID;

/** Reconciles provider telemetry without dispatching provider work or settling a job. */
public interface ProviderUsageReconciliationService {
    /** Reconcile one page and return its continuation, or null to restart at the beginning. */
    UUID reconcilePage(UUID after);
}
