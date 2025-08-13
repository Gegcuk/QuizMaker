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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResendVerificationRequest Validation Tests")
class ResendVerificationRequestValidationTest {

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
        ResendVerificationRequest request = new ResendVerificationRequest("test@example.com");

        // When
        Set<ConstraintViolation<ResendVerificationRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("blank email should fail validation")
    void blankEmail_ShouldFailValidation(String blankEmail) {
        // Given
        ResendVerificationRequest request = new ResendVerificationRequest(blankEmail);

        // When
        Set<ConstraintViolation<ResendVerificationRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("email") && 
                        v.getMessage().contains("required")));
    }

    @Test
    @DisplayName("null email should fail validation")
    void nullEmail_ShouldFailValidation() {
        // Given
        ResendVerificationRequest request = new ResendVerificationRequest(null);

        // When
        Set<ConstraintViolation<ResendVerificationRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("email") && 
                        v.getMessage().contains("required")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-email", "test@", "@example.com", "test.example.com"})
    @DisplayName("invalid email format should fail validation")
    void invalidEmailFormat_ShouldFailValidation(String invalidEmail) {
        // Given
        ResendVerificationRequest request = new ResendVerificationRequest(invalidEmail);

        // When
        Set<ConstraintViolation<ResendVerificationRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("email") && 
                        v.getMessage().contains("Invalid email format")));
    }
}
