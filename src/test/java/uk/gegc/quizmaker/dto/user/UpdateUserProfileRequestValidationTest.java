package uk.gegc.quizmaker.dto.user;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.user.api.dto.UpdateUserProfileRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UpdateUserProfileRequest Validation Tests")
class UpdateUserProfileRequestValidationTest {

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

        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                "John Doe",
                "Software developer passionate about education",
                preferences
        );

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with null fields")
    void shouldBeValidWithNullFields() {
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(null, null, null);

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with empty strings")
    void shouldBeValidWithEmptyStrings() {
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("", "", new HashMap<>());

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with display name at maximum length")
    void shouldBeValidWithDisplayNameAtMaximumLength() {
        String maxLengthDisplayName = "A".repeat(50);
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(maxLengthDisplayName, "Bio", null);

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with bio at maximum length")
    void shouldBeValidWithBioAtMaximumLength() {
        String maxLengthBio = "A".repeat(500);
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("John", maxLengthBio, null);

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should fail validation with display name exceeding maximum length")
    void shouldFailValidationWithDisplayNameExceedingMaximumLength() {
        String tooLongDisplayName = "A".repeat(51);
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(tooLongDisplayName, "Bio", null);

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Should have validation violations");

        ConstraintViolation<UpdateUserProfileRequest> violation = violations.iterator().next();
        assertEquals("displayName", violation.getPropertyPath().toString());
        assertEquals("Display name must not exceed 50 characters", violation.getMessage());
    }

    @Test
    @DisplayName("Should fail validation with bio exceeding maximum length")
    void shouldFailValidationWithBioExceedingMaximumLength() {
        String tooLongBio = "A".repeat(501);
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("John", tooLongBio, null);

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Should have validation violations");

        ConstraintViolation<UpdateUserProfileRequest> violation = violations.iterator().next();
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

        UpdateUserProfileRequest request = new UpdateUserProfileRequest("John", "Bio", preferences);

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with special characters in display name")
    void shouldBeValidWithSpecialCharactersInDisplayName() {
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                "John-Doe_123 @#$%",
                "Bio with special chars: @#$%^&*()",
                null
        );

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with unicode characters")
    void shouldBeValidWithUnicodeCharacters() {
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                "José María",
                "Bio with unicode: 你好世界 🌍",
                null
        );

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }

    @Test
    @DisplayName("Should be valid with whitespace-only strings")
    void shouldBeValidWithWhitespaceOnlyStrings() {
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("   ", "   ", null);

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Should have no validation violations");
    }
}

