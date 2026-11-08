package uk.gegc.quizmaker.features.attempt.domain.model.draft;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.require;

/** A deleted value retains its version, preventing stale save/delete from succeeding after recreation. */
public record AnswerDraft(Reference reference, long version, Status status, String normalizedResponse,
                          Instant createdAt, Instant updatedAt) {
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    public enum Status { PRESENT, DELETED }

    public record Reference(UUID attemptId, UUID formId, UUID formItemId, UUID questionRevisionId,
                            ContractVersion responseContract) {
        public Reference {
            require(attemptId != null && formId != null && formItemId != null
                    && questionRevisionId != null && responseContract != null, INVALID_INPUT);
        }
        @Override public String toString() { return "AnswerDraft.Reference[redacted]"; }
    }

    public AnswerDraft {
        require(reference != null && version > 0 && status != null, INVALID_INPUT);
        require(status == Status.DELETED ? normalizedResponse == null : normalizedResponse != null, INVALID_INPUT);
        createdAt = time(createdAt);
        updatedAt = time(updatedAt);
        require(!updatedAt.isBefore(createdAt), INVALID_INPUT);
    }

    public static Instant time(Instant value) {
        require(value != null && !value.isBefore(MIN_TIME) && !value.isAfter(MAX_TIME), INVALID_INPUT);
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    @Override public String toString() { return "AnswerDraft[version=" + version + ", status=" + status + "]"; }
}
