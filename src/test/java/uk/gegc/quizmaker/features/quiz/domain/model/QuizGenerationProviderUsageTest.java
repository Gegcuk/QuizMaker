package uk.gegc.quizmaker.features.quiz.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Quiz generation provider usage")
class QuizGenerationProviderUsageTest {

    @Test
    @DisplayName("Reported usage increments telemetry without changing legacy customer tokens")
    void reportedUsageChangesOnlyProviderTelemetry() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setActualTokens(77L);
        job.setProviderLlmTokens(23L);

        job.recordProviderUsage(100L);

        assertThat(job.getProviderLlmTokens()).isEqualTo(123L);
        assertThat(job.getActualTokens()).isEqualTo(77L);
        assertThat(job.getProviderUsageState()).isEqualTo(ProviderUsageState.COMPLETE);
    }

    @Test
    @DisplayName("Missing usage makes telemetry incomplete and later reports cannot hide the gap")
    void missingUsageRemainsIncompleteAfterLaterReport() {
        QuizGenerationJob job = new QuizGenerationJob();

        job.recordProviderUsage(null);
        job.recordProviderUsage(25L);

        assertThat(job.getProviderLlmTokens()).isEqualTo(25L);
        assertThat(job.getProviderUsageState()).isEqualTo(ProviderUsageState.INCOMPLETE);
    }

    @Test
    @DisplayName("Provider attempt rows transition once and reject conflicting terminal facts")
    void providerUsageRecordEnforcesLifecycleInvariant() {
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.parse("2026-08-10T12:00:00");

        var attempt = QuizGenerationProviderUsage.started(jobId, attemptId, now);

        assertThat(attempt.matches(ProviderUsageRecordState.STARTED, null)).isTrue();
        assertThat(attempt.transitionTo(ProviderUsageRecordState.REPORTED, 0L)).isTrue();
        assertThat(attempt.transitionTo(ProviderUsageRecordState.REPORTED, 0L)).isFalse();
        assertThatThrownBy(() -> attempt.transitionTo(ProviderUsageRecordState.MISSING, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> attempt.transitionTo(ProviderUsageRecordState.REPORTED, -1L));
    }
}
