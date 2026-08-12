package uk.gegc.quizmaker.features.document.application;

import org.springframework.dao.DataAccessException;
import uk.gegc.quizmaker.shared.exception.DocumentAccessDeniedException;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingCapacityExceededException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingTimeoutException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentStorageException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;
import uk.gegc.quizmaker.shared.exception.DocumentUploadLimitExceededException;
import uk.gegc.quizmaker.shared.exception.UserNotAuthorizedException;

import java.time.Duration;
import java.util.Locale;

/** Privacy-safe, low-cardinality metrics for document ingestion and storage maintenance. */
public interface DocumentIngestionMetrics {

    void ingestionStarted();

    void ingestionStopped();

    void recordEvent(Stage stage, Outcome outcome, Reason reason);

    void recordDuration(Stage stage, Outcome outcome, Duration duration);

    void recordExtracted(Format format, int characters, Integer pages);

    void recordReconciliationCandidates(int count);

    enum Stage {
        VALIDATION,
        STAGING,
        CONVERSION,
        CHUNKING,
        PROMOTION,
        PUBLICATION,
        PROCESSING,
        COMPENSATION,
        CLEANUP,
        RECONCILIATION;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Outcome {
        ACCEPTED,
        REJECTED,
        SUCCEEDED,
        FAILED,
        SKIPPED;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Reason {
        NONE,
        INVALID_INPUT,
        UPLOAD_SIZE,
        TYPE_MISMATCH,
        RESOURCE_LIMIT,
        TIMEOUT,
        CAPACITY,
        STORAGE,
        PROCESSING,
        PERSISTENCE,
        NOT_FOUND,
        ACCESS_DENIED,
        CLEANUP,
        UNKNOWN;

        private static final int MAX_CAUSE_DEPTH = 8;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isRejectedRequest() {
            return switch (this) {
                case INVALID_INPUT, UPLOAD_SIZE, TYPE_MISMATCH, RESOURCE_LIMIT, TIMEOUT,
                     CAPACITY, NOT_FOUND, ACCESS_DENIED -> true;
                default -> false;
            };
        }

        public static Reason from(Throwable failure) {
            Throwable current = failure;
            Reason fallback = UNKNOWN;
            int depth = 0;
            while (current != null && depth++ < MAX_CAUSE_DEPTH) {
                if (current instanceof DocumentProcessingTimeoutException) {
                    return TIMEOUT;
                }
                if (current instanceof DocumentUploadLimitExceededException) {
                    return UPLOAD_SIZE;
                }
                if (current instanceof DocumentTypeMismatchException) {
                    return TYPE_MISMATCH;
                }
                if (current instanceof DocumentProcessingCapacityExceededException) {
                    return CAPACITY;
                }
                if (current instanceof DocumentResourceLimitException) {
                    return RESOURCE_LIMIT;
                }
                if (current instanceof DocumentStorageException) {
                    return STORAGE;
                }
                if (current instanceof DataAccessException) {
                    return PERSISTENCE;
                }
                if (current instanceof DocumentNotFoundException) {
                    return NOT_FOUND;
                }
                if (current instanceof UserNotAuthorizedException
                        || current instanceof DocumentAccessDeniedException) {
                    return ACCESS_DENIED;
                }
                if (current instanceof DocumentProcessingException) {
                    fallback = PROCESSING;
                }
                if (current instanceof IllegalArgumentException && fallback == UNKNOWN) {
                    fallback = INVALID_INPUT;
                }
                current = current.getCause();
            }
            return fallback;
        }
    }

    enum Format {
        PDF,
        EPUB,
        TEXT,
        UNKNOWN;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Format fromContentType(String contentType) {
            if (contentType == null) {
                return UNKNOWN;
            }
            String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "application/pdf" -> PDF;
                case "application/epub+zip", "application/epub", "application/x-epub" -> EPUB;
                case "text/plain", "text/txt" -> TEXT;
                default -> UNKNOWN;
            };
        }
    }
}
