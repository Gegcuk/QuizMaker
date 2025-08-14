package uk.gegc.quizmaker.shared.security.annotation;

import uk.gegc.quizmaker.features.user.domain.model.RoleName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    RoleName[] value();

    LogicalOperator operator() default LogicalOperator.OR;

    enum LogicalOperator {
        AND, OR
    }
} 