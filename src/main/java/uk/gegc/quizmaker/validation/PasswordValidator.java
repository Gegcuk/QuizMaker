package uk.gegc.quizmaker.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {
    
    // Password must contain:
    // - At least one uppercase letter (Unicode)
    // - At least one lowercase letter (Unicode)
    // - At least one digit
    // - At least one special character (any non-letter and non-number)
    // - No spaces
    // Note: Length is enforced by @Size, so we don't duplicate that check here
    private static final String PASSWORD_PATTERN = 
        "^(?=\\S+$)(?=.*\\p{Lu})(?=.*\\p{Ll})(?=.*\\d)(?=.*[^\\p{L}\\p{N}]).+$";
    
    private static final Pattern pattern = Pattern.compile(PASSWORD_PATTERN);
    
    @Override
    public void initialize(ValidPassword constraintAnnotation) {
        // No initialization needed
    }
    
    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return true; // let @NotBlank/@NotNull handle null values
        }
        
        return pattern.matcher(password).matches();
    }
} 