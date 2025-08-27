package uk.gegc.quizmaker.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.CONCURRENT)
public class CreateQuizRequestValidationTest {

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

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Title length must be between 3 and 100 characters");
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

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Description must be at most 1000 characters long");
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

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Estimated time can't be less than 1 minute");
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

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Estimated time can't be more than 180 minutes");
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

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Timer duration must be at least 1 minute");
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

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Timer duration must be at most 180 minutes");
    }
}