package uk.gegc.quizmaker.dto.question;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(
        name = "QuestionDto",
        description = "Data transfer object representing a question"
)
@Getter
@Setter
public class QuestionDto {

    @Schema(description = "UUID of the question", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Question type", example = "MCQ_SINGLE")
    private QuestionType type;

    @Schema(description = "Difficulty level", example = "MEDIUM")
    private Difficulty difficulty;

    @Schema(description = "Question text", example = "Select the correct option:")
    private String questionText;

    @Schema(description = "Content JSON for this question", type = "object")
    private JsonNode content;

    @Schema(description = "Optional hint text", example = "Remember the order")
    private String hint;

    @Schema(description = "Optional explanation text", example = "Because that option matches the criteria")
    private String explanation;

    @Schema(description = "Optional attachment URL", example = "http://example.com/diagram.png")
    private String attachmentUrl;

    @Schema(description = "Timestamp when created", example = "2025-05-21T14:30:00Z")
    private Instant createdAt;

    @Schema(description = "Timestamp when last updated", example = "2025-05-22T09:15:00Z")
    private Instant updatedAt;

    @Schema(
            description = "List of associated quiz UUIDs",
            example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]"
    )
    private List<UUID> quizIds;

    @Schema(
            description = "List of associated tag UUIDs",
            example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afb7\"]"
    )
    private List<UUID> tagIds;
}
