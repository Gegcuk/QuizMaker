package uk.gegc.quizmaker.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Test configuration for Clock management in tests.
 * 
 * This configuration provides a fixed Clock for testing purposes,
 * allowing tests to have predictable time behavior and avoid
 * flaky tests due to timing issues.
 */
@TestConfiguration
@Profile("test")
public class TestClockConfig {

    /**
     * Fixed instant for testing - January 1, 2024, 12:00:00 UTC
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-01-01T12:00:00Z");

    /**
     * Creates a fixed Clock for testing.
     * 
     * This Clock always returns the same time, making tests predictable
     * and avoiding timing-related flakiness.
     * 
     * @return Fixed Clock instance for testing
     */
    @Bean
    @Primary
    @Profile("test")
    public Clock testClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
    }

    /**
     * Gets the fixed instant used in tests.
     * 
     * @return The fixed instant for testing
     */
    public static Instant getFixedInstant() {
        return FIXED_INSTANT;
    }
}
