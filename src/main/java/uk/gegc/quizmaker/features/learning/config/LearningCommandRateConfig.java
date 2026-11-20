package uk.gegc.quizmaker.features.learning.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "quizmaker.learning.command-rate")
public class LearningCommandRateConfig {
    public static final int MAX_BURST = 20;
    @Min(1) @Max(60)
    private int perMinute = 60;
    @Min(1) @Max(MAX_BURST)
    private int burst = MAX_BURST;

    public int getPerMinute() { return perMinute; }
    public void setPerMinute(int perMinute) { this.perMinute = perMinute; }
    public int getBurst() { return burst; }
    public void setBurst(int burst) { this.burst = burst; }
}
