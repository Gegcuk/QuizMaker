package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(name = "DocumentQuizGroupDto", description = "Group of quizzes generated from the same document")
public record DocumentQuizGroupDto(
        @Schema(description = "Document quiz group identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID groupId,

        @Schema(description = "Source document identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID documentId,

        @Schema(description = "Document title", example = "Machine Learning Fundamentals")
        String documentTitle,

        @Schema(description = "Document filename", example = "ml-fundamentals.pdf")
        String documentFilename,

        @Schema(description = "Number of chunks in the document", example = "5")
        int totalChunks,

        @Schema(description = "Total number of questions across all quizzes", example = "25")
        int totalQuestions,

        @Schema(description = "Number of chunk quizzes generated", example = "5")
        int chunkQuizCount,

        @Schema(description = "Consolidated quiz identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID consolidatedQuizId,

        @Schema(description = "Generation job identifier", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID generationJobId,

        @Schema(description = "User who uploaded the document", example = "john.doe")
        String uploadedBy,

        @Schema(description = "Document upload timestamp", example = "2024-01-15T14:25:00")
        LocalDateTime documentUploadedAt,

        @Schema(description = "Quiz generation completion timestamp", example = "2024-01-15T14:30:00")
        LocalDateTime generationCompletedAt,

        @Schema(description = "List of chunk quiz identifiers", example = "[\"uuid1\", \"uuid2\", \"uuid3\"]")
        List<UUID> chunkQuizIds,

        @Schema(description = "Generation status", example = "COMPLETED")
        String status,

        @Schema(description = "Error message if generation failed", example = "AI service temporarily unavailable")
        String errorMessage
) {

    /**
     * Create a document quiz group DTO
     */
    public static DocumentQuizGroupDto create(
            UUID groupId,
            UUID documentId,
            String documentTitle,
            String documentFilename,
            int totalChunks,
            int totalQuestions,
            int chunkQuizCount,
            UUID consolidatedQuizId,
            UUID generationJobId,
            String uploadedBy,
            LocalDateTime documentUploadedAt,
            LocalDateTime generationCompletedAt,
            List<UUID> chunkQuizIds
    ) {
        return new DocumentQuizGroupDto(
                groupId,
                documentId,
                documentTitle,
                documentFilename,
                totalChunks,
                totalQuestions,
                chunkQuizCount,
                consolidatedQuizId,
                generationJobId,
                uploadedBy,
                documentUploadedAt,
                generationCompletedAt,
                chunkQuizIds,
                "COMPLETED",
                null
        );
    }

    /**
     * Create a failed document quiz group DTO
     */
    public static DocumentQuizGroupDto failed(
            UUID groupId,
            UUID documentId,
            String documentTitle,
            String errorMessage
    ) {
        return new DocumentQuizGroupDto(
                groupId,
                documentId,
                documentTitle,
                null,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
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
     * Check if consolidated quiz exists
     */
    public boolean hasConsolidatedQuiz() {
        return consolidatedQuizId != null;
    }

    /**
     * Get total number of quizzes (chunk + consolidated)
     */
    public int getTotalQuizCount() {
        int count = chunkQuizCount;
        if (hasConsolidatedQuiz()) {
            count++;
        }
        return count;
    }
} 