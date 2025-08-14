package uk.gegc.quizmaker.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = NoLeadingTrailingSpacesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoLeadingTrailingSpaces {
    String message() default "{no.leading.trailing.spaces}";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
} 