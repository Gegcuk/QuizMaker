package uk.gegc.quizmaker.features.quiz.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuizGenerationStatusTest {

    @Test
    void fromEntity_exposesBoundedProgressWithAtMostTwoDecimalPlaces() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setProgressPercentage(42.812831723);

        QuizGenerationStatus status = QuizGenerationStatus.fromEntity(job);

        assertEquals(42.81, status.progressPercentage());
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
}
