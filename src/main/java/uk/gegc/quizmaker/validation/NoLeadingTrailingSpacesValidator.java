package uk.gegc.quizmaker.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NoLeadingTrailingSpacesValidator implements ConstraintValidator<NoLeadingTrailingSpaces, String> {
    
    @Override
    public void initialize(NoLeadingTrailingSpaces constraintAnnotation) {
        // No initialization needed
    }
    
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // let @NotBlank/@NotNull handle null values
        }
        
        // Check if the string equals its trimmed version
        return value.equals(value.trim());
    }
} 