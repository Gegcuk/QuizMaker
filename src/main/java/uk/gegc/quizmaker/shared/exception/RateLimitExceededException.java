package uk.gegc.quizmaker.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;
    
    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
    
    public RateLimitExceededException(String message) {
        this(message, 60); // Default 1 minute
    }
    
    public long getRetryAfterSeconds() { 
        return retryAfterSeconds; 
    }
}
