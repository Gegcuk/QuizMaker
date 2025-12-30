package uk.gegc.quizmaker.features.bugreport.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.bug-report")
public class BugReportProperties {

    /**
     * Email address that should receive bug report notifications.
     */
    @NotBlank(message = "Bug report notification recipient must be configured")
    private String recipient = "gegcuk@gmail.com";

    /**
     * Subject line used for bug report notification emails.
     */
    @NotBlank(message = "Bug report notification subject must be configured")
    private String subject = "New Bug Report";

    /**
     * Simple rate limit (per minute) applied to public bug submissions.
     */
    private int rateLimitPerMinute = 5;
}
