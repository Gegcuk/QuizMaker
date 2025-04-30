package uk.gegc.quizmaker.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UpdateQuestionRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator(){
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }


    @Test
    void questionTextTooLong_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setQuestionText("x".repeat(1001));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertEquals(1, violations.size());
        assertEquals("Question text length must be between 3 and 1000 characters", violations.iterator().next().getMessage());
    }

    @Test
    void questionTextTooShort_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setQuestionText("x".repeat(2));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "questionText");

        assertEquals(1, violations.size());
        assertEquals("Question text length must be between 3 and 1000 characters", violations.iterator().next().getMessage());
    }

    @Test
    void hintTooLong_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setHint("x".repeat(501));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "hint");

        assertEquals(1, violations.size());
        assertEquals("Hint length must be less than 500 characters", violations.iterator().next().getMessage());
    }

    @Test
    void explanationTooLong_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setExplanation("x".repeat(2001));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "explanation");

        assertEquals(1, violations.size());
        assertEquals("Explanation must be less than 2000 characters", violations.iterator().next().getMessage());
    }

    @Test
    void attachmentUrlTooLong_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setAttachmentUrl("x".repeat(2049));
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "attachmentUrl");

        assertEquals(1, violations.size());
        assertEquals("URL length is limited by 2048 characters", violations.iterator().next().getMessage());
    }

    @Test
    void typeIsNull_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setType(null);
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "type");

        assertEquals(1, violations.size());
        assertEquals("Type must not be null", violations.iterator().next().getMessage());
    }

    @Test
    void difficultyIsNull_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setDifficulty(null);
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "difficulty");

        assertEquals(1, violations.size());
        assertEquals("Difficulty must not be null", violations.iterator().next().getMessage());
    }

    @Test
    void contentIsBlank_thanValidationFails(){
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setContent("");
        Set<ConstraintViolation<UpdateQuestionRequest>> violations = validator.validateProperty(request, "content");

        assertEquals(1, violations.size());
        assertEquals("Content must not be blank", violations.iterator().next().getMessage());
    }



}
