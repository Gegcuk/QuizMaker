package uk.gegc.quizmaker.features.assignment.domain.model;

import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContractException.Reason.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContractException.require;

/**
 * Assignment storage vocabulary v1 (#531). Values are trusted server-captured facts, not grants.
 * #621 owns reference/authority resolution and publication; #532/#534 own eligibility and start.
 * Construction neither proves source existence nor activates any assignment or preset capability.
 */
public final class AssignmentContract {
    public static final int VERSION = 1;
    private AssignmentContract() { }

    public enum ScopeKind { PERSONAL, ORGANIZATION }
    public enum AudienceKind { ROSTER, GROUP }
    public enum CapacityEnforcement { HARD, SOFT }
    public enum DisplayNameRequirement { NONE, OPTIONAL, REQUIRED }
    public enum EmailRequirement { NONE, OPTIONAL, REQUIRED, REQUIRED_VERIFIED }
    public enum Status { DRAFT, PUBLISHED }

    public record Scope(ScopeKind kind, UUID organizationId) {
        public Scope {
            require(kind != null && (kind == ScopeKind.ORGANIZATION) == (organizationId != null), INVALID_SCOPE);
        }
        @Override public String toString() { return "AssignmentScope[redacted]"; }
    }

    /** Resolved immutable membership, including its exact roster/group source version. Empty is not public. */
    public record Audience(int schemaVersion, AudienceKind kind, UUID sourceVersionId, List<UUID> memberIds) {
        public Audience {
            version(schemaVersion);
            require(kind != null && sourceVersionId != null && memberIds != null
                    && memberIds.stream().allMatch(id -> id != null), INVALID_AUDIENCE);
            require(new HashSet<>(memberIds).size() == memberIds.size(), INVALID_AUDIENCE);
            memberIds = memberIds.stream().sorted().toList();
        }
        @Override public String toString() { return "AssignmentAudience[redacted]"; }
    }

    /** Optional bounds are explicit; this value does not decide start/continue semantics. */
    public record Availability(Instant opensAt, Instant closesAt) {
        public Availability {
            opensAt = timestamp(opensAt, false);
            closesAt = timestamp(closesAt, false);
            require(opensAt == null || closesAt == null || opensAt.isBefore(closesAt), INVALID_WINDOW);
        }
    }

    /** A09: absent capacity has no enforcement; configured capacity always has an explicit mode. */
    public record Capacity(Integer limit, CapacityEnforcement enforcement) {
        public Capacity {
            require(limit == null ? enforcement == null
                    : limit >= 1 && limit <= 10_000 && enforcement != null, INVALID_CAPACITY);
        }
    }

    /** A10 dimensions are independent. Disabled guest access cannot collect identity. */
    public record GuestAccess(boolean enabled, DisplayNameRequirement displayName, EmailRequirement email) {
        public GuestAccess {
            require(displayName != null && email != null && (enabled
                    || displayName == DisplayNameRequirement.NONE && email == EmailRequirement.NONE), INVALID_GUEST_POLICY);
        }
    }

    /** The code-defined immutable preset is the only delivered policy source; no alias/current lookup. */
    public record PolicyReference(int schemaVersion, PresetReference preset) {
        public PolicyReference {
            version(schemaVersion);
            require(BuiltInPracticePreset.REFERENCE.equals(preset), UNKNOWN_POLICY);
        }
    }

    public record Definition(UUID quizRevisionId, int contentSchemaVersion, PolicyReference policy,
                             Audience audience, Availability availability, Capacity capacity, GuestAccess guests) {
        public Definition {
            version(contentSchemaVersion);
            require(quizRevisionId != null && policy != null && audience != null && availability != null
                    && capacity != null && guests != null, INVALID_SNAPSHOT);
        }
        @Override public String toString() { return "AssignmentDefinition[redacted]"; }
    }

    /** Separate draft and published storage. No transition/current pointer is introduced by this contract. */
    public record Snapshot(UUID id, UUID assignmentId, int version, int schemaVersion, Status status,
                           UUID ownerId, Scope scope, Definition definition, Instant recordedAt) {
        public Snapshot {
            require(id != null && assignmentId != null && ownerId != null, INVALID_IDENTITY);
            AssignmentContract.version(schemaVersion);
            require(version > 0 && status != null && scope != null && definition != null, INVALID_SNAPSHOT);
            recordedAt = timestamp(recordedAt, true);
        }
        @Override public String toString() { return "AssignmentSnapshot[status=" + status + ", schemaVersion=" + schemaVersion + "]"; }
    }

    private static void version(int value) { require(value == VERSION, UNKNOWN_VERSION); }

    private static Instant timestamp(Instant value, boolean required) {
        require(value != null || !required, INVALID_SNAPSHOT);
        if (value == null) return null;
        // DATETIME(6), UTC. Normalize before comparing so distinct instants cannot collapse to an empty window.
        require(!value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && value.isBefore(Instant.parse("+10000-01-01T00:00:00Z")), INVALID_WINDOW);
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
