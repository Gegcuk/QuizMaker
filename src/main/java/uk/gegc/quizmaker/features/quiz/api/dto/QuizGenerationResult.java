package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(name = "QuizGenerationResult", description = "Complete result of quiz generation including chunk quizzes and consolidated quiz")
public record QuizGenerationResult(
        @Schema(description = "Generation job identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID generationJobId,

        @Schema(description = "Individual quizzes for each document chunk")
        List<ChunkQuizDto> chunkQuizzes,

        @Schema(description = "Consolidated quiz containing all questions from all chunks")
        QuizDto consolidatedQuiz,

        @Schema(description = "Document quiz group information")
        DocumentQuizGroupDto documentGroup,

        @Schema(description = "Total number of questions generated", example = "25")
        int totalQuestions,

        @Schema(description = "Time taken for generation", example = "PT2M30S")
        Duration generationTime,

        @Schema(description = "Mapping of chunk index to chunk title", example = "{\"1\": \"Introduction\", \"2\": \"Chapter 1\"}")
        Map<Integer, String> chunkTitles,

        @Schema(description = "Generation status", example = "COMPLETED")
        String status,

        @Schema(description = "Error message if generation failed", example = "AI service temporarily unavailable")
        String errorMessage
) {

    /**
     * Create a successful generation result
     */
    public static QuizGenerationResult success(
            UUID generationJobId,
            List<ChunkQuizDto> chunkQuizzes,
            QuizDto consolidatedQuiz,
            DocumentQuizGroupDto documentGroup,
            int totalQuestions,
            Duration generationTime,
            Map<Integer, String> chunkTitles
    ) {
        return new QuizGenerationResult(
                generationJobId,
                chunkQuizzes,
                consolidatedQuiz,
                documentGroup,
                totalQuestions,
                generationTime,
                chunkTitles,
                "COMPLETED",
                null
        );
    }

    /**
     * Create a failed generation result
     */
    public static QuizGenerationResult failure(
            UUID generationJobId,
            String errorMessage
    ) {
        return new QuizGenerationResult(
                generationJobId,
                null,
                null,
                null,
                0,
                Duration.ZERO,
                null,
                "FAILED",
                errorMessage
        );
    }

    /**
     * Check if generation was successful
     */
    public boolean isSuccessful() {
        return "COMPLETED".equals(status);
    }

    /**
     * Get total number of chunk quizzes
     */
    public int getChunkQuizCount() {
        return chunkQuizzes != null ? chunkQuizzes.size() : 0;
    }
} 