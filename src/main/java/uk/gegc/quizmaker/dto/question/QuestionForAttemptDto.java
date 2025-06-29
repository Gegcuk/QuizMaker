package uk.gegc.quizmaker.dto.question;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;

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

    @Schema(description = "Optional attachment URL", example = "http://example.com/image.png")
    private String attachmentUrl;
} 