package uk.gegc.quizmaker.features.document.infra.isolation;

import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;

enum DocumentParserWorkerError {
    PDF_PAGE_LIMIT(FailureType.RESOURCE_LIMIT, "PDF exceeds the configured page limit"),
    PDF_MEMORY_LIMIT(FailureType.RESOURCE_LIMIT,
            "PDF exceeds the configured memory and scratch storage limit"),
    EXTRACTED_TEXT_LIMIT(FailureType.RESOURCE_LIMIT,
            "Extracted document text exceeds the configured limit"),
    OUTPUT_LIMIT(FailureType.RESOURCE_LIMIT,
            "Converted document output exceeds the configured limit"),
    TYPE_MISMATCH(FailureType.TYPE_MISMATCH,
            "Document content does not match a supported document type"),
    RESOURCE_LIMIT(FailureType.RESOURCE_LIMIT,
            "Document processing exceeded a configured resource limit"),
    PROCESSING_FAILED(FailureType.PROCESSING_FAILED, "Document processing failed");

    private final FailureType failureType;
    private final String safeMessage;

    DocumentParserWorkerError(FailureType failureType, String safeMessage) {
        this.failureType = failureType;
        this.safeMessage = safeMessage;
    }

    RuntimeException toException() {
        return switch (failureType) {
            case RESOURCE_LIMIT -> new DocumentResourceLimitException(safeMessage);
            case TYPE_MISMATCH -> new DocumentTypeMismatchException(safeMessage);
            case PROCESSING_FAILED -> new DocumentProcessingException(safeMessage);
        };
    }

    static DocumentParserWorkerError fromResourceLimit(String message) {
        if ("PDF exceeds the configured page limit".equals(message)) {
            return PDF_PAGE_LIMIT;
        }
        if ("PDF exceeds the configured memory and scratch storage limit".equals(message)) {
            return PDF_MEMORY_LIMIT;
        }
        if (message != null && message.startsWith("Extracted ")) {
            return EXTRACTED_TEXT_LIMIT;
        }
        return RESOURCE_LIMIT;
    }

    private enum FailureType {
        RESOURCE_LIMIT,
        TYPE_MISMATCH,
        PROCESSING_FAILED
    }
}
