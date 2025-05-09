package uk.gegc.quizmaker.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CreateQuestionRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void questionTextIsNull_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setQuestionText(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertEquals(1, violations.size());
        assertEquals("Question text must not be blank", violations.iterator().next().getMessage());
    }

    @Test
    void questionTextTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setQuestionText("x".repeat(1001));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertEquals(1, violations.size());
        assertEquals("Question text length must be between 3 and 1000 characters", violations.iterator().next().getMessage());
    }

    @Test
    void questionTextTooShort_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setQuestionText("x".repeat(2));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertEquals(1, violations.size());
        assertEquals("Question text length must be between 3 and 1000 characters", violations.iterator().next().getMessage());
    }

    @Test
    void hintTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setHint("x".repeat(501));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "hint");

        assertEquals(1, violations.size());
        assertEquals("Hint length must be less than 500 characters", violations.iterator().next().getMessage());
    }

    @Test
    void explanationTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setExplanation("x".repeat(2001));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "explanation");

        assertEquals(1, violations.size());
        assertEquals("Explanation must be less than 2000 characters", violations.iterator().next().getMessage());
    }

    @Test
    void attachmentUrlTooLong_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setAttachmentUrl("x".repeat(2049));
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "attachmentUrl");

        assertEquals(1, violations.size());
        assertEquals("URL length is limited by 2048 characters", violations.iterator().next().getMessage());
    }

    @Test
    void typeIsNull_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setType(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "type");

        assertEquals(1, violations.size());
        assertEquals("Type must not be null", violations.iterator().next().getMessage());
    }

    @Test
    void difficultyIsNull_thanValidationFails() {
        CreateQuestionRequest request = new CreateQuestionRequest();
        request.setDifficulty(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(request, "difficulty");

        assertEquals(1, violations.size());
        assertEquals("Difficulty must not be null", violations.iterator().next().getMessage());
    }

    @Test
    void contentIsNull_thanValidationFails() {
        CreateQuestionRequest questionRequest = new CreateQuestionRequest();
        questionRequest.setContent(null);
        Set<ConstraintViolation<CreateQuestionRequest>> violations = validator.validateProperty(questionRequest, "content");

        assertEquals(1, violations.size());
        assertEquals("Content must not be null", violations.iterator().next().getMessage());
    }

}
