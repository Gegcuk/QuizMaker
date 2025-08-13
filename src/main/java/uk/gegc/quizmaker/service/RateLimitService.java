package uk.gegc.quizmaker.service;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.exception.RateLimitExceededException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    
    private final Map<String, LocalDateTime> lastRequestTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> requestCount = new ConcurrentHashMap<>();
    
    public void checkRateLimit(String operation, String key) {
        checkRateLimit(operation, key, 5);
    }

    public void checkRateLimit(String operation, String key, int limitPerMinute) {
        String rateLimitKey = operation + ":" + key;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneMinuteAgo = now.minusMinutes(1);
        
        // Clean up old entries
        lastRequestTime.entrySet().removeIf(entry -> entry.getValue().isBefore(oneMinuteAgo));
        requestCount.entrySet().removeIf(entry -> lastRequestTime.get(entry.getKey()) == null);
        
        // Check if we have a recent request
        LocalDateTime firstInWindow = lastRequestTime.get(rateLimitKey);
        if (firstInWindow != null && firstInWindow.isAfter(oneMinuteAgo)) {
            int count = requestCount.getOrDefault(rateLimitKey, 0);
            if (count >= limitPerMinute) {
                long retryAfter = java.time.Duration.between(now, firstInWindow.plusMinutes(1)).getSeconds();
                throw new RateLimitExceededException("Too many requests for " + operation, retryAfter);
            }
            requestCount.put(rateLimitKey, count + 1);
        } else {
            // First request in this window
            lastRequestTime.put(rateLimitKey, now);
            requestCount.put(rateLimitKey, 1);
        }
    }
}
