package uk.gegc.quizmaker.features.quiz.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationProviderUsage.TerminalOutcome.*;

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

        var attempt = QuizGenerationProviderUsage.started(jobId, attemptId, now, 1L);

        assertThat(attempt.matches(ProviderUsageRecordState.STARTED, null)).isTrue();
        assertThat(attempt.recordTerminalFact(ProviderUsageRecordState.REPORTED, 0L, now.plusSeconds(1)))
                .isEqualTo(RECORDED);
        assertThat(attempt.recordTerminalFact(ProviderUsageRecordState.REPORTED, 0L, now.plusSeconds(2)))
                .isEqualTo(DUPLICATE);
        assertThat(attempt.getFirstConflictAt()).isNull();
        assertThat(attempt.getTerminalAt()).isEqualTo(now.plusSeconds(1));
        assertThat(attempt.recordTerminalFact(ProviderUsageRecordState.MISSING, null, now.plusSeconds(3)))
                .isEqualTo(FIRST_CONFLICT);
        assertThat(attempt.recordTerminalFact(ProviderUsageRecordState.FAILED, null, now.plusSeconds(4)))
                .isEqualTo(KNOWN_CONFLICT);
        assertThat(attempt.getFirstConflictAt()).isEqualTo(now.plusSeconds(3));
        assertThat(attempt.getConflictingRecordState()).isEqualTo(ProviderUsageRecordState.MISSING);
        assertThat(attempt.getConflictingProviderLlmTokens()).isNull();
        assertThat(attempt.getRecordState()).isEqualTo(ProviderUsageRecordState.REPORTED);
        assertThat(attempt.getProviderLlmTokens()).isZero();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> attempt.recordTerminalFact(ProviderUsageRecordState.REPORTED, -1L, now));
    }
}
