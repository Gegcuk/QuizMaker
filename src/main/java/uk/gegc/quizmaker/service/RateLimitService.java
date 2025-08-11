package uk.gegc.quizmaker.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    
    private final Map<String, LocalDateTime> lastRequestTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> requestCount = new ConcurrentHashMap<>();
    
    public void checkRateLimit(String operation, String key) {
        String rateLimitKey = operation + ":" + key;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneMinuteAgo = now.minusMinutes(1);
        
        // Clean up old entries
        lastRequestTime.entrySet().removeIf(entry -> entry.getValue().isBefore(oneMinuteAgo));
        requestCount.entrySet().removeIf(entry -> lastRequestTime.get(entry.getKey()) == null);
        
        // Check if we have a recent request
        LocalDateTime lastRequest = lastRequestTime.get(rateLimitKey);
        if (lastRequest != null && lastRequest.isAfter(oneMinuteAgo)) {
            int count = requestCount.getOrDefault(rateLimitKey, 0);
            if (count >= 5) { // 5 requests per minute as per MVP plan
                throw new RuntimeException("Rate limit exceeded. Please try again later.");
            }
            requestCount.put(rateLimitKey, count + 1);
        } else {
            // First request in this window
            lastRequestTime.put(rateLimitKey, now);
            requestCount.put(rateLimitKey, 1);
        }
    }
}
