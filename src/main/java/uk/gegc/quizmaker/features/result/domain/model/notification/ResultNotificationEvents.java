package uk.gegc.quizmaker.features.result.domain.model.notification;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Category;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultNotificationEvent.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.Reason.*;

/** Pure classification of verified source facts. Only first #556 release has a runtime writer. */
public final class ResultNotificationEvents {
    private ResultNotificationEvents() { }
    public enum Validity { VALID, INVALID }
    public enum CertificateStatus { ABSENT, VALID, REVOKED, EXPIRED }
    /** Exact participant-visible points, not normalized rounding or an internal grading version. */
    public record Total(long earned, long possible, int basisPoints) {
        public Total {
            require(possible > 0 && earned >= 0 && earned <= possible && basisPoints >= 0 && basisPoints <= 10000, INVALID_INPUT);
            require(AutomaticResult.Score.calculate(earned, possible, 0).basisPoints() == basisPoints, INVALID_INPUT);
        }
        @Override public String toString() { return "NotificationTotal[redacted]"; }
    }
    /** validity is the participant-visible status; null means withheld, independently of total. */
    public record ResultFacts(boolean available, Set<Category> permitted, Total total, Boolean passed, Validity validity) {
        public ResultFacts {
            require(permitted != null && permitted.stream().noneMatch(Objects::isNull), INVALID_INPUT);
            permitted = Set.copyOf(permitted);
            require(!available || !permitted.contains(Category.TOTAL_SCORE) || total != null, INVALID_INPUT);
            require(!available || !permitted.contains(Category.PASS_FAIL) || passed != null, INVALID_INPUT);
        }
        @Override public String toString() { return "NotificationResultFacts[redacted]"; }
    }
    /** Reason identity refers to an immutable, verified participant-facing reason fact, never text. */
    public record CertificateFacts(CertificateStatus status, UUID certificateId, UUID reasonVersionId) {
        public CertificateFacts {
            require(status != null && ((status == CertificateStatus.ABSENT) == (certificateId == null)), INVALID_INPUT);
            require(status == CertificateStatus.REVOKED || reasonVersionId == null, INVALID_INPUT);
        }
        @Override public String toString() { return "NotificationCertificateFacts[redacted]"; }
    }
    public static ResultNotificationEvent firstRelease(AutomaticResult result, ResultRelease release, ResultDisclosure.Policy policy) {
        require(result != null && release != null && policy != null, INVALID_INPUT);
        require(result.id().equals(release.resultId()) && ResultDisclosure.Binding.of(result).equals(policy.binding())
                && policy.version() == ResultDisclosure.VERSION && policy.family() == ResultDisclosure.Family.PERSONAL_PRACTICE
                && policy.permitted().contains(Category.TOTAL_SCORE) && !release.releasedAt().isBefore(result.createdAt()), CORRUPT);
        var score = result.score();
        var after = new ResultFacts(true, policy.permitted(), new Total(score.earned(), score.possible(), score.basisPoints()),
                policy.permitted().contains(Category.PASS_FAIL) ? score.passed() : null, null);
        return resultChange(new Source(SourceKind.RESULT_RELEASE, release.id(), release.version()), result.id(), release.releasedAt(),
                new ResultFacts(false, Set.of(), null, null, null), after).get(0);
    }
    public static List<ResultNotificationEvent> resultChange(Source source, UUID resultId, Instant at, ResultFacts before, ResultFacts after) {
        require(source != null && source.kind() != SourceKind.CERTIFICATE && resultId != null && at != null
                && before != null && after != null, INVALID_INPUT);
        if (!after.available()) return List.of();
        if (!before.available()) return List.of(event(Kind.RESULT_OPENED, source, resultId, null, at, visible(after.permitted())));
        require(source.kind() == SourceKind.SELECTED_RESULT, INVALID_INPUT);
        var events = new ArrayList<ResultNotificationEvent>();
        if (both(before, after, Category.TOTAL_SCORE) && !before.total().equals(after.total()))
            events.add(event(Kind.TOTAL_SCORE_CHANGED, source, resultId, null, at, Set.of(Category.TOTAL_SCORE)));
        if (before.validity() != null && after.validity() != null && before.validity() != after.validity()
                || both(before, after, Category.PASS_FAIL) && !before.passed().equals(after.passed()))
            events.add(event(Kind.RESULT_STATUS_CHANGED, source, resultId, null, at,
                    both(before, after, Category.PASS_FAIL) ? Set.of(Category.PASS_FAIL) : Set.of()));
        return List.copyOf(events);
    }
    public static List<ResultNotificationEvent> certificateChange(Source source, Instant at, CertificateFacts before, CertificateFacts after) {
        require(source != null && source.kind() == SourceKind.CERTIFICATE && at != null && before != null && after != null, INVALID_INPUT);
        Kind kind = null; UUID previous = null;
        if (before.status() == CertificateStatus.ABSENT && after.status() == CertificateStatus.VALID) kind = Kind.CERTIFICATE_ISSUED;
        else if (before.certificateId() != null && after.status() == CertificateStatus.VALID
                && !before.certificateId().equals(after.certificateId())) { kind = Kind.CERTIFICATE_REPLACED; previous = before.certificateId(); }
        else if (before.certificateId() != null && before.certificateId().equals(after.certificateId())) {
            if (before.status() != CertificateStatus.REVOKED && after.status() == CertificateStatus.REVOKED) kind = Kind.CERTIFICATE_REVOKED;
            else if (before.status() == CertificateStatus.REVOKED && after.status() == CertificateStatus.REVOKED
                    && after.reasonVersionId() != null && !after.reasonVersionId().equals(before.reasonVersionId())) kind = Kind.CERTIFICATE_REASON_CLARIFIED;
        }
        return kind == null ? List.of() : List.of(event(kind, source, after.certificateId(), previous, at, Set.of()));
    }
    private static boolean both(ResultFacts before, ResultFacts after, Category category) {
        return before.permitted().contains(category) && after.permitted().contains(category);
    }
    private static Set<Category> visible(Set<Category> permitted) {
        var categories = EnumSet.noneOf(Category.class);
        for (var category : List.of(Category.TOTAL_SCORE, Category.PASS_FAIL)) if (permitted.contains(category)) categories.add(category);
        return categories;
    }
    private static ResultNotificationEvent event(Kind kind, Source source, UUID target, UUID previous, Instant at, Set<Category> categories) {
        return new ResultNotificationEvent(VERSION, kind, source, target, previous, at, categories);
    }
}
