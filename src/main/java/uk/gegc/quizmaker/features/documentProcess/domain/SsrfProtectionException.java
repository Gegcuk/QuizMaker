package uk.gegc.quizmaker.features.documentProcess.domain;

/**
 * Exception thrown when a URL fails SSRF protection checks.
 */
public class SsrfProtectionException extends LinkFetchException {
    
    public SsrfProtectionException(String message) {
        super(message);
    }
    
    public SsrfProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

