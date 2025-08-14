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
import uk.gegc.quizmaker.features.auth.api.dto.ResetPasswordRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResetPasswordRequest Validation Tests")
class ResetPasswordRequestValidationTest {

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
        ResetPasswordRequest request = new ResetPasswordRequest("ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "1234567"}) // Less than 8 characters
    @DisplayName("password too short should fail validation")
    void passwordTooShort_ShouldFailValidation(String shortPassword) {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest(shortPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("blank password should fail validation")
    void blankPassword_ShouldFailValidation(String blankPassword) {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest(blankPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword") && 
                        v.getMessage().contains("required")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "password", "12345678", "abcdefgh"})
    @DisplayName("weak password should fail validation")
    void weakPassword_ShouldFailValidation(String weakPassword) {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest(weakPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword")));
    }

    @Test
    @DisplayName("password at max length should pass validation")
    void passwordAtMaxLength_ShouldPassValidation() {
        // Given
        String maxLengthPassword = "ValidP@ssw0rd123!".repeat(6).substring(0, 100); // Exactly 100 characters
        ResetPasswordRequest request = new ResetPasswordRequest(maxLengthPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }
}
