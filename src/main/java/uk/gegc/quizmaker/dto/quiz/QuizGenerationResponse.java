package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.model.quiz.GenerationStatus;

import java.util.UUID;

@Schema(name = "QuizGenerationResponse", description = "Response from starting a quiz generation job")
public record QuizGenerationResponse(
        @Schema(description = "Unique job identifier for tracking progress", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID jobId,

        @Schema(description = "Current status of the generation job", example = "PROCESSING")
        GenerationStatus status,

        @Schema(description = "Message describing the current state", example = "Quiz generation started successfully")
        String message,

        @Schema(description = "Estimated time to completion in seconds", example = "300")
        Long estimatedTimeSeconds
) {

    /**
     * Create a response for a newly started job
     */
    public static QuizGenerationResponse started(UUID jobId, Long estimatedTimeSeconds) {
        return new QuizGenerationResponse(
                jobId,
                GenerationStatus.PROCESSING,
                "Quiz generation started successfully",
                estimatedTimeSeconds
        );
    }

    /**
     * Create a response for a job that failed to start
     */
    public static QuizGenerationResponse failed(String errorMessage) {
        return new QuizGenerationResponse(
                null,
                GenerationStatus.FAILED,
                errorMessage,
                0L
        );
    }
} 