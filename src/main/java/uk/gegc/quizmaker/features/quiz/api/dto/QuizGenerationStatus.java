package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.time.LocalDateTime;

@Schema(name = "QuizGenerationStatus", description = "Current status of a quiz generation job")
public record QuizGenerationStatus(
        @Schema(description = "Unique job identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        String jobId,

        @Schema(description = "Current status of the generation job", example = "PROCESSING")
        GenerationStatus status,

        @Schema(description = "Total number of chunks to process", example = "10")
        Integer totalChunks,

        @Schema(description = "Number of chunks processed so far", example = "5")
        Integer processedChunks,

        @Schema(description = "Progress percentage (0-100)", example = "50.0")
        Double progressPercentage,

        @Schema(description = "Currently processing chunk", example = "Chapter 3 - Introduction")
        String currentChunk,

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
        LocalDateTime completedAt
) {

    /**
     * Create a status DTO from a QuizGenerationJob entity
     */
    public static QuizGenerationStatus fromEntity(QuizGenerationJob job) {
        return new QuizGenerationStatus(
                job.getId().toString(),
                job.getStatus(),
                job.getTotalChunks(),
                job.getProcessedChunks(),
                job.getProgressPercentage(),
                job.getCurrentChunk(),
                job.getEstimatedCompletion(),
                job.getErrorMessage(),
                job.getTotalQuestionsGenerated(),
                job.getDurationSeconds(),
                job.getEstimatedTimeRemainingSeconds(),
                job.getGeneratedQuizId() != null ? job.getGeneratedQuizId().toString() : null,
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }

    /**
     * Check if the job is in a terminal state
     */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /**
     * Check if the job completed successfully
     */
    public boolean isCompleted() {
        return GenerationStatus.COMPLETED.equals(status);
    }

    /**
     * Check if the job failed
     */
    public boolean isFailed() {
        return GenerationStatus.FAILED.equals(status) || GenerationStatus.CANCELLED.equals(status);
    }

    /**
     * Check if the job is still active
     */
    public boolean isActive() {
        return status != null && status.isActive();
    }
} 