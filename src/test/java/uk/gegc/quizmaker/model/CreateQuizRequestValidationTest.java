package uk.gegc.quizmaker.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CreateQuizRequestValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private CreateQuizRequest baseRequest() {
        return new CreateQuizRequest(
                "Valid title",
                "A valid description",
                Visibility.PUBLIC,
                Difficulty.EASY,
                false,
                false,
                10,
                5,
                UUID.randomUUID(),
                List.of(UUID.randomUUID())
        );
    }

    @Test
    void whenTitleTooShort_thenValidationFails() {
        var req = new CreateQuizRequest(
                "Hi",                       // too short
                "desc",
                null,                       // will default to PRIVATE
                null,                       // will default to MEDIUM
                false, false,
                10, 5,
                null, List.of()
        );
        Set<ConstraintViolation<CreateQuizRequest>> violations =
                validator.validateProperty(req, "title");

        assertEquals(1, violations.size());
        assertEquals(
                "Title length must be between 3 and 100 characters",
                violations.iterator().next().getMessage()
        );
    }

    @Test
    void whenDescriptionTooLong_thenValidationFails() {
        String longDesc = "x".repeat(1001);
        var req = new CreateQuizRequest(
                "Valid title",
                longDesc, null, null,
                false, false, 10, 5,
                null, null
        );
        Set<ConstraintViolation<CreateQuizRequest>> violations =
                validator.validateProperty(req, "description");

        assertEquals(1, violations.size());
        assertEquals(
                "Description must be at most 1000 characters long",
                violations.iterator().next().getMessage()
        );
    }

    @Test
    void whenEstimatedTimeTooSmall_thenValidationFails() {
        var req = new CreateQuizRequest(
                "Valid title", "desc", null, null,
                false, false,
                0,    // too small
                5,
                null, null
        );
        Set<ConstraintViolation<CreateQuizRequest>> violations =
                validator.validateProperty(req, "estimatedTime");

        assertEquals(1, violations.size());
        assertEquals(
                "Estimated time can't be less than 1 minute",
                violations.iterator().next().getMessage()
        );
    }

    @Test
    void whenEstimatedTimeTooBig_thenValidationFails() {
        var req = new CreateQuizRequest(
                "Valid title", "desc", null, null,
                false, false,
                181,  // too big
                5,
                null, null
        );
        Set<ConstraintViolation<CreateQuizRequest>> violations =
                validator.validateProperty(req, "estimatedTime");

        assertEquals(1, violations.size());
        assertEquals(
                "Estimated time can't be more than 180 minutes",
                violations.iterator().next().getMessage()
        );
    }

    @Test
    void whenTimerDurationTooSmall_thenValidationFails() {
        var req = new CreateQuizRequest(
                "Valid title", "desc", null, null,
                false, false,
                10,
                0,    // too small
                null, null
        );
        Set<ConstraintViolation<CreateQuizRequest>> violations =
                validator.validateProperty(req, "timerDuration");

        assertEquals(1, violations.size());
        assertEquals(
                "Timer duration must be at least 1 minute",
                violations.iterator().next().getMessage()
        );
    }

    @Test
    void whenTimerDurationTooBig_thenValidationFails() {
        var req = new CreateQuizRequest(
                "Valid title", "desc", null, null,
                false, false,
                10,
                181,  // too big
                null, null
        );
        Set<ConstraintViolation<CreateQuizRequest>> violations =
                validator.validateProperty(req, "timerDuration");

        assertEquals(1, violations.size());
        assertEquals(
                "Timer duration must be at most 180 minutes",
                violations.iterator().next().getMessage()
        );
    }
}