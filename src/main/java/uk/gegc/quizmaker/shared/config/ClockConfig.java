package uk.gegc.quizmaker.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Configuration for centralized Clock management.
 * Provides a single source of time for the entire application.
 * 
 * This configuration allows for:
 * - Consistent time handling across the application
 * - Easy testing with fixed clocks
 * - Configurable timezone support
 * - Better control over time-dependent operations
 */
@Configuration
public class ClockConfig {

    /**
     * Default timezone for the application.
     * Can be overridden via application properties.
     */
    @Value("${app.timezone:UTC}")
    private String timezone;

    /**
     * Creates the primary Clock bean for the application.
     * 
     * The Clock provides:
     * - Current time in the configured timezone
     * - Consistent time source for all time operations
     * - Easy mocking for testing
     * 
     * @return Clock instance configured with the application timezone
     */
    @Bean
    @Primary
    public Clock clock() {
        String configuredZone = timezone == null || timezone.isBlank()
                ? "UTC"
                : timezone.trim();
        return Clock.system(ZoneId.of(configuredZone));
    }

    /**
     * Creates a UTC Clock bean for operations that specifically need UTC time.
     * 
     * @return Clock instance configured for UTC
     */
    @Bean("utcClock")
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    /**
     * Creates a system default Clock bean for operations that need system timezone.
     * 
     * @return Clock instance using system default timezone
     */
    @Bean("systemClock")
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
