package uk.gegc.quizmaker.dto.question;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class QuestionDto {

    private UUID id;
    private QuestionType type;
    private Difficulty difficulty;
    private String questionText;
    private JsonNode content;
    private String hint;
    private String explanation;
    private String attachmentUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private List<UUID> quizIds;
    private List<UUID> tagIds;
}
