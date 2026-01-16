package uk.gegc.quizmaker.features.attempt.api.dto;

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
        Instant answeredAt
) {
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
