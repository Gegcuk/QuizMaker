package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.exception.RateLimitExceededException;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    private final RateLimitService rateLimitService = new RateLimitService();

    @Test
    void checkRateLimit_WithinLimit_ShouldNotThrowException() {
        // Given
        String operation = "test";
        String key = "test-key";

        // When & Then - Should not throw exception for first 5 requests
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> rateLimitService.checkRateLimit(operation, key));
        }
    }

    @Test
    void checkRateLimit_ExceedsLimit_ShouldThrowRateLimitExceededException() {
        // Given
        String operation = "test";
        String key = "test-key";

        // When - Make 5 requests (within limit)
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkRateLimit(operation, key);
        }

        // Then - 6th request should throw exception
        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> rateLimitService.checkRateLimit(operation, key)
        );
        
        assertEquals("Too many requests for test", exception.getMessage());
    }

    @Test
    void checkRateLimit_DifferentKeys_ShouldHaveSeparateLimits() {
        // Given
        String operation = "test";
        String key1 = "key1";
        String key2 = "key2";

        // When - Exceed limit for key1
        for (int i = 0; i < 6; i++) {
            if (i < 5) {
                rateLimitService.checkRateLimit(operation, key1);
            } else {
                assertThrows(RateLimitExceededException.class, 
                    () -> rateLimitService.checkRateLimit(operation, key1));
            }
        }

        // Then - key2 should still be within limit
        assertDoesNotThrow(() -> rateLimitService.checkRateLimit(operation, key2));
    }
}
