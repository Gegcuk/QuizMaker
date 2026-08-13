package uk.gegc.quizmaker.features.quiz.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Quiz generation status mapping tests")
class QuizGenerationStatusTest {

    @Test
    @DisplayName("Status mapping exposes bounded progress with at most two decimal places")
    void fromEntity_exposesBoundedProgressWithAtMostTwoDecimalPlaces() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setProgressPercentage(42.812831723);

        QuizGenerationStatus status = QuizGenerationStatus.fromEntity(job);

        assertEquals(42.81, status.progressPercentage());
    }

    @Test
    @DisplayName("Status mapping normalizes legacy active 100 percent rows to 99")
    void fromEntity_normalizesLegacyActiveCompletionValue() {
        QuizGenerationJob job = jobWithProgress(GenerationStatus.PROCESSING, 100.0);

        QuizGenerationStatus status = QuizGenerationStatus.fromEntity(job);

        assertEquals(99.0, status.progressPercentage());
    }

    @Test
    @DisplayName("Status mapping keeps failed and cancelled jobs below 100")
    void fromEntity_keepsUnsuccessfulTerminalStatesBelowCompletion() {
        QuizGenerationStatus failed = QuizGenerationStatus.fromEntity(
                jobWithProgress(GenerationStatus.FAILED, 100.0)
        );
        QuizGenerationStatus cancelled = QuizGenerationStatus.fromEntity(
                jobWithProgress(GenerationStatus.CANCELLED, 100.0)
        );

        assertAll(
                () -> assertEquals(99.0, failed.progressPercentage()),
                () -> assertEquals(99.0, cancelled.progressPercentage())
        );
    }

    @Test
    @DisplayName("Status mapping reports completed legacy jobs as exactly 100 percent")
    void fromEntity_normalizesCompletedLegacyValueToCompletion() {
        QuizGenerationJob job = jobWithProgress(GenerationStatus.COMPLETED, 37.5);

        QuizGenerationStatus status = QuizGenerationStatus.fromEntity(job);

        assertEquals(100.0, status.progressPercentage());
    }

    @Test
    @DisplayName("Provider usage completeness is additive and follows billing-field visibility")
    void fromEntityExposesProviderUsageStateOnlyWithBillingFields() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setProviderUsageState(ProviderUsageState.INCOMPLETE);

        QuizGenerationStatus publicStatus = QuizGenerationStatus.fromEntity(job, false);
        QuizGenerationStatus billingStatus = QuizGenerationStatus.fromEntity(job, true);

        assertEquals(null, publicStatus.providerUsageState());
        assertEquals(ProviderUsageState.INCOMPLETE, billingStatus.providerUsageState());
    }

    private QuizGenerationJob jobWithProgress(GenerationStatus status, double progressPercentage) {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setProgressPercentage(progressPercentage);
        job.setStatus(status);
        return job;
    }
}
