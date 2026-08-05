package uk.gegc.quizmaker.shared.exception;

/** Raised before an upload can exceed the configured storage and parsing budget. */
public class DocumentUploadLimitExceededException extends RuntimeException {

    public DocumentUploadLimitExceededException() {
        super("Document upload exceeds the configured size limit");
    }
}
