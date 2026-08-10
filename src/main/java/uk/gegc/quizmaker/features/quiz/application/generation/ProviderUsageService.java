package uk.gegc.quizmaker.features.quiz.application.generation;

import java.util.UUID;

/**
 * Persists provider telemetry independently from customer billing.
 */
public interface ProviderUsageService {

    ProviderUsageRecordResult recordReported(UUID jobId, UUID providerAttemptId, long providerLlmTokens);

    ProviderUsageRecordResult recordMissing(UUID jobId, UUID providerAttemptId);
}
