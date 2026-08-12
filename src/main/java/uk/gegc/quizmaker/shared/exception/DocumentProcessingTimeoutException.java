package uk.gegc.quizmaker.shared.exception;

/** Typed resource-limit failure used for bounded document timeout telemetry. */
public class DocumentProcessingTimeoutException extends DocumentResourceLimitException {

    public DocumentProcessingTimeoutException() {
        super("Document processing exceeded the configured time limit");
    }
}
