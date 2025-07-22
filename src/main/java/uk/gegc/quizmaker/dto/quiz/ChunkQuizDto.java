package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(name = "ChunkQuizDto", description = "Individual quiz for a document chunk")
public record ChunkQuizDto(
        @Schema(description = "Quiz identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID quizId,

        @Schema(description = "Quiz title", example = "Chapter 1 - Introduction Quiz")
        String title,

        @Schema(description = "Quiz description", example = "Quiz covering the introduction chapter")
        String description,

        @Schema(description = "Number of questions in this quiz", example = "5")
        int questionCount,

        @Schema(description = "Chunk index in the document", example = "1")
        int chunkIndex,

        @Schema(description = "Chunk title", example = "Introduction")
        String chunkTitle,

        @Schema(description = "Estimated time to complete in minutes", example = "10")
        Integer estimatedTime,

        @Schema(description = "Quiz status", example = "PUBLISHED")
        String status,

        @Schema(description = "Quiz visibility", example = "PRIVATE")
        String visibility,

        @Schema(description = "Creation timestamp", example = "2024-01-15T14:30:00")
        LocalDateTime createdAt,

        @Schema(description = "Category name", example = "AI Generated")
        String categoryName,

        @Schema(description = "Document identifier this quiz belongs to", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID documentId
) {

    /**
     * Create a chunk quiz DTO from quiz entity and chunk metadata
     */
    public static ChunkQuizDto fromQuiz(QuizDto quiz, int chunkIndex, String chunkTitle, UUID documentId) {
        return new ChunkQuizDto(
                quiz.id(),
                quiz.title(),
                quiz.description(),
                0, // questionCount will be set separately
                chunkIndex,
                chunkTitle,
                quiz.estimatedTime(),
                quiz.status().toString(),
                quiz.visibility().toString(),
                LocalDateTime.now(), // createdAt will be set separately
                "AI Generated", // categoryName will be set separately
                documentId
        );
    }

    /**
     * Check if this is a valid chunk quiz
     */
    public boolean isValid() {
        return quizId != null && title != null && chunkIndex > 0;
    }

    /**
     * Get display title for the chunk quiz
     */
    public String getDisplayTitle() {
        if (chunkTitle != null && !chunkTitle.trim().isEmpty()) {
            return String.format("Quiz: %s", chunkTitle);
        }
        return String.format("Quiz: Chapter %d", chunkIndex);
    }
} 