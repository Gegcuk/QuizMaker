package uk.gegc.quizmaker.features.attempt.api.dto;

import uk.gegc.quizmaker.features.question.api.dto.QuestionContentAvailability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "AnswerSubmissionDto", description = "Result of submitting an answer to a question")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerSubmissionDto(
        @Schema(description = "UUID of the answer record", example = "4b3a2c1d-0f9e-8d7c-6b5a-4c3b2a1d0e9f")
        UUID answerId,

        @Schema(description = "UUID of the question", example = "abcdef12-3456-7890-abcd-ef1234567890")
        UUID questionId,

        @Schema(description = "Whether the submitted answer was correct (only included when includeCorrectness=true)", example = "true")
        Boolean isCorrect,

        @Schema(description = "Score awarded for this answer", example = "1.0")
        Double score,

        @Schema(description = "Timestamp when the answer was recorded", example = "2025-05-20T14:35:00Z")
        Instant answeredAt,

        @Schema(description = "Correct answer information (only included when includeCorrectAnswer=true)", type = "object")
        JsonNode correctAnswer,

        @Schema(description = "Explanation of the correct answer (included when includeExplanation=true)", example = "Paris is the capital of France.")
        String explanation,

        @Schema(description = "Next question in ONE_BY_ONE mode (safe, without correct answers)")
        QuestionForAttemptDto nextQuestion
) {
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contractVersion", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    public int contractVersion() { return 1; }

    @com.fasterxml.jackson.annotation.JsonProperty("correctAnswerAvailability")
    @Schema(description = "NOT_INCLUDED means omitted by existing inclusion rules, never an incorrect answer")
    public QuestionContentAvailability correctAnswerAvailability() {
        return correctAnswer == null
                ? QuestionContentAvailability.NOT_INCLUDED
                : QuestionContentAvailability.AVAILABLE;
    }

}
