package uk.gegc.quizmaker.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {
    String message() default "{password.composition}";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
} 