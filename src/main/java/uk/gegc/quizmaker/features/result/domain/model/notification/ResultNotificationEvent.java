package uk.gegc.quizmaker.features.result.domain.model.notification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Category;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.Reason.*;

/** Immutable reference envelope, not a participant DTO or delivery authorization. */
public record ResultNotificationEvent(int version, Kind kind, Source source, UUID targetId,
                                      UUID previousTargetId, Instant occurredAt, Set<Category> categories) {
    public static final int VERSION = 1;
    public enum Kind {
        RESULT_OPENED, TOTAL_SCORE_CHANGED, RESULT_STATUS_CHANGED,
        CERTIFICATE_ISSUED, CERTIFICATE_REPLACED, CERTIFICATE_REVOKED, CERTIFICATE_REASON_CLARIFIED
    }
    public enum SourceKind { RESULT_RELEASE, SELECTED_RESULT, CERTIFICATE }
    public record Source(SourceKind kind, UUID id, long version) {
        public Source { require(kind != null && id != null && version > 0, INVALID_INPUT); }
        @Override public String toString() { return "NotificationSource[redacted]"; }
    }
    public ResultNotificationEvent {
        require(version == VERSION, UNSUPPORTED);
        require(kind != null && source != null && targetId != null && occurredAt != null && categories != null, INVALID_INPUT);
        require(categories.stream().allMatch(c -> c == Category.TOTAL_SCORE || c == Category.PASS_FAIL), INVALID_INPUT);
        categories = Set.copyOf(categories);
        boolean certificate = kind.name().startsWith("CERTIFICATE_");
        require(certificate == (source.kind() == SourceKind.CERTIFICATE), INVALID_INPUT);
        require(kind != Kind.RESULT_OPENED || source.kind() == SourceKind.RESULT_RELEASE, INVALID_INPUT);
        require(kind != Kind.TOTAL_SCORE_CHANGED && kind != Kind.RESULT_STATUS_CHANGED
                || source.kind() == SourceKind.SELECTED_RESULT, INVALID_INPUT);
        require(!certificate || categories.isEmpty(), INVALID_INPUT);
        require(kind != Kind.TOTAL_SCORE_CHANGED || categories.equals(Set.of(Category.TOTAL_SCORE)), INVALID_INPUT);
        require(kind != Kind.RESULT_STATUS_CHANGED || categories.isEmpty() || categories.equals(Set.of(Category.PASS_FAIL)), INVALID_INPUT);
        require((kind == Kind.CERTIFICATE_REPLACED) == (previousTargetId != null), INVALID_INPUT);
        require(previousTargetId == null || !previousTargetId.equals(targetId), INVALID_INPUT);
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }
    /** First opening reuses the authoritative release/trace identity. Other kinds are contract-only. */
    public UUID id() {
        if (kind == Kind.RESULT_OPENED) return source.id();
        String key = "result-notification/v1|" + kind + "|" + source.kind() + "|" + source.id()
                + "|" + source.version() + "|" + targetId;
        // Bounded identity metadata only; no document, question, answer or reason content.
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.US_ASCII));
    }
    @Override public String toString() { return "ResultNotificationEvent[version=" + version + ",kind=" + kind + "]"; }
}
