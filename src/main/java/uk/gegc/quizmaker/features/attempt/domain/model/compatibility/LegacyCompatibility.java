package uk.gegc.quizmaker.features.attempt.domain.model.compatibility;

import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeMapping;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Inventory vocabulary v1. A classification never grants access or changes an execution binding. */
public final class LegacyCompatibility {
    public static final int VERSION = 1;
    private LegacyCompatibility() { }

    public enum Family {
        ATTEMPT, ANSWER, SHARE_LINK, RESULT_SNAPSHOT, QUIZ_CONFIGURATION, REPETITION_ENTRY, REPETITION_REVIEW,
        SHARE_LINK_USAGE, SHARE_LINK_EVENT
    }
    public enum Classification { SUPPORTED_ADAPTER, LEGACY_ONLY, UNSUPPORTED, UNKNOWN }

    /** Fixed reasons and exact downstream owners; no stored strings or private content in diagnostics. */
    public enum Finding {
        MAPPED_REQUEST(Classification.SUPPORTED_ADAPTER, 609),
        LEGACY_HISTORY(Classification.LEGACY_ONLY, 704),
        LEGACY_CAPABILITY(Classification.LEGACY_ONLY, 623),
        LEGACY_AGGREGATE(Classification.LEGACY_ONLY, 606),
        MUTABLE_CONFIGURATION(Classification.LEGACY_ONLY, 700),
        LATER_CAPABILITY(Classification.LEGACY_ONLY, 609),
        CHOICE_REQUIRED(Classification.UNSUPPORTED, 833),
        ACCESS_UNAVAILABLE(Classification.UNSUPPORTED, 600),
        UNSUPPORTED_MAPPING(Classification.UNSUPPORTED, 609),
        UNKNOWN_VALUE(Classification.UNKNOWN, 608),
        BROKEN_REFERENCE(Classification.UNKNOWN, 608),
        AMBIGUOUS_IDENTITY(Classification.UNKNOWN, 624),
        INCONSISTENT_STATE(Classification.UNKNOWN, 619),
        DUPLICATE_ANSWER(Classification.UNKNOWN, 848),
        UNEXPECTED_REPETITION(Classification.UNKNOWN, 667);

        private final Classification classification;
        private final int ownerIssue;
        Finding(Classification classification, int ownerIssue) {
            this.classification = classification; this.ownerIssue = ownerIssue;
        }
        public Classification classification() { return classification; }
        public int ownerIssue() { return ownerIssue; }
    }

    /** Flags only. Stored identifiers are used solely as private keyset cursors. */
    public record Observation(UUID cursor, boolean brokenReference, boolean unknownValue,
                              boolean ambiguousIdentity, boolean inconsistentState,
                              boolean duplicateAnswer, boolean unexpectedRepetition) {
        public Observation { Objects.requireNonNull(cursor); }
        @Override public String toString() { return "Legacy observation [redacted]"; }
    }

    public static Finding classify(Family family, Observation row) {
        Objects.requireNonNull(family); Objects.requireNonNull(row);
        // Exactly one bucket per row, with stable precedence for overlapping discrepancies.
        if (row.brokenReference()) return Finding.BROKEN_REFERENCE;
        if (row.unknownValue()) return Finding.UNKNOWN_VALUE;
        if (row.ambiguousIdentity()) return Finding.AMBIGUOUS_IDENTITY;
        if (row.duplicateAnswer()) return Finding.DUPLICATE_ANSWER;
        if (row.inconsistentState()) return Finding.INCONSISTENT_STATE;
        if (row.unexpectedRepetition()) return Finding.UNEXPECTED_REPETITION;
        return switch (family) {
            case ATTEMPT, ANSWER -> Finding.LEGACY_HISTORY;
            case SHARE_LINK, SHARE_LINK_USAGE, SHARE_LINK_EVENT -> Finding.LEGACY_CAPABILITY;
            case RESULT_SNAPSHOT -> Finding.LEGACY_AGGREGATE;
            case QUIZ_CONFIGURATION -> Finding.MUTABLE_CONFIGURATION;
            case REPETITION_ENTRY, REPETITION_REVIEW -> Finding.UNEXPECTED_REPETITION;
        };
    }

