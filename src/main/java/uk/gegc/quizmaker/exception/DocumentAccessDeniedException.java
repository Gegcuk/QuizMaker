package uk.gegc.quizmaker.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class DocumentAccessDeniedException extends RuntimeException {

    public DocumentAccessDeniedException(String message) {
        super(message);
    }

    public DocumentAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentAccessDeniedException(String username, String documentId, String operation) {
        super(String.format("User '%s' is not authorized to %s document with ID %s", username, operation, documentId));
    }
} 