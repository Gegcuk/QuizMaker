package uk.gegc.quizmaker.dto.question;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Schema(
        name = "CreateQuestionRequest",
        description = "Payload for creating a new question"
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CreateQuestionRequest implements QuestionContentRequest {

    @Schema(description = "Type of the question", requiredMode = Schema.RequiredMode.REQUIRED, example = "TRUE_FALSE")
    @NotNull(message = "Type must not be null")
    private QuestionType type;

    @Schema(description = "Difficulty level", requiredMode = Schema.RequiredMode.REQUIRED, example = "EASY")
    @NotNull(message = "Difficulty must not be null")
    private Difficulty difficulty;

    @Schema(
            description = "Question text",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "What is the capital of France?"
    )
    @NotBlank(message = "Question text must not be blank")
    @Size(min = 3, max = 1000, message = "Question text length must be between 3 and 1000 characters")
    private String questionText;

    @Schema(
            description = "Content JSON specific to the question type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            type = "object"
    )
    @NotNull(message = "Content must not be null")
    private JsonNode content;

    @Schema(description = "Optional hint for the question", example = "Think of the Eiffel Tower")
    @Size(max = 500, message = "Hint length must be less than 500 characters")
    private String hint;

    @Schema(description = "Optional explanation for the answer", example = "Paris is the capital of France.")
    @Size(max = 2000, message = "Explanation must be less than 2000 characters")
    private String explanation;

    @Schema(
            description = "Optional URL for an attachment",
            example = "http://example.com/image.png"
    )
    @Size(max = 2048, message = "URL length is limited by 2048 characters")
    private String attachmentUrl;

    @Schema(
            description = "List of quiz IDs to associate this question with",
            example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]"
    )
    private List<UUID> quizIds = new ArrayList<>();

    @Schema(
            description = "List of tag IDs to associate this question with",
            example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afb7\"]"
    )
    private List<UUID> tagIds = new ArrayList<>();
}