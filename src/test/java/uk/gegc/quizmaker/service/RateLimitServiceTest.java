package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.exception.RateLimitExceededException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimitService Tests")
class RateLimitServiceTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService();
    }

    @Test
    @DisplayName("First request should pass")
    void firstRequest_ShouldPass() {
        // When & Then
        assertDoesNotThrow(() -> 
            rateLimitService.checkRateLimit("test-operation", "test-key")
        );
    }

    @Test
    @DisplayName("Multiple requests within limit should pass")
    void multipleRequestsWithinLimit_ShouldPass() {
        // Given
        String operation = "test-operation";
        String key = "test-key";

        // When & Then
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> 
                rateLimitService.checkRateLimit(operation, key)
            );
        }
    }

    @Test
    @DisplayName("Request exceeding limit should throw RateLimitExceededException")
    void requestExceedingLimit_ShouldThrowException() {
        // Given
        String operation = "test-operation";
        String key = "test-key";

        // When - Make 5 requests (at the limit)
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkRateLimit(operation, key);
        }

        // Then - 6th request should throw exception
        RateLimitExceededException exception = assertThrows(RateLimitExceededException.class, () ->
            rateLimitService.checkRateLimit(operation, key)
        );

        assertEquals("Too many requests for test-operation", exception.getMessage());
        assertTrue(exception.getRetryAfterSeconds() > 0);
        assertTrue(exception.getRetryAfterSeconds() <= 60);
    }

    @Test
    @DisplayName("Different keys should have separate limits")
    void differentKeys_ShouldHaveSeparateLimits() {
        // Given
        String operation = "test-operation";
        String key1 = "key1";
        String key2 = "key2";

        // When - Exceed limit for key1
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkRateLimit(operation, key1);
        }

        // Then - key1 should be rate limited
        assertThrows(RateLimitExceededException.class, () ->
            rateLimitService.checkRateLimit(operation, key1)
        );

        // But key2 should still work
        assertDoesNotThrow(() ->
            rateLimitService.checkRateLimit(operation, key2)
        );
    }

    @Test
    @DisplayName("Different operations should have separate limits")
    void differentOperations_ShouldHaveSeparateLimits() {
        // Given
        String operation1 = "operation1";
        String operation2 = "operation2";
        String key = "test-key";

        // When - Exceed limit for operation1
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkRateLimit(operation1, key);
        }

        // Then - operation1 should be rate limited
        assertThrows(RateLimitExceededException.class, () ->
            rateLimitService.checkRateLimit(operation1, key)
        );

        // But operation2 should still work
        assertDoesNotThrow(() ->
            rateLimitService.checkRateLimit(operation2, key)
        );
    }

    @Test
    @DisplayName("Retry-after should be calculated correctly")
    void retryAfter_ShouldBeCalculatedCorrectly() {
        // Given
        String operation = "test-operation";
        String key = "test-key";

        // When - Make 5 requests to hit the limit
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkRateLimit(operation, key);
        }

        // Then - 6th request should throw exception with retry-after
        RateLimitExceededException exception = assertThrows(RateLimitExceededException.class, () ->
            rateLimitService.checkRateLimit(operation, key)
        );

        long retryAfter = exception.getRetryAfterSeconds();
        assertTrue(retryAfter > 0, "Retry-after should be positive");
        assertTrue(retryAfter <= 60, "Retry-after should not exceed 60 seconds");
        
        // The retry-after should be close to 60 seconds (within 5 seconds tolerance)
        // since we just hit the limit and the window should reset in about 1 minute
        assertTrue(retryAfter >= 55, "Retry-after should be close to 60 seconds");
    }
}
