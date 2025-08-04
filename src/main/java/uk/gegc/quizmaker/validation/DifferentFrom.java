package uk.gegc.quizmaker.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = DifferentFromValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DifferentFrom {
    String message() default "{password.same}";
    
    String field();
    
    String notEqualTo();
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
} 