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
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("null token should fail validation")
    void nullToken_ShouldFailValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest(null, "ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("token") && 
                        v.getMessage().contains("blank")));
    }

    @Test
    @DisplayName("empty token should fail validation")
    void emptyToken_ShouldFailValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("", "ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("token") && 
                        v.getMessage().contains("blank")));
    }

    @Test
    @DisplayName("blank token should fail validation")
    void blankToken_ShouldFailValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("   ", "ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("token") && 
                        v.getMessage().contains("blank")));
    }

    @Test
    @DisplayName("token too long should fail validation")
    void tokenTooLong_ShouldFailValidation() {
        // Given
        String longToken = "a".repeat(513); // 513 characters (max is 512)
        ResetPasswordRequest request = new ResetPasswordRequest(longToken, "ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("token") && 
                        v.getMessage().contains("must not exceed 512 characters")));
    }

    @Test
    @DisplayName("null password should fail validation")
    void nullPassword_ShouldFailValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", null);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword") && 
                        v.getMessage().contains("blank")));
    }

    @Test
    @DisplayName("empty password should fail validation")
    void emptyPassword_ShouldFailValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword") && 
                        v.getMessage().contains("blank")));
    }

    @Test
    @DisplayName("blank password should fail validation")
    void blankPassword_ShouldFailValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "   ");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword") && 
                        v.getMessage().contains("blank")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "1234567"}) // Less than 8 characters
    @DisplayName("password too short should fail validation")
    void passwordTooShort_ShouldFailValidation(String shortPassword) {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", shortPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword") && 
                        v.getMessage().contains("between 8 and 100 characters")));
    }

    @Test
    @DisplayName("password too long should fail validation")
    void passwordTooLong_ShouldFailValidation() {
        // Given
        String longPassword = "a".repeat(101); // 101 characters (max is 100)
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", longPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword") && 
                        v.getMessage().contains("between 8 and 100 characters")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password123", // No special character
            "PASSWORD123", // No lowercase
            "Password",    // No number
            "Pass123"      // Too short
    })
    @DisplayName("invalid password format should fail validation")
    void invalidPasswordFormat_ShouldFailValidation(String invalidPassword) {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", invalidPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ValidP@ssw0rd123!",
            "MyS3cr3tP@ss!",
            "Str0ngP@ssw0rd#",
            "C0mpl3xP@ss$"
    })
    @DisplayName("valid password format should pass validation")
    void validPasswordFormat_ShouldPassValidation(String validPassword) {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", validPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty(), "Should have no validation violations for password: " + validPassword);
    }

    @Test
    @DisplayName("both null fields should fail validation")
    void bothNullFields_ShouldFailValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest(null, null);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertEquals(2, violations.size());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("token")));
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword")));
    }

    @Test
    @DisplayName("token with special characters should pass validation")
    void tokenWithSpecialCharacters_ShouldPassValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("token-with-special-chars_123", "ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("token at max length should pass validation")
    void tokenAtMaxLength_ShouldPassValidation() {
        // Given
        String maxLengthToken = "a".repeat(512); // Exactly 512 characters
        ResetPasswordRequest request = new ResetPasswordRequest(maxLengthToken, "ValidP@ssw0rd123!");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("password at min length should pass validation")
    void passwordAtMinLength_ShouldPassValidation() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "ValidP@1");

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("password at max length should pass validation")
    void passwordAtMaxLength_ShouldPassValidation() {
        // Given
        String maxLengthPassword = "ValidP@ssw0rd123!".repeat(6).substring(0, 100); // Exactly 100 characters
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", maxLengthPassword);

        // When
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);

        // Then
        assertTrue(violations.isEmpty());
    }
}
