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
import uk.gegc.quizmaker.shared.validation.PasswordValidator;
import uk.gegc.quizmaker.shared.validation.ValidPassword;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class PasswordValidatorTest {

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

    // Test class to hold password for validation
    static class PasswordHolder {
        @ValidPassword
        private String password;

        public PasswordHolder(String password) {
            this.password = password;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "P@ssw0rd!",          // Valid: has all requirements
        "Str0ng!Pass",        // Valid: has all requirements
        "MyP@ssw0rd123",      // Valid: has all requirements
        "Test123!ABC",        // Valid: has all requirements
        "Secure#Pass1",       // Valid: has all requirements
        "Café123!",           // Valid: Unicode letters
        "Ünic0de*",           // Valid: Unicode letters
        "Password1?",         // Valid: question mark as special char
        "Password1_",         // Valid: underscore as special char
        "Password1-",         // Valid: dash as special char
        "Password1(",         // Valid: parenthesis as special char
        "Password1)",         // Valid: parenthesis as special char
        "Pass1!"              // Valid: meets composition requirements (length handled by @Size)
    })
    void shouldAcceptValidPasswords(String password) {
        PasswordHolder holder = new PasswordHolder(password);
        Set<ConstraintViolation<PasswordHolder>> violations = validator.validate(holder);
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "password",           // Invalid: no uppercase, no digit, no special char
        "PASSWORD",           // Invalid: no lowercase, no digit, no special char
        "Password",           // Invalid: no digit, no special char
        "Password1",          // Invalid: no special char (letters and numbers only)
        "Password!",          // Invalid: no digit
        "password123!",       // Invalid: no uppercase
        "PASSWORD123!",       // Invalid: no lowercase
        "Passwordone!",       // Invalid: no digit
        "Pass word1!",        // Invalid: contains space
        "P@ssw0rd ",          // Invalid: trailing space
        " P@ssw0rd!",         // Invalid: leading space
        ""                    // Invalid: empty
    })
    void shouldRejectInvalidPasswords(String password) {
        PasswordHolder holder = new PasswordHolder(password);
        Set<ConstraintViolation<PasswordHolder>> violations = validator.validate(holder);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void shouldReturnTrueForNullPassword() {
        // Updated behavior: null should return true (let @NotBlank handle it)
        PasswordValidator validator = new PasswordValidator();
        boolean result = validator.isValid(null, null);
        assertThat(result).isTrue();
    }

    @Test
    void shouldAcceptWideSpecialCharacterRange() {
        // Now uses [^\p{L}\p{N}] pattern which accepts any non-letter, non-number character
        String[] specialCharPasswords = {
            "Password1@",
            "Password1#",
            "Password1$",
            "Password1%",
            "Password1^",
            "Password1&",
            "Password1+",
            "Password1=",
            "Password1!",
            "Password1?",
            "Password1*",
            "Password1(",
            "Password1)",
            "Password1_",
            "Password1-",
            "Password1[",
            "Password1]",
            "Password1{",
            "Password1}",
            "Password1|",
            "Password1\\",
            "Password1/",
            "Password1:",
            "Password1;",
            "Password1\"",
            "Password1'",
            "Password1<",
            "Password1>",
            "Password1,",
            "Password1.",
            "Password1~",
            "Password1`"
        };

        for (String password : specialCharPasswords) {
            PasswordHolder holder = new PasswordHolder(password);
            Set<ConstraintViolation<PasswordHolder>> violations = validator.validate(holder);
            assertThat(violations)
                .as("Password '%s' should be valid", password)
                .isEmpty();
        }
    }

    @Test
    void shouldAcceptUnicodeLetters() {
        // Using \p{Lu} and \p{Ll} now provides full Unicode support
        String[] unicodePasswords = {
            "Café123!",           // French
            "Naïve123!",          // French
            "Résumé123!",         // French
            "München123!",        // German
            "Zürich123!",         // German
            "España123!",         // Spanish
            "Москва123!",         // Russian (Cyrillic)
            "Ελλάδα123!",         // Greek
        };

        for (String password : unicodePasswords) {
            PasswordHolder holder = new PasswordHolder(password);
            Set<ConstraintViolation<PasswordHolder>> violations = validator.validate(holder);
            
            assertThat(violations)
                .as("Password '%s' should be valid", password)
                .isEmpty();
        }
    }

    @Test
    void shouldRejectPasswordsWithOnlyPartialRequirements() {
        // Test passwords that meet some but not all requirements
        String[][] testCases = {
            {"Abcdefgh", "no digit or special character"},
            {"12345678", "no uppercase, lowercase, or special character"},
            {"!@#$%^&*", "no uppercase, lowercase, or digit"},
            {"Abcd1234", "no special character (only letters and numbers)"},
            {"abcd!@#$", "no uppercase or digit"},
            {"ABCD!@#$", "no lowercase or digit"}
        };

        for (String[] testCase : testCases) {
            PasswordHolder holder = new PasswordHolder(testCase[0]);
            Set<ConstraintViolation<PasswordHolder>> violations = validator.validate(holder);
            assertThat(violations)
                .as("Password '%s' should be invalid because: %s", testCase[0], testCase[1])
                .isNotEmpty();
        }
    }

    @Test
    void shouldRejectJapaneseWithoutProperCase() {
        // Japanese doesn't have uppercase/lowercase distinction, so this should fail
        PasswordHolder holder = new PasswordHolder("日本123!");
        Set<ConstraintViolation<PasswordHolder>> violations = validator.validate(holder);
        assertThat(violations)
            .as("Japanese password without case distinction should be invalid")
            .isNotEmpty();
    }

    @Test
    void shouldDemonstrateCompositionVsLengthSeparation() {
        // This test demonstrates the clean separation of concerns:
        // @ValidPassword handles composition, @Size handles length
        
        // Short but composition-valid password (6 chars)
        PasswordHolder shortButValid = new PasswordHolder("Pass1!");
        Set<ConstraintViolation<PasswordHolder>> violations = validator.validate(shortButValid);
        assertThat(violations)
            .as("Short password with valid composition should pass @ValidPassword (length handled by @Size)")
            .isEmpty();
        
        // Long but composition-invalid password
        PasswordHolder longButInvalid = new PasswordHolder("verylongpasswordwithnouppercaseorspecialchars123");
        Set<ConstraintViolation<PasswordHolder>> violationsInvalid = validator.validate(longButInvalid);
        assertThat(violationsInvalid)
            .as("Long password without proper composition should fail @ValidPassword")
            .isNotEmpty();
    }
} 