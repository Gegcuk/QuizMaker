package uk.gegc.quizmaker.features.quiz.config;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Type-safe configuration for quiz defaults.
 */
@Component
@Data
@Validated
@ConfigurationProperties(prefix = "quiz")
public class QuizDefaultsProperties {

    /**
     * Identifier of the category used when quiz creation requests omit or reference an invalid category.
     */
    @NotNull(message = "Property quiz.default-category-id must be configured")
    private UUID defaultCategoryId;
}

