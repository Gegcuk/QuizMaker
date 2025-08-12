package uk.gegc.quizmaker.dto.auth;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthRequestValidationTest {

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

    @Test
    void loginRequest_shouldAcceptAnyPasswordFormat() {
        // LoginRequest no longer validates password complexity - only checks @NotBlank
        LoginRequest request = new LoginRequest("testuser", "weak");
        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void loginRequest_shouldRejectBlankPassword() {
        LoginRequest request = new LoginRequest("testuser", "");
        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("password");
    }

    @Test
    void registerRequest_shouldAcceptValidPassword() {
        RegisterRequest request = new RegisterRequest(
            "testuser", 
            "test@example.com", 
            "Valid@Pass123"
        );
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void registerRequest_shouldRejectWeakPassword() {
        RegisterRequest request = new RegisterRequest(
            "testuser", 
            "test@example.com", 
            "weak"
        );
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        
        // Should have 2 violations: @Size and @ValidPassword
        assertThat(violations).hasSize(2);
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("password");
    }

    @Test
    void registerRequest_shouldRejectUsernameWithSpaces() {
        RegisterRequest request = new RegisterRequest(
            " testuser ", 
            "test@example.com", 
            "Valid@Pass123"
        );
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("username");
    }

    @Test
    void registerRequest_shouldRejectEmailWithSpaces() {
        RegisterRequest request = new RegisterRequest(
            "testuser",
            " test@example.com ",  // email with leading/trailing spaces
            "ValidPass123!"
        );
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(2); // @NoLeadingTrailingSpaces and @Email violations
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("email");
    }

    @Test
    void registerRequest_shouldRejectOversizedEmail() {
        String longEmail = "a".repeat(250) + "@example.com"; // Over 254 chars
        RegisterRequest request = new RegisterRequest(
            "testuser",
            longEmail,
            "ValidPass123!"
        );
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(2); // @Size and @Email violations
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("email");
    }

    @Test
    void changePasswordRequest_shouldAcceptValidNewPassword() {
        ChangePasswordRequest request = new ChangePasswordRequest(
            "OldPassword123!", 
            "NewValid@Pass123"
        );
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void changePasswordRequest_shouldRejectWeakNewPassword() {
        ChangePasswordRequest request = new ChangePasswordRequest(
            "OldPassword123!", 
            "weak"
        );
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(2); // @Size and @ValidPassword
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("newPassword");
    }

    @Test
    void changePasswordRequest_shouldRejectSamePasswords() {
        // Test the @DifferentFrom constraint
        ChangePasswordRequest request = new ChangePasswordRequest(
            "SamePass123!",
            "SamePass123!"
        );
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        
        // The violation should now be attached to the newPassword field
        ConstraintViolation<ChangePasswordRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("newPassword");
    }

    @Test
    void changePasswordRequest_shouldSkipDifferentFromWhenCurrentPasswordIsBlank() {
        // @DifferentFrom should skip validation when currentPassword is blank
        // Only @NotBlank violation should be reported, no duplicate @DifferentFrom violation
        ChangePasswordRequest request = new ChangePasswordRequest(
            "",  // blank current password
            "NewValid@Pass123"
        );
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1); // Only @NotBlank violation
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("currentPassword");
    }

    @Test
    void changePasswordRequest_shouldSkipDifferentFromWhenNewPasswordIsBlank() {
        // @DifferentFrom should skip validation when newPassword is blank
        // Only @NotBlank, @Size, and @ValidPassword violations should be reported, no @DifferentFrom violation
        ChangePasswordRequest request = new ChangePasswordRequest(
            "OldPassword123!",
            ""  // blank new password
        );
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(3); // @NotBlank, @Size, and @ValidPassword violations
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("newPassword");
    }

    @Test
    void changePasswordRequest_shouldSkipDifferentFromWhenBothPasswordsAreBlank() {
        // @DifferentFrom should skip validation when both passwords are blank
        // Only @NotBlank violations should be reported, no @DifferentFrom violation
        ChangePasswordRequest request = new ChangePasswordRequest(
            "",  // blank current password
            ""   // blank new password
        );
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(4); // @NotBlank for current, @NotBlank, @Size, and @ValidPassword for new
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("currentPassword", "newPassword");
    }

    @Test
    void changePasswordRequest_shouldAcceptDifferentValidPasswords() {
        ChangePasswordRequest request = new ChangePasswordRequest(
            "OldValid@Pass123",
            "NewValid@Pass456"
        );
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void resetPasswordRequest_shouldAcceptValidNewPassword() {
        ResetPasswordRequest request = new ResetPasswordRequest(
            "NewValid@Pass123"
        );
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void resetPasswordRequest_shouldRejectWeakNewPassword() {
        ResetPasswordRequest request = new ResetPasswordRequest(
            "weak"
        );
        Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1); // Only @ValidPassword violation (no @Size since "weak" is 4 chars)
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("newPassword");
    }

    @Test
    void forgotPasswordRequest_shouldAcceptValidEmail() {
        ForgotPasswordRequest request = new ForgotPasswordRequest("test@example.com");
        Set<ConstraintViolation<ForgotPasswordRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void forgotPasswordRequest_shouldRejectInvalidEmail() {
        ForgotPasswordRequest request = new ForgotPasswordRequest("invalid-email");
        Set<ConstraintViolation<ForgotPasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("email");
    }

    @Test
    void forgotPasswordRequest_shouldRejectEmailWithSpaces() {
        ForgotPasswordRequest request = new ForgotPasswordRequest(
            " test@example.com "  // email with leading/trailing spaces
        );
        Set<ConstraintViolation<ForgotPasswordRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(2); // @NoLeadingTrailingSpaces and @Email violations
        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsOnly("email");
    }
} 