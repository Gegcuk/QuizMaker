package uk.gegc.quizmaker.shared.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Utility class for date and time operations using the centralized Clock.
 * 
 * This class provides a consistent way to get current time throughout the application
 * and ensures all time operations use the same Clock instance.
 */
@Component
public class DateUtils {

    private final Clock clock;

    @Autowired
    public DateUtils(Clock clock) {
        this.clock = clock;
    }

    /**
     * Gets the current time as an Instant using the application's Clock.
     * 
     * @return Current Instant
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * Gets the current time as LocalDateTime using the application's Clock.
     * 
     * @return Current LocalDateTime in the application's timezone
     */
    public LocalDateTime nowLocalDateTime() {
        return LocalDateTime.now(clock);
    }

    /**
     * Gets the current time as LocalDateTime in UTC.
     * 
     * @return Current LocalDateTime in UTC
     */
    public LocalDateTime nowUtc() {
        return LocalDateTime.now(Clock.systemUTC());
    }

    /**
     * Gets the current time as milliseconds since epoch.
     * 
     * @return Current time in milliseconds
     */
    public long currentTimeMillis() {
        return clock.millis();
    }

    /**
     * Gets the Clock instance used by this utility.
     * 
     * @return The Clock instance
     */
    public Clock getClock() {
        return clock;
    }

    /**
     * Gets the timezone of the application's Clock.
     * 
     * @return The ZoneId of the Clock
     */
    public ZoneId getZone() {
        return clock.getZone();
    }
}
