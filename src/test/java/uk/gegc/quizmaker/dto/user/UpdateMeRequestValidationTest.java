package uk.gegc.quizmaker.dto.user;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UpdateMeRequest Validation Tests")
class UpdateMeRequestValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Should be valid with all fields within limits")
    void shouldBeValidWithAllFieldsWithinLimits() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("theme", "dark");
        preferences.put("notifications", Map.of("email", true, "push", false));

        UpdateMeRequest request = new UpdateMeRequest(
                "John Doe",
                "Software developer passionate about education",
                preferences
        );

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with null fields")
    void shouldBeValidWithNullFields() {
        UpdateMeRequest request = new UpdateMeRequest(null, null, null);

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with empty strings")
    void shouldBeValidWithEmptyStrings() {
        UpdateMeRequest request = new UpdateMeRequest("", "", new HashMap<>());

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with display name at maximum length")
    void shouldBeValidWithDisplayNameAtMaximumLength() {
        String maxLengthDisplayName = "A".repeat(50);
        UpdateMeRequest request = new UpdateMeRequest(maxLengthDisplayName, "Bio", null);

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with bio at maximum length")
    void shouldBeValidWithBioAtMaximumLength() {
        String maxLengthBio = "A".repeat(500);
        UpdateMeRequest request = new UpdateMeRequest("John", maxLengthBio, null);

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should fail validation with display name exceeding maximum length")
    void shouldFailValidationWithDisplayNameExceedingMaximumLength() {
        String tooLongDisplayName = "A".repeat(51);
        UpdateMeRequest request = new UpdateMeRequest(tooLongDisplayName, "Bio", null);

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Should have validation violations");

        ConstraintViolation<UpdateMeRequest> violation = violations.iterator().next();
        assertEquals("displayName", violation.getPropertyPath().toString());
        assertEquals("Display name must not exceed 50 characters", violation.getMessage());
    }

    @Test
    @DisplayName("Should fail validation with bio exceeding maximum length")
    void shouldFailValidationWithBioExceedingMaximumLength() {
        String tooLongBio = "A".repeat(501);
        UpdateMeRequest request = new UpdateMeRequest("John", tooLongBio, null);

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Should have validation violations");

        ConstraintViolation<UpdateMeRequest> violation = violations.iterator().next();
        assertEquals("bio", violation.getPropertyPath().toString());
        assertEquals("Bio must not exceed 500 characters", violation.getMessage());
    }

    @Test
    @DisplayName("Should be valid with complex preferences object")
    void shouldBeValidWithComplexPreferencesObject() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("theme", "dark");
        preferences.put("language", "en");
        preferences.put("notifications", Map.of(
                "email", true,
                "push", false,
                "sms", false
        ));
        preferences.put("privacy", Map.of(
                "profileVisibility", "public",
                "showEmail", false
        ));

        UpdateMeRequest request = new UpdateMeRequest("John", "Bio", preferences);

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with special characters in display name")
    void shouldBeValidWithSpecialCharactersInDisplayName() {
        UpdateMeRequest request = new UpdateMeRequest(
                "John-Doe_123 @#$%",
                "Bio with special chars: @#$%^&*()",
                null
        );

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with unicode characters")
    void shouldBeValidWithUnicodeCharacters() {
        UpdateMeRequest request = new UpdateMeRequest(
                "José María",
                "Bio with unicode: 你好世界 🌍",
                null
        );

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with whitespace-only strings")
    void shouldBeValidWithWhitespaceOnlyStrings() {
        UpdateMeRequest request = new UpdateMeRequest("   ", "   ", null);

        Set<ConstraintViolation<UpdateMeRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }
}

