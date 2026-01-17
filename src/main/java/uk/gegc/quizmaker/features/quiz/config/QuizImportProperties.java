package uk.gegc.quizmaker.features.quiz.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Data
@Validated
@ConfigurationProperties(prefix = "quiz.import")
public class QuizImportProperties {

    @NotNull(message = "Property quiz.import.max-items must be configured")
    @Min(value = 1, message = "quiz.import.max-items must be at least 1")
    private Integer maxItems = 1000;

    @NotNull(message = "Property quiz.import.rate-limit-per-minute must be configured")
    @Min(value = 1, message = "quiz.import.rate-limit-per-minute must be at least 1")
    private Integer rateLimitPerMinute = 10;
}
