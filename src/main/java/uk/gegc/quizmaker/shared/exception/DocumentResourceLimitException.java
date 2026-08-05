package uk.gegc.quizmaker.shared.exception;

/** Raised when a parser reaches a server-owned document resource limit. */
public class DocumentResourceLimitException extends RuntimeException {

    public DocumentResourceLimitException(String message) {
        super(message);
    }
}
