package uk.gegc.quizmaker.security.annotation;

import uk.gegc.quizmaker.features.user.domain.model.PermissionName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    PermissionName[] value();

    LogicalOperator operator() default LogicalOperator.OR;

    enum LogicalOperator {
        AND, OR
    }
} 