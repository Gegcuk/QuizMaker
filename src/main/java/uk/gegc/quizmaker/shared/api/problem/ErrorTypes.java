package uk.gegc.quizmaker.shared.api.problem;

import java.net.URI;

/**
 * Centralised catalog of RFC 7807 Problem Detail type URIs.
 * Each constant should point to documentation describing the error.
 *
 * <p>Example usage:
 * <pre>
 * ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
 * problem.setType(ErrorTypes.RESOURCE_NOT_FOUND);
 * problem.setTitle("Resource Not Found");
 * </pre>
 *
 * @see ProblemDetailBuilder
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7807">RFC 7807</a>
 */
public final class ErrorTypes {

    private static final String BASE_URL = "https://quizzence.com/docs/errors";

    // ==================== Resource Errors ====================
    public static final URI RESOURCE_NOT_FOUND = URI.create(BASE_URL + "/resource-not-found");
    public static final URI DOCUMENT_NOT_FOUND = URI.create(BASE_URL + "/document-not-found");

    // ==================== Validation Errors ====================
    public static final URI VALIDATION_FAILED = URI.create(BASE_URL + "/validation-failed");
    public static final URI INVALID_ARGUMENT = URI.create(BASE_URL + "/invalid-argument");
    public static final URI CONSTRAINT_VIOLATION = URI.create(BASE_URL + "/constraint-violation");
    public static final URI TYPE_MISMATCH = URI.create(BASE_URL + "/type-mismatch");
    public static final URI MALFORMED_JSON = URI.create(BASE_URL + "/malformed-json");
    public static final URI UNSUPPORTED_OPERATION = URI.create(BASE_URL + "/unsupported-operation");
    public static final URI UNSUPPORTED_QUESTION_TYPE = URI.create(BASE_URL + "/unsupported-question-type");
    public static final URI UNSUPPORTED_FILE_TYPE = URI.create(BASE_URL + "/unsupported-file-type");
    public static final URI UNSUPPORTED_FORMAT = URI.create(BASE_URL + "/unsupported-format");
    public static final URI QUIZ_GENERATION_FAILED = URI.create(BASE_URL + "/quiz-generation-failed");

    // ==================== Processing Errors ====================
    public static final URI CONVERSION_FAILED = URI.create(BASE_URL + "/conversion-failed");
    public static final URI NORMALIZATION_FAILED = URI.create(BASE_URL + "/normalization-failed");
    public static final URI DOCUMENT_PROCESSING_FAILED = URI.create(BASE_URL + "/document-processing-failed");
    public static final URI DOCUMENT_STORAGE_FAILED = URI.create(BASE_URL + "/document-storage-failed");
    public static final URI PROCESSING_FAILED = URI.create(BASE_URL + "/processing-failed");

    // ==================== Security Errors ====================
    public static final URI UNAUTHORIZED = URI.create(BASE_URL + "/unauthorized");
    public static final URI ACCESS_DENIED = URI.create(BASE_URL + "/access-denied");
    public static final URI DOCUMENT_ACCESS_DENIED = URI.create(BASE_URL + "/document-access-denied");

    // ==================== State Errors ====================
    public static final URI ATTEMPT_NOT_COMPLETED = URI.create(BASE_URL + "/attempt-not-completed");
    public static final URI ILLEGAL_STATE = URI.create(BASE_URL + "/illegal-state");
    public static final URI DATA_CONFLICT = URI.create(BASE_URL + "/data-conflict");
    public static final URI OPTIMISTIC_LOCK_CONFLICT = URI.create(BASE_URL + "/optimistic-lock-conflict");

    // ==================== Rate Limiting ====================
    public static final URI RATE_LIMIT_EXCEEDED = URI.create(BASE_URL + "/rate-limit-exceeded");

    // ==================== AI Service Errors ====================
    public static final URI AI_SERVICE_UNAVAILABLE = URI.create(BASE_URL + "/ai-service-unavailable");
    public static final URI AI_SERVICE_ERROR = URI.create(BASE_URL + "/ai-service-error");

    // ==================== Share Link Errors ====================
    public static final URI SHARE_LINK_ALREADY_USED = URI.create(BASE_URL + "/share-link-already-used");

    // ==================== Billing Errors ====================
    public static final URI INSUFFICIENT_TOKENS = URI.create(BASE_URL + "/insufficient-tokens");
    public static final URI INSUFFICIENT_AVAILABLE_TOKENS = URI.create(BASE_URL + "/insufficient-available-tokens");
    public static final URI RESERVATION_NOT_ACTIVE = URI.create(BASE_URL + "/reservation-not-active");
    public static final URI COMMIT_EXCEEDS_RESERVED = URI.create(BASE_URL + "/commit-exceeds-reserved");
    public static final URI PACK_NOT_FOUND = URI.create(BASE_URL + "/pack-not-found");
    public static final URI STRIPE_WEBHOOK_INVALID_SIGNATURE = URI.create(BASE_URL + "/stripe-webhook-invalid-signature");
    public static final URI IDEMPOTENCY_CONFLICT = URI.create(BASE_URL + "/idempotency-conflict");
    public static final URI INVALID_JOB_STATE_FOR_COMMIT = URI.create(BASE_URL + "/invalid-job-state-for-commit");

    // ==================== Generic Errors ====================
    public static final URI INTERNAL_SERVER_ERROR = URI.create(BASE_URL + "/internal-server-error");
    public static final URI GENERIC_ERROR = URI.create(BASE_URL + "/error");

    private ErrorTypes() {
        throw new AssertionError("Utility class - do not instantiate");
    }
}
