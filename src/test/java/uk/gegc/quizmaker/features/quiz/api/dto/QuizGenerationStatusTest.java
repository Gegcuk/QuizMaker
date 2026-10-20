package uk.gegc.quizmaker.features.quiz.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Quiz generation status mapping tests")
class QuizGenerationStatusTest {

    @Test
    void queueFieldsAreAnOptionalConsistentSnapshotAndNeverAppearAfterAdmission() throws Exception {
        var instant = java.time.Instant.parse("2026-10-20T12:00:00Z");
        var position = new uk.gegc.quizmaker.features.quiz.application.query.GenerationQueuePosition(2, instant);
        var pending = QuizGenerationStatus.fromEntity(jobWithProgress(GenerationStatus.PENDING, 0), false, null, position);
        assertEquals(2L, pending.queuePosition());
        assertEquals(1L, pending.jobsAhead());
        assertEquals(instant, pending.queuePositionAsOf());
        for (var state : List.of(GenerationStatus.PROCESSING, GenerationStatus.COMPLETED, GenerationStatus.FAILED, GenerationStatus.CANCELLED)) {
            var status = QuizGenerationStatus.fromEntity(jobWithProgress(state, 0), false, null, position);
            assertNull(status.queuePosition());
            assertNull(status.jobsAhead());
            assertNull(status.queuePositionAsOf());
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var oldPayload = "{\"jobId\":\"legacy\",\"status\":\"PENDING\",\"progressPercentage\":0}";
        var legacy = mapper.readValue(oldPayload, QuizGenerationStatus.class);
        assertNull(legacy.queuePosition());
        assertNull(legacy.jobsAhead());
        assertNull(legacy.queuePositionAsOf());
        // Model an existing client that ignores additive response members.
        var oldClient = mapper.readerFor(LegacyStatus.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertEquals("PENDING", ((LegacyStatus) oldClient.readValue(mapper.writeValueAsString(pending))).status());
    }

    private record LegacyStatus(String jobId, String status, Double progressPercentage) { }

    @Test
    @DisplayName("Status mapping exposes bounded progress with at most two decimal places")
    void fromEntity_exposesBoundedProgressWithAtMostTwoDecimalPlaces() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setProgressPercentage(42.812831723);

        QuizGenerationStatus status = QuizGenerationStatus.fromEntity(job);

        assertEquals(42.81, status.progressPercentage());
        assertNull(status.coverage());
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

    @Test
    @DisplayName("Status mapping adds typed coverage without changing legacy null behavior")
    void fromEntityMapsOptionalTypedCoverage() {
        QuizGenerationJob job = jobWithProgress(GenerationStatus.PROCESSING, 99.0);
        GenerationCoverageSnapshot snapshot = new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.PARTIAL,
                80,
                10,
                9,
                1,
                2,
                List.of(
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.FILL_GAP, 5, 4, 1),
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.MCQ_SINGLE, 5, 5, 0)
                )
        );

        QuizGenerationStatus status = QuizGenerationStatus.fromEntity(
                job,
                false,
                GenerationCoverage.fromSnapshot(snapshot)
        );

        assertAll(
                () -> assertEquals(GenerationCoverageOutcome.PARTIAL, status.coverage().outcome()),
                () -> assertEquals(10, status.coverage().requested()),
                () -> assertEquals(9, status.coverage().accepted()),
                () -> assertEquals(1, status.coverage().missing()),
                () -> assertEquals(
                        List.of(QuestionType.MCQ_SINGLE, QuestionType.FILL_GAP),
                        status.coverage().types().stream().map(GenerationTypeCoverage::questionType).toList()
                )
        );
    }

    private QuizGenerationJob jobWithProgress(GenerationStatus status, double progressPercentage) {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setProgressPercentage(progressPercentage);
        job.setStatus(status);
        return job;
    }
}
