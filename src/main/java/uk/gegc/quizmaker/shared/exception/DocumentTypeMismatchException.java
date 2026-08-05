package uk.gegc.quizmaker.shared.exception;

/** Raised when filename, declared MIME type, or detected content disagree. */
public class DocumentTypeMismatchException extends RuntimeException {

    public DocumentTypeMismatchException(String message) {
        super(message);
    }
}
