package uk.gegc.quizmaker.features.attempt.api.dto;

import uk.gegc.quizmaker.features.question.api.dto.QuestionContentAvailability;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.util.UUID;

@Schema(
        name = "QuestionForAttemptDto",
        description = "Question data safe for users during quiz attempts (no correct answers exposed)"
)
@Getter
@Setter
public class QuestionForAttemptDto {

    @Schema(description = "Question UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Question type", example = "MCQ_SINGLE")
    private QuestionType type;

    @Schema(description = "Difficulty level", example = "MEDIUM")
    private Difficulty difficulty;

    @Schema(description = "Question text", example = "Select the correct option:")
    private String questionText;

    @Schema(description = "Safe content without correct answers", type = "object")
    private JsonNode safeContent;

    @Schema(description = "Optional hint", example = "Think carefully about the options")
    private String hint;

    @Schema(description = "Resolved attachment metadata")
    private MediaRefDto attachment;

    @Schema(description = "Optional attachment URL", example = "http://example.com/image.png")
    private String attachmentUrl;
    @Schema(description = "Question contract version; old requests without a version use v1", accessMode = Schema.AccessMode.READ_ONLY)
    private final int contractVersion = 1;

    @Schema(description = "AVAILABLE or UNAVAILABLE. Unavailable content is null, never an implicit answer or score.")
    private QuestionContentAvailability contentAvailability =
            QuestionContentAvailability.AVAILABLE;

    @Schema(description = "Bounded repair diagnostic when content is UNAVAILABLE")
    private org.springframework.http.ProblemDetail contentProblem;

} 
