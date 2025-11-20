package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(name = "GenerateQuizFromUploadRequest", description = "Request to upload document and generate quiz in one operation")
public record GenerateQuizFromUploadRequest(
        @Schema(description = "Document processing strategy", example = "CHAPTER_BASED")
        ProcessDocumentRequest.ChunkingStrategy chunkingStrategy,

        @Schema(description = "Maximum characters per chunk", example = "50000")
        @Min(value = 1000, message = "Max chunk size must be at least 1000 characters")
        @Max(value = 100000, message = "Max chunk size must not exceed 100000 characters")
        Integer maxChunkSize,

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

        @Schema(description = "Difficulty level for the generated questions (defaults to MEDIUM if not provided)", example = "MEDIUM", defaultValue = "MEDIUM")
        Difficulty difficulty,

        @Schema(description = "Estimated time per question in minutes", example = "2")
        @Min(value = 1, message = "Estimated time per question must be at least 1 minute")
        @Max(value = 10, message = "Estimated time per question must not exceed 10 minutes")
        Integer estimatedTimePerQuestion,

        @Schema(description = "Category ID for the quiz", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID categoryId,

        @Schema(description = "List of tag IDs for the quiz", example = "[\"a1b2c3d4-...\", \"e5f6g7h8-...\"]")
        List<UUID> tagIds,

        @Schema(description = "Language for generated quiz content (ISO 639-1 code)", example = "en")
        String language
) {
    public GenerateQuizFromUploadRequest {
        // Set default values
        chunkingStrategy = (chunkingStrategy == null) ? ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED : chunkingStrategy;
        maxChunkSize = (maxChunkSize == null) ? 250000 : maxChunkSize;
        estimatedTimePerQuestion = (estimatedTimePerQuestion == null) ? 1 : estimatedTimePerQuestion;
        tagIds = (tagIds == null) ? List.of() : tagIds;
        language = (language == null || language.isBlank()) ? "en" : language.trim();
        
        // Defensive fallback for difficulty (validation should catch null, but this prevents NPE)
        difficulty = (difficulty == null) ? Difficulty.MEDIUM : difficulty;

        // Set default scope if not provided - MUST be done before validation
        quizScope = (quizScope == null) ? QuizScope.ENTIRE_DOCUMENT : quizScope;

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

        // Validate scope-specific parameters - AFTER setting default scope
        validateScopeParameters();
    }

    private void validateScopeParameters() {
        // Use a local variable to handle null quizScope
        QuizScope scope = (quizScope == null) ? QuizScope.ENTIRE_DOCUMENT : quizScope;

        switch (scope) {
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

    /**
     * Convert to ProcessDocumentRequest for document processing
     */
    public ProcessDocumentRequest toProcessDocumentRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(chunkingStrategy);
        request.setMaxChunkSize(maxChunkSize);
        request.setMinChunkSize(300); // Use improved chunking logic with 300 char minimum
        request.setStoreChunks(true);
        return request;
    }

    /**
     * Convert to GenerateQuizFromDocumentRequest for quiz generation
     */
    public GenerateQuizFromDocumentRequest toGenerateQuizFromDocumentRequest(UUID documentId) {
        return new GenerateQuizFromDocumentRequest(
                documentId,
                quizScope,
                chunkIndices,
                chapterTitle,
                chapterNumber,
                quizTitle,
                quizDescription,
                questionsPerType,
                difficulty,
                estimatedTimePerQuestion,
                categoryId,
                tagIds,
                language
        );
    }

    public GenerateQuizFromUploadRequest(
            ProcessDocumentRequest.ChunkingStrategy chunkingStrategy,
            Integer maxChunkSize,
            QuizScope quizScope,
            List<Integer> chunkIndices,
            String chapterTitle,
            Integer chapterNumber,
            String quizTitle,
            String quizDescription,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty,
            Integer estimatedTimePerQuestion,
            UUID categoryId,
            List<UUID> tagIds
    ) {
        this(
                chunkingStrategy,
                maxChunkSize,
                quizScope,
                chunkIndices,
                chapterTitle,
                chapterNumber,
                quizTitle,
                quizDescription,
                questionsPerType,
                difficulty,
                estimatedTimePerQuestion,
                categoryId,
                tagIds,
                null
        );
    }
}
