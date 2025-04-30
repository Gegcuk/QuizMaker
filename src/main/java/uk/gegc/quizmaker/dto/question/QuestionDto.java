package uk.gegc.quizmaker.dto.question;

import lombok.Getter;
import lombok.Setter;

import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class QuestionDto {

    private UUID id;
    private QuestionType type;
    private Difficulty difficulty;
    private String questionText;
    private String content;
    private String hint;
    private String explanation;
    private String attachmentUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<UUID> quizIds;
    private List<UUID> tagIds;
}
