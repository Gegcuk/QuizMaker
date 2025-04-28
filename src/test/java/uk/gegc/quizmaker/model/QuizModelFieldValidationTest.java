package uk.gegc.quizmaker.model;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;


import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.model.quizManagement.Quiz;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QuizModelFieldValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUpValidator(){
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void whenTitleTooShort_thenValidationFails(){
        Quiz quiz = new Quiz();
        quiz.setTitle("Hi");
        Set<ConstraintViolation<Quiz>> violations = validator.validateProperty(quiz, "title");

        assertEquals(1, violations.size(), "Expected one violation on title");
        ConstraintViolation<Quiz> violation = violations.iterator().next();
        assertEquals("Title length must be between 3 and 100 characters", violation.getMessage());
    }

    @Test
    void whenTitleTooLong_thenValidationFails(){
        Quiz quiz = new Quiz();
        quiz.setTitle("hi".repeat(52));
        Set<ConstraintViolation<Quiz>> violations = validator.validateProperty(quiz, "title");

        assertEquals(1, violations.size(), "Expected one violation on title");
        ConstraintViolation<Quiz> violation = violations.iterator().next();
        assertEquals("Title length must be between 3 and 100 characters", violation.getMessage());
    }

    @Test
    void whenDescriptionTooLong_thenValidationFails(){
        Quiz quiz = new Quiz();
        quiz.setDescription("hi".repeat(501));
        Set<ConstraintViolation<Quiz>> violations = validator.validateProperty(quiz, "description");

        assertEquals(1, violations.size(), "Expected 1 violation on description");
        ConstraintViolation<Quiz> violation = violations.iterator().next();
        assertEquals("Description must be at most 1000 characters long", violation.getMessage());
    }

    @Test
    void whenEstimatedTimeTooSmall_thenValidationFails(){
        Quiz quiz = new Quiz();
        quiz.setEstimatedTime(0);
        Set<ConstraintViolation<Quiz>> violations = validator.validateProperty(quiz, "estimatedTime");

        assertEquals(1, violations.size());
        ConstraintViolation<Quiz> violation = violations.iterator().next();
        assertEquals("Estimated time can't be less than 1 minute", violation.getMessage());
    }

    @Test
    void whenEstimatedTimeTooBig_thenValidationFails(){
        Quiz quiz = new Quiz();
        quiz.setEstimatedTime(181);
        Set<ConstraintViolation<Quiz>> violations = validator.validateProperty(quiz, "estimatedTime");


        assertEquals(1, violations.size());
        ConstraintViolation<Quiz> violation = violations.iterator().next();
        assertEquals("Estimated time can't be more than 180 minutes", violation.getMessage());
    }

    @Test
    void whenTimerDurationTooSmall_thenValidationFails(){
        Quiz quiz = new Quiz();
        quiz.setTimerDuration(0);
        Set<ConstraintViolation<Quiz>> violations = validator.validateProperty(quiz,"timerDuration");

        assertEquals(1, violations.size());
        assertEquals("Timer duration must be at least 1 minute", violations.iterator().next().getMessage());
    }

    @Test
    void whenTimerDurationTooBig_thenValidationFails(){
        Quiz quiz = new Quiz();
        quiz.setTimerDuration(181);
        Set<ConstraintViolation<Quiz>> violations = validator.validateProperty(quiz,"timerDuration");

        assertEquals(1, violations.size());
        assertEquals("Timer duration must be at most 180 minutes", violations.iterator().next().getMessage());
    }
}