    /** Reuse #516; a supported request mapping is not a working target start or historical snapshot. */
    public static Finding classifyMapping(LegacyPracticeMapping mapping) {
        Objects.requireNonNull(mapping);
        if (mapping instanceof LegacyPracticeMapping.Mapped) return Finding.MAPPED_REQUEST;
        if (mapping instanceof LegacyPracticeMapping.LegacyOnly) return Finding.LATER_CAPABILITY;
        if (mapping instanceof LegacyPracticeMapping.ChoiceRequired) return Finding.CHOICE_REQUIRED;
        if (mapping instanceof LegacyPracticeMapping.Unavailable unavailable) {
            return unavailable.reason() == LegacyPracticeMapping.Unavailable.Reason.UNKNOWN_TIMER_CONFIGURATION
                    ? Finding.UNKNOWN_VALUE : Finding.ACCESS_UNAVAILABLE;
        }
        return Finding.UNSUPPORTED_MAPPING;
    }

    public enum StartEvidence { PROVEN_ABSENT, COMMITTED_TARGET, UNKNOWN_OR_IN_FLIGHT }
    public enum RoutingInput { COMPATIBLE_CANDIDATE, TARGET_OWNED, RECONCILE, NOT_COMPATIBLE }

    /** Specification input for #703, not an implementation of its operator/allowance/routing race. */
    public static RoutingInput routingInput(StartEvidence evidence, Finding compatibility) {
        Objects.requireNonNull(evidence); Objects.requireNonNull(compatibility);
        return switch (evidence) {
            case COMMITTED_TARGET -> RoutingInput.TARGET_OWNED;
            case UNKNOWN_OR_IN_FLIGHT -> RoutingInput.RECONCILE;
            case PROVEN_ABSENT -> compatibility == Finding.MAPPED_REQUEST
                    ? RoutingInput.COMPATIBLE_CANDIDATE : RoutingInput.NOT_COMPATIBLE;
        };
    }

    /** Explicit caller-selected work budget; no automatic background scan or production default. */
    public record Limits(int batchSize, int maxRowsPerFamily, int timeoutSeconds) {
        public Limits {
            if (batchSize < 1 || batchSize > 1000 || maxRowsPerFamily < 1 || maxRowsPerFamily > 1_000_000
                    || timeoutSeconds < 1 || timeoutSeconds > 300) {
                throw new IllegalArgumentException("Invalid legacy inventory limits");
            }
        }
    }

    public record FamilyCount(Family family, long examined, boolean complete, Map<Finding, Long> findings) {
        public FamilyCount {
            Objects.requireNonNull(family); findings = Map.copyOf(findings);
            if (examined < 0 || findings.values().stream().anyMatch(n -> n < 0)
                    || findings.values().stream().mapToLong(Long::longValue).sum() != examined) {
                throw new IllegalArgumentException("Unreconciled legacy inventory counts");
            }
        }
    }

    /** Contains no row, user, quiz or capability identifiers. Counts describe one database snapshot. */
    public record Report(int version, List<FamilyCount> families) {
        public Report {
            families = List.copyOf(families);
            if (version != VERSION || families.size() != Family.values().length
                    || families.stream().map(FamilyCount::family).distinct().count() != Family.values().length) {
                throw new IllegalArgumentException("Incomplete legacy inventory report");
            }
        }
        public boolean complete() { return families.stream().allMatch(FamilyCount::complete); }
        public boolean emptyRepetitionConfirmed() {
            return families.stream().filter(f -> f.family() == Family.REPETITION_ENTRY
                            || f.family() == Family.REPETITION_REVIEW || f.family() == Family.QUIZ_CONFIGURATION)
                    .allMatch(f -> f.complete() && (f.family() == Family.QUIZ_CONFIGURATION
                            ? f.findings().keySet().stream().allMatch(k -> k == Finding.MUTABLE_CONFIGURATION)
                            : f.examined() == 0));
        }
    }
}
