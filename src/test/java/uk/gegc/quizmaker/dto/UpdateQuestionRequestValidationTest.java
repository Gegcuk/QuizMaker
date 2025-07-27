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
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.CONCURRENT)
public class UpdateQuestionRequestValidationTest {

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
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setQuestionText(null);
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Question text must not be blank");
    }


    @Test
    void questionTextTooLong_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setQuestionText("x".repeat(1001));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Question text length must be between 3 and 1000 characters");
    }

    @Test
    void questionTextTooShort_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setQuestionText("x".repeat(2));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Question text length must be between 3 and 1000 characters");
    }

    @Test
    void hintTooLong_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setHint("x".repeat(501));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "hint");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Hint length must be less than 500 characters");
    }

    @Test
    void explanationTooLong_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setExplanation("x".repeat(2001));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "explanation");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Explanation must be less than 2000 characters");
    }

    @Test
    void attachmentUrlTooLong_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setAttachmentUrl("x".repeat(2049));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "attachmentUrl");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("URL length is limited by 2048 characters");
    }

    @Test
    void typeIsNull_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setType(null);
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "type");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Type must not be null");
    }

    @Test
    void difficultyIsNull_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setDifficulty(null);
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "difficulty");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Difficulty must not be null");
    }

    @Test
    void contentIsNull_thanValidationFails() {
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setContent(null);
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "content");

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("Content must not be null");
    }


}
