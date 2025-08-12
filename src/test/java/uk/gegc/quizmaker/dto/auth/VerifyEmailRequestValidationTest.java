package uk.gegc.quizmaker.dto.auth;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VerifyEmailRequest Validation Tests")
class VerifyEmailRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("valid request should pass validation")
    void validRequest_ShouldPassValidation() {
        // Given
        VerifyEmailRequest request = new VerifyEmailRequest("valid-token-here");

        // When
        Set<ConstraintViolation<VerifyEmailRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("blank token should fail validation")
    void blankToken_ShouldFailValidation(String blankToken) {
        // Given
        VerifyEmailRequest request = new VerifyEmailRequest(blankToken);

        // When
        Set<ConstraintViolation<VerifyEmailRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("token") && 
                        v.getMessage().contains("required")));
    }

    @Test
    @DisplayName("null token should fail validation")
    void nullToken_ShouldFailValidation() {
        // Given
        VerifyEmailRequest request = new VerifyEmailRequest(null);

        // When
        Set<ConstraintViolation<VerifyEmailRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("token") && 
                        v.getMessage().contains("required")));
    }
}
