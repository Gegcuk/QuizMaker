package uk.gegc.quizmaker.shared.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireResourceOwnership {
    String resourceParam();

    String resourceType();

    String ownerField() default "userId";
} 