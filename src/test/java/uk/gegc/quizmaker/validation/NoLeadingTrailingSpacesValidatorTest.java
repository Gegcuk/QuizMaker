package uk.gegc.quizmaker.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class NoLeadingTrailingSpacesValidatorTest {

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

    // Test class to hold string for validation
    static class StringHolder {
        @NoLeadingTrailingSpaces
        private String value;

        public StringHolder(String value) {
            this.value = value;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "username",           // Valid: no spaces
        "user.name",          // Valid: no spaces
        "user-name",          // Valid: no spaces
        "user_name",          // Valid: no spaces
        "user123",            // Valid: no spaces
        "email@example.com",  // Valid: no spaces
        "a",                  // Valid: single character
        "multi word",         // Valid: internal spaces allowed
        "multiple   spaces"   // Valid: internal spaces allowed
    })
    void shouldAcceptValidStrings(String value) {
        StringHolder holder = new StringHolder(value);
        Set<ConstraintViolation<StringHolder>> violations = validator.validate(holder);
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " username",          // Invalid: leading space
        "username ",          // Invalid: trailing space
        " username ",         // Invalid: both leading and trailing
        "  username",         // Invalid: multiple leading spaces
        "username  ",         // Invalid: multiple trailing spaces
        "  username  ",       // Invalid: multiple leading and trailing
        " ",                  // Invalid: only space
        "  ",                 // Invalid: only spaces
        "\tusername",         // Invalid: leading tab
        "username\t",         // Invalid: trailing tab
        "\nusername",         // Invalid: leading newline
        "username\n"          // Invalid: trailing newline
    })
    void shouldRejectInvalidStrings(String value) {
        StringHolder holder = new StringHolder(value);
        Set<ConstraintViolation<StringHolder>> violations = validator.validate(holder);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void shouldReturnTrueForNullValue() {
        // null should return true (let @NotBlank handle it)
        NoLeadingTrailingSpacesValidator validator = new NoLeadingTrailingSpacesValidator();
        boolean result = validator.isValid(null, null);
        assertThat(result).isTrue();
    }

    @Test
    void shouldAcceptEmptyString() {
        StringHolder holder = new StringHolder("");
        Set<ConstraintViolation<StringHolder>> violations = validator.validate(holder);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldHandleUnicodeCharacters() {
        String[] unicodeStrings = {
            "café",               // Valid: no spaces
            "naïve",              // Valid: no spaces
            "résumé",             // Valid: no spaces
            "münchen",            // Valid: no spaces
            "москва",             // Valid: no spaces
            "ελλάδα",             // Valid: no spaces
            "日本"                // Valid: no spaces
        };

        for (String value : unicodeStrings) {
            StringHolder holder = new StringHolder(value);
            Set<ConstraintViolation<StringHolder>> violations = validator.validate(holder);
            assertThat(violations)
                .as("Unicode string '%s' should be valid", value)
                .isEmpty();
        }
    }

    @Test
    void shouldRejectUnicodeStringsWithSpaces() {
        String[] unicodeStringsWithSpaces = {
            " café",              // Invalid: leading space
            "café ",              // Invalid: trailing space
            " résumé ",           // Invalid: both
        };

        for (String value : unicodeStringsWithSpaces) {
            StringHolder holder = new StringHolder(value);
            Set<ConstraintViolation<StringHolder>> violations = validator.validate(holder);
            assertThat(violations)
                .as("Unicode string with spaces '%s' should be invalid", value)
                .isNotEmpty();
        }
    }
} 