package uk.gegc.quizmaker.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnsupportedFileTypeException extends RuntimeException {

    public UnsupportedFileTypeException(String message) {
        super(message);
    }

    public UnsupportedFileTypeException(String message, Throwable cause) {
        super(message, cause);
    }

    public UnsupportedFileTypeException(String filename, String contentType) {
        super(String.format("Unsupported file type for file '%s' with content type '%s'", filename, contentType));
    }

    public UnsupportedFileTypeException(String filename, String contentType, String supportedTypes) {
        super(String.format("Unsupported file type for file '%s' with content type '%s'. Supported types: %s",
                filename, contentType, supportedTypes));
    }
} 