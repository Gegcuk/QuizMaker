package uk.gegc.quizmaker.shared.exception;

/** Raised when all bounded document parsing workers are occupied. */
public class DocumentProcessingCapacityExceededException extends RuntimeException {

    public DocumentProcessingCapacityExceededException() {
        super("Document processing capacity is temporarily unavailable");
    }
}
