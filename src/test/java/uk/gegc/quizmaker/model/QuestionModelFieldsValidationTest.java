package uk.gegc.quizmaker.model;


import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.model.question.Question;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

public class QuestionModelFieldsValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator(){
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    public void questionTextTooLong_thenValidationFails(){
        Question question = new Question();
        question.setQuestionText("hi".repeat(501));
        Set<ConstraintViolation<Question>> violations = validator.validateProperty(question, "questionText");

        assertEquals(1, violations.size());
        assertEquals("Question text length must be less than 1000 characters", violations.iterator().next().getMessage());
    }

    @Test
    public void hintTooLong_thenValidationFails(){
        Question question = new Question();
        question.setHint("hi".repeat(251));
        Set<ConstraintViolation<Question>> violations = validator.validateProperty(question, "hint");

        assertEquals(1, violations.size());
        assertEquals("Hint length must be less than 500 characters", violations.iterator().next().getMessage());
    }

    @Test
    public void explanationTooLong_thenValidationFails(){
        Question question = new Question();
        question.setExplanation("hi".repeat(1001));
        Set<ConstraintViolation<Question>> violations = validator.validateProperty(question, "explanation");

        assertEquals(1, violations.size());
        assertEquals("Explanation must be less than 2000 characters", violations.iterator().next().getMessage());
    }

    @Test
    public void attachmentUrlTooLong_thenValidationFails(){
        Question question = new Question();
        question.setAttachmentUrl("hi".repeat(1025));
        Set<ConstraintViolation<Question>> violations = validator.validateProperty(question, "attachmentUrl");

        assertEquals(1, violations.size());
        assertEquals("URL length is limited by 2048 characters", violations.iterator().next().getMessage());
    }
}
