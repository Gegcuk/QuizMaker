package uk.gegc.quizmaker.features.attempt.domain.model.response;

import java.util.List;

/** Fixed codes and request indexes only. Never retain a response, driver cause or private content. */
public final class ResponseBatchException extends RuntimeException {
    public enum Reason { MALFORMED, LIMIT, INVALID_ITEMS, CONFLICT }
    public enum Code { DUPLICATE_ITEM, UNKNOWN_ITEM, INVALID_RESPONSE, UNSUPPORTED_CONTRACT, ANSWER_LOCKED }
    public record ItemError(int index, Code code) { }
    private final Reason reason;
    private final List<ItemError> errors;
    public ResponseBatchException(Reason reason) { this(reason, List.of()); }
    public ResponseBatchException(Reason reason, List<ItemError> errors) {
        super("Response batch rejected: " + reason);
        this.reason = reason;
        this.errors = List.copyOf(errors);
    }
    public Reason reason() { return reason; }
    public List<ItemError> errors() { return errors; }
}
