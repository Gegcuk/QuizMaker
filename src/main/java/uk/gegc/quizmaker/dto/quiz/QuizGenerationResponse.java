package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.dto.quiz.QuizScope;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Schema(name = "QuizGenerationResponse", description = "Response from AI quiz generation process")
public record QuizGenerationResponse(
        @Schema(description = "Generated quiz ID", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID quizId,

        @Schema(description = "Quiz title", example = "Machine Learning Fundamentals Quiz")
        String title,

        @Schema(description = "Quiz description", example = "Test your knowledge of machine learning basics")
        String description,

        @Schema(description = "Total number of questions generated", example = "25")
        Integer totalQuestions,

        @Schema(description = "Quiz scope that was used for generation", example = "SPECIFIC_CHAPTER")
        QuizScope quizScope,

        @Schema(description = "Number of chunks processed", example = "5")
        Integer chunksProcessed,

        @Schema(description = "Questions generated per chunk by type", example = "{\"MCQ_SINGLE\": 3, \"TRUE_FALSE\": 2}")
        Map<QuestionType, Integer> questionsPerChunk,

        @Schema(description = "Total time taken for generation", example = "PT2M30S")
        Duration generationTime,

        @Schema(description = "Quiz status", example = "DRAFT")
        QuizStatus status,

        @Schema(description = "Estimated time to complete the quiz in minutes", example = "15")
        Integer estimatedTime,

        @Schema(description = "Summary of the generation process")
        QuizGenerationSummary generationSummary
) {} 