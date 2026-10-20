package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import uk.gegc.quizmaker.features.quiz.application.query.GenerationQueuePosition;

import java.time.Instant;
import java.time.LocalDateTime;

@Schema(name = "QuizGenerationStatus", description = "Current status of a quiz generation job")
public record QuizGenerationStatus(
        @Schema(description = "Unique job identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        String jobId,

        @Schema(description = "Current status of the generation job. COMPLETED means the generated quiz and its billing settlement were durably finalized and the quiz is available to its owner.", example = "PROCESSING")
        GenerationStatus status,

        @Schema(description = "Total number of chunks to process", example = "10")
        Integer totalChunks,

        @Schema(description = "Number of chunks processed so far", example = "5")
        Integer processedChunks,

        @Schema(description = "Progress percentage rounded to at most two decimal places. Values from 0 through 99 represent accepted or active work; 100 is reserved for a durably COMPLETED job. Failed and cancelled jobs retain their last bounded value. Clients must use status, not progress alone, to determine completion. Computed from task counters when available, otherwise from chunk counters.", example = "99.0", minimum = "0", maximum = "100")
        Double progressPercentage,

        @Schema(description = "Currently processing chunk or status message", example = "Chunk 2/5 · MCQ_SINGLE · done")
        String currentChunk,

        @Schema(description = "Total number of question type tasks to process", example = "15")
        Integer totalTasks,

        @Schema(description = "Number of completed question type tasks", example = "8")
        Integer completedTasks,

        @Schema(description = "Last completed question type", example = "MCQ_SINGLE")
        String lastCompletedType,

        @Schema(description = "Estimated completion time", example = "2024-01-15T14:30:00")
        LocalDateTime estimatedCompletion,

        @Schema(description = "Error message if generation failed", example = "AI service temporarily unavailable")
        String errorMessage,

        @Schema(description = "Total questions generated so far", example = "25")
        Integer totalQuestionsGenerated,

        @Schema(description = "Time elapsed since job started (in seconds)", example = "120")
        Long elapsedTimeSeconds,

        @Schema(description = "Estimated time remaining (in seconds)", example = "180")
        Long estimatedTimeRemainingSeconds,

        @Schema(description = "Generated quiz ID (only available when completed)", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        String generatedQuizId,

        @Schema(description = "Job start time", example = "2024-01-15T14:25:00")
        LocalDateTime startedAt,

        @Schema(description = "Job completion time (only available when completed/failed)", example = "2024-01-15T14:30:00")
        LocalDateTime completedAt,

        // Billing fields (internal use only, not exposed in public API yet)
        @Schema(description = "Billing reservation ID", example = "d290f1ee-6c54-4b01-90e6-d701748f0851", accessMode = Schema.AccessMode.READ_ONLY)
        String billingReservationId,

        @Schema(description = "Reservation expiry time", example = "2024-01-15T16:30:00", accessMode = Schema.AccessMode.READ_ONLY)
        LocalDateTime reservationExpiresAt,

        @Schema(description = "Estimated billing tokens", example = "1500", accessMode = Schema.AccessMode.READ_ONLY)
        Long billingEstimatedTokens,

        @Schema(description = "Committed billing tokens", example = "1200", accessMode = Schema.AccessMode.READ_ONLY)
        Long billingCommittedTokens,

        @Schema(description = "Billing state", example = "RESERVED", accessMode = Schema.AccessMode.READ_ONLY)
        String billingState,

        @Schema(description = "Input prompt tokens", example = "800", accessMode = Schema.AccessMode.READ_ONLY)
        Long inputPromptTokens,

        @Schema(description = "Estimation version", example = "v1.0", accessMode = Schema.AccessMode.READ_ONLY)
        String estimationVersion,

        @Schema(description = "Completeness of provider usage telemetry. This does not determine the customer charge.", example = "COMPLETE", accessMode = Schema.AccessMode.READ_ONLY, nullable = true)
        ProviderUsageState providerUsageState,

        @Schema(description = "Immutable requested-versus-accepted question coverage once reconciliation has completed. Null for legacy jobs and jobs that have not reached reconciliation. Coverage does not imply that a quiz is available; clients must still require status COMPLETED.", accessMode = Schema.AccessMode.READ_ONLY, nullable = true)
        GenerationCoverage coverage,

        @Schema(description = "Volatile 1-based global FIFO admission position, including earlier interrupted work awaiting recovery. Null after admission, for terminal/legacy/inconsistent jobs, or when the optional queue read is unavailable. This is not a completion estimate; recovery can increase a waiting position.", minimum = "1", example = "2", types = {"integer", "null"}, format = "int64", accessMode = Schema.AccessMode.READ_ONLY, nullable = true)
        Long queuePosition,

        @Schema(description = "Number of waiting admissions ahead of this job; queuePosition minus one. Null whenever queuePosition is null. Does not include jobs with an active execution lease.", minimum = "0", example = "1", types = {"integer", "null"}, format = "int64", accessMode = Schema.AccessMode.READ_ONLY, nullable = true)
        Long jobsAhead,

        @Schema(description = "UTC observation time of the same queue read used for both numeric fields. Position may change immediately after this snapshot. Null whenever queuePosition is null.", example = "2026-10-20T12:00:00Z", types = {"string", "null"}, format = "date-time", accessMode = Schema.AccessMode.READ_ONLY, nullable = true)
        Instant queuePositionAsOf
) {

    /**
     * Create a status DTO from a QuizGenerationJob entity
     */
    public static QuizGenerationStatus fromEntity(QuizGenerationJob job) {
        return fromEntity(job, false, null);
    }

    /**
     * Create a status DTO from a QuizGenerationJob entity with billing fields conditionally exposed
     */
    public static QuizGenerationStatus fromEntity(QuizGenerationJob job, boolean includeBillingFields) {
        return fromEntity(job, includeBillingFields, null);
    }

    /**
     * Create a status DTO with an optional immutable coverage snapshot.
     */
    public static QuizGenerationStatus fromEntity(
            QuizGenerationJob job,
            boolean includeBillingFields,
            GenerationCoverage coverage
    ) {
        return fromEntity(job, includeBillingFields, coverage, null);
    }

    public static QuizGenerationStatus fromEntity(
            QuizGenerationJob job,
            boolean includeBillingFields,
            GenerationCoverage coverage,
            GenerationQueuePosition position
    ) {
        // Never expose a queue position from a different lifecycle snapshot.
        if (job.getStatus() != GenerationStatus.PENDING) position = null;
        return new QuizGenerationStatus(
                job.getId().toString(),
                job.getStatus(),
                job.getTotalChunks(),
                job.getProcessedChunks(),
                job.getProgressPercentage(),
                job.getCurrentChunk(),
                job.getTotalTasks(),
                job.getCompletedTasks(),
                null, // lastCompletedType - can be extracted from currentChunk if needed
                job.getEstimatedCompletion(),
                job.getErrorMessage(),
                job.getTotalQuestionsGenerated(),
                job.getDurationSeconds(),
                job.getEstimatedTimeRemainingSeconds(),
                job.getGeneratedQuizId() != null ? job.getGeneratedQuizId().toString() : null,
                job.getStartedAt(),
                job.getCompletedAt(),
                // Conditionally expose billing fields
                includeBillingFields && job.getBillingReservationId() != null ? job.getBillingReservationId().toString() : null,
                includeBillingFields ? job.getReservationExpiresAt() : null,
                includeBillingFields ? job.getBillingEstimatedTokens() : null,
                includeBillingFields ? job.getBillingCommittedTokens() : null,
                includeBillingFields && job.getBillingState() != null ? job.getBillingState().name() : null,
                includeBillingFields ? job.getInputPromptTokens() : null,
                includeBillingFields ? job.getEstimationVersion() : null,
                includeBillingFields ? job.getProviderUsageState() : null,
                coverage,
                position == null ? null : position.queuePosition(),
                position == null ? null : position.jobsAhead(),
                position == null ? null : position.asOf()
        );
    }

    /**
     * Check if the job is in a terminal state
     */
    @Schema(description = "Whether the job is no longer active because it completed, failed, or was cancelled", accessMode = Schema.AccessMode.READ_ONLY)
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /**
     * Check if the job completed successfully
     */
    @Schema(description = "Whether the job completed successfully", accessMode = Schema.AccessMode.READ_ONLY)
    public boolean isCompleted() {
        return GenerationStatus.COMPLETED.equals(status);
    }

    /**
     * Check if the job failed
     */
    @Schema(description = "Whether the job failed or was cancelled", accessMode = Schema.AccessMode.READ_ONLY)
    public boolean isFailed() {
        return GenerationStatus.FAILED.equals(status) || GenerationStatus.CANCELLED.equals(status);
    }

    /**
     * Check if the job is still active
     */
    @Schema(description = "Whether the job is pending or processing", accessMode = Schema.AccessMode.READ_ONLY)
    public boolean isActive() {
        return status != null && status.isActive();
    }
}
