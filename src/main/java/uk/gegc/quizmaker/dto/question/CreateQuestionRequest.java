package uk.gegc.quizmaker.dto.question;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.Quiz;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class CreateQuestionRequest {
    @NotNull(message = "Type must not be null")
    QuestionType type;

    @NotNull(message = "Difficulty must not be null")
    private Difficulty difficulty;

    @Size(min = 3, max = 1000, message = "Question text length must be between 3 and 1000 characters")
    private String questionText;

    @NotBlank(message = "Content must not be blank")
    private String content;

    @Size(max = 500, message = "Hint length must be less than 500 characters")
    private String hint;

    @Size(max = 2000, message = "Explanation must be less than 2000 characters")
    private String explanation;

    @Size(max = 2048, message = "URL length is limited by 2048 characters")
    private String attachmentUrl;

    private List<UUID> quizIds = new ArrayList<>();

    private List<UUID> tagIds = new ArrayList<>();

}
