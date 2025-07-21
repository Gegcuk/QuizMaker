package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(name = "GenerateQuizFromDocumentRequest", description = "Request to generate a quiz from document chunks using AI")
public record GenerateQuizFromDocumentRequest(
        @Schema(description = "ID of the uploaded and processed document", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        @NotNull(message = "Document ID must not be null")
        UUID documentId,

        @Schema(description = "Quiz scope - whether to generate for entire document or specific chunks", example = "ENTIRE_DOCUMENT")
        QuizScope quizScope,

        @Schema(description = "Specific chunk indices to include (only used when quizScope is SPECIFIC_CHUNKS)", example = "[0, 1, 2]")
        List<Integer> chunkIndices,

        @Schema(description = "Chapter title to include (only used when quizScope is SPECIFIC_CHAPTER)", example = "Introduction to Machine Learning")
        String chapterTitle,

        @Schema(description = "Chapter number to include (only used when quizScope is SPECIFIC_CHAPTER)", example = "1")
        Integer chapterNumber,

        @Schema(description = "Custom quiz title (optional - AI will generate if not provided)", example = "Machine Learning Fundamentals Quiz")
        @Size(max = 100, message = "Quiz title must not exceed 100 characters")
        String quizTitle,

        @Schema(description = "Custom quiz description (optional - AI will generate if not provided)", example = "Test your knowledge of machine learning basics")
        @Size(max = 500, message = "Quiz description must not exceed 500 characters")
        String quizDescription,

        @Schema(description = "Number of questions per type to generate per chunk", example = "{\"MCQ_SINGLE\": 3, \"TRUE_FALSE\": 2}")
        @NotNull(message = "Questions per type must not be null")
        @Size(min = 1, message = "At least one question type must be specified")
        Map<QuestionType, Integer> questionsPerType,

        @Schema(description = "Difficulty level for the generated questions", example = "MEDIUM")
        @NotNull(message = "Difficulty must not be null")
        Difficulty difficulty,

        @Schema(description = "Estimated time per question in minutes", example = "2")
        @Min(value = 1, message = "Estimated time per question must be at least 1 minute")
        @Max(value = 10, message = "Estimated time per question must not exceed 10 minutes")
        Integer estimatedTimePerQuestion,

        @Schema(description = "Category ID for the quiz", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID categoryId,

        @Schema(description = "List of tag IDs for the quiz", example = "[\"a1b2c3d4-...\", \"e5f6g7h8-...\"]")
        List<UUID> tagIds
) {
    public GenerateQuizFromDocumentRequest {
        // Set default values
        estimatedTimePerQuestion = (estimatedTimePerQuestion == null) ? 2 : estimatedTimePerQuestion;
        tagIds = (tagIds == null) ? List.of() : tagIds;
        
        // Set default scope if not provided
        quizScope = (quizScope == null) ? QuizScope.ENTIRE_DOCUMENT : quizScope;
        
        // Validate scope-specific parameters
        validateScopeParameters();
        
        // Validate questions per type
        if (questionsPerType != null) {
            questionsPerType.forEach((type, count) -> {
                if (count == null || count < 1) {
                    throw new IllegalArgumentException("Question count for type " + type + " must be at least 1");
                }
                if (count > 10) {
                    throw new IllegalArgumentException("Question count for type " + type + " must not exceed 10");
                }
            });
        }
    }
    
    private void validateScopeParameters() {
        switch (quizScope) {
            case SPECIFIC_CHUNKS:
                if (chunkIndices == null || chunkIndices.isEmpty()) {
                    throw new IllegalArgumentException("Chunk indices must be specified when quizScope is SPECIFIC_CHUNKS");
                }
                if (chunkIndices.stream().anyMatch(index -> index < 0)) {
                    throw new IllegalArgumentException("Chunk indices must be non-negative");
                }
                break;
            case SPECIFIC_CHAPTER:
                if (chapterTitle == null && chapterNumber == null) {
                    throw new IllegalArgumentException("Either chapterTitle or chapterNumber must be specified when quizScope is SPECIFIC_CHAPTER");
                }
                break;
            case SPECIFIC_SECTION:
                if (chapterTitle == null && chapterNumber == null) {
                    throw new IllegalArgumentException("Either chapterTitle or chapterNumber must be specified when quizScope is SPECIFIC_SECTION");
                }
                break;
            case ENTIRE_DOCUMENT:
                // No additional validation needed
                break;
        }
    }
} 