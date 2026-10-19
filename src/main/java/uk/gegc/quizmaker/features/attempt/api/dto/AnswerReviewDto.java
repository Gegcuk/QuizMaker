package uk.gegc.quizmaker.features.attempt.api.dto;

import uk.gegc.quizmaker.features.question.api.dto.QuestionContentAvailability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "AnswerReviewDto", description = "Review details for a single answer with user response and correct answer")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerReviewDto(
        @Schema(description = "UUID of the question", example = "abcdef12-3456-7890-abcd-ef1234567890")
        UUID questionId,

        @Schema(description = "Question type", example = "MCQ_SINGLE")
        QuestionType type,

        @Schema(description = "Question text", example = "What is the capital of France?")
        String questionText,

        @Schema(description = "Optional hint", example = "Think about major European cities")
        String hint,

    @Schema(description = "Optional attachment URL", example = "http://example.com/image.png")
    String attachmentUrl,

    @Schema(description = "Resolved attachment metadata")
    MediaRefDto attachment,

    @Schema(description = "Optional explanation of the correct answer (only included when includeCorrectAnswers=true)", example = "Paris is the capital and largest city of France")
    String explanation,

    @Schema(description = "Safe question content for rendering (without correct answers)", type = "object")
    JsonNode questionSafeContent,

        @Schema(description = "User's submitted response (JSON structure depends on question type)", type = "object")
        JsonNode userResponse,

        @Schema(description = "Correct answer (JSON structure depends on question type)", type = "object")
        JsonNode correctAnswer,

        @Schema(description = "Whether the user's answer was correct", example = "true")
        Boolean isCorrect,

        @Schema(description = "Score awarded for this answer", example = "1.0")
        Double score,

        @Schema(description = "Timestamp when the answer was submitted", example = "2025-05-20T14:35:00Z")
        Instant answeredAt,

        @Schema(description = "Availability of questionSafeContent; NOT_INCLUDED means context was not requested")
        QuestionContentAvailability contentAvailability,
        @Schema(description = "Availability of userResponse; never substitutes an empty or incorrect answer")
        QuestionContentAvailability responseAvailability,
        @Schema(description = "AVAILABLE, NOT_INCLUDED or UNAVAILABLE; omission is distinct from isCorrect=false")
        QuestionContentAvailability correctAnswerAvailability,
        @Schema(description = "Bounded diagnostic for unavailable data; no source content or internal details")
        org.springframework.http.ProblemDetail contentProblem
) {
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contractVersion", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    public int contractVersion() { return 1; }

    public AnswerReviewDto(UUID questionId, QuestionType type, String questionText, String hint,
                           String attachmentUrl, MediaRefDto attachment, String explanation,
                           JsonNode questionSafeContent, JsonNode userResponse, JsonNode correctAnswer,
                           Boolean isCorrect, Double score, Instant answeredAt) {
        this(questionId, type, questionText, hint, attachmentUrl, attachment, explanation,
                questionSafeContent, userResponse, correctAnswer, isCorrect, score, answeredAt,
                availability(questionSafeContent), availability(userResponse), availability(correctAnswer), null);
    }

    private static QuestionContentAvailability availability(JsonNode value) {
        return value == null ? QuestionContentAvailability.NOT_INCLUDED
                : QuestionContentAvailability.AVAILABLE;
    }

    public AnswerReviewDto(
            UUID questionId,
            QuestionType type,
            String questionText,
            String hint,
            String attachmentUrl,
            String explanation,
            JsonNode questionSafeContent,
            JsonNode userResponse,
            JsonNode correctAnswer,
            Boolean isCorrect,
            Double score,
            Instant answeredAt
    ) {
        this(
                questionId,
                type,
                questionText,
                hint,
                attachmentUrl,
                null,
                explanation,
                questionSafeContent,
                userResponse,
                correctAnswer,
                isCorrect,
                score,
                answeredAt
        );
    }
}
