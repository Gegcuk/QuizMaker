package uk.gegc.quizmaker.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.CONCURRENT)
public class CreateQuestionRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private Validator validator;

    @BeforeAll
    static void setUpFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @BeforeEach
    void setUp() {
        validator = validatorFactory.getValidator();
    }

    @Test
    void questionTextIsNull_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setQuestionText(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Question text must not be blank");
    }

    @Test
    void questionTextTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setQuestionText("x".repeat(1001));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Question text length must be between 3 and 1000 characters");
    }

    @Test
    void questionTextTooShort_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setQuestionText("x".repeat(2));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Question text length must be between 3 and 1000 characters");
    }

    @Test
    void hintTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setHint("x".repeat(501));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "hint");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Hint length must be less than 500 characters");
    }

    @Test
    void explanationTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setExplanation("x".repeat(2001));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "explanation");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Explanation must be less than 2000 characters");
    }

    @Test
    void attachmentUrlTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setAttachmentUrl("x".repeat(2049));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "attachmentUrl");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("URL length is limited by 2048 characters");
    }

    @Test
    void typeIsNull_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setType(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "type");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Type must not be null");
    }

    @Test
    void difficultyIsNull_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setDifficulty(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "difficulty");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Difficulty must not be null");
    }

    @Test
    void contentIsNull_thanValidationFails() {
        CreateQuestionRequest questionRequest = new CreateQuestionRequest();
        questionRequest.setContent(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(questionRequest, "content");

        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Content must not be null");
    }

}
