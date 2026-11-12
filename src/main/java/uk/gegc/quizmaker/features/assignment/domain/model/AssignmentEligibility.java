package uk.gegc.quizmaker.features.assignment.domain.model;

import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;

/**
 * Pure #532 preflight over exact, server-resolved facts. Neither a grant nor a reservation.
 * #621/#798 supply current publication state; #534 must reload/revalidate at atomic start.
 * Never bind these inputs from an HTTP request or reconstruct them from a legacy share link.
 */
public final class AssignmentEligibility {
    public static final int VERSION = 1;
    private AssignmentEligibility() { }

    /** Observed current state, not new persisted transitions or an implementation of revocation. */
    public enum Publication { CURRENT, SUPERSEDED, REVOKED, UNKNOWN }
    public enum Verification { VERIFIED, DENIED, UNKNOWN, NOT_APPLICABLE }
    public enum Outcome { ALLOW, DENY }
    public enum Reason {
        ALLOWED, INVALID_INPUT, UNSUPPORTED_VERSION, ASSIGNMENT_UNAVAILABLE, SUBJECT_UNAVAILABLE,
        SUBJECT_MISMATCH, FACTS_UNAVAILABLE, INCONSISTENT_FACTS, ACCESS_DENIED, NOT_VISIBLE,
        ORGANIZATION_DENIED, NOT_PUBLISHED, SUPERSEDED, REVOKED, AUDIENCE_MISMATCH,
        NOT_OPEN, CLOSED, CLOCK_UNAVAILABLE
    }

    /** Binds independent access observations to this participant and exact assignment snapshot. */
    public record Binding(UUID assignmentId, UUID snapshotId, int assignmentVersion, UUID subjectId,
                          UUID ownerId, Scope scope) {
        public static Binding of(Snapshot assignment, AttemptSubject subject) {
            Objects.requireNonNull(assignment);
            Objects.requireNonNull(subject);
            return new Binding(assignment.assignmentId(), assignment.id(), assignment.version(),
                    subject.userId(), assignment.ownerId(), assignment.scope());
        }
        @Override public String toString() { return "AssignmentEligibilityBinding[redacted]"; }
    }

    /**
     * resourceAccess covers applicable scoped permission/ownership checks, not role names.
     * visibility is independent. Organization scope requires verified current membership;
     * personal scope requires explicit NOT_APPLICABLE. Missing evidence never means public.
     * These are trusted reader observations, not participant assertions or cached grants.
     */
    public record Facts(int version, Binding binding, Publication publication, Verification resourceAccess,
                        Verification visibility, Verification organizationMembership) {
        @Override public String toString() { return "AssignmentEligibilityFacts[redacted]"; }
    }

    public record Context(int version, Snapshot assignment, AttemptSubject subject, Facts facts) {
        @Override public String toString() { return "AssignmentEligibilityContext[redacted]"; }
    }

    /** Exact eligible version retained only in the internal allow result; never in public denial. */
    public record Reference(UUID assignmentId, UUID snapshotId, int assignmentVersion) {
        public Reference {
            Objects.requireNonNull(assignmentId);
            Objects.requireNonNull(snapshotId);
            if (assignmentVersion < 1) throw new IllegalArgumentException("Invalid assignment version");
        }
        @Override public String toString() { return "AssignmentEligibilityReference[redacted]"; }
    }

    /** Future HTTP adapters must use this projection, never serialize the internal decision. */
    public record PublicDecision(int version, Outcome outcome) {
        public PublicDecision {
            Objects.requireNonNull(outcome);
            if (version != VERSION) throw new IllegalArgumentException("Unsupported eligibility version");
        }
    }

    public record Decision(int version, Outcome outcome, Reason reason, Reference eligibleVersion) {
        public Decision {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason);
            if (version != VERSION || (outcome == Outcome.ALLOW) != (reason == Reason.ALLOWED)
                    || (outcome == Outcome.ALLOW) != (eligibleVersion != null)) {
                throw new IllegalArgumentException("Invalid eligibility decision");
            }
        }
        public PublicDecision publicDecision() { return new PublicDecision(VERSION, outcome); }
        @Override public String toString() {
            return "AssignmentEligibilityDecision[version=" + version + ", outcome=" + outcome + ", reason=" + reason + "]";
        }
    }

    /** now is supplied only by the application Clock; snapshot timestamps already use UTC microseconds. */
    public static Decision evaluate(Context context, Instant now) {
        if (context == null) return denied(Reason.INVALID_INPUT);
        if (context.version() != VERSION) return denied(Reason.UNSUPPORTED_VERSION);
        var assignment = context.assignment();
        if (assignment == null) return denied(Reason.ASSIGNMENT_UNAVAILABLE);
        var subject = context.subject();
        if (subject == null) return denied(Reason.SUBJECT_UNAVAILABLE);
        var facts = context.facts();
        if (facts == null) return denied(Reason.FACTS_UNAVAILABLE);
        if (facts.version() != VERSION) return denied(Reason.UNSUPPORTED_VERSION);
        if (!Binding.of(assignment, subject).equals(facts.binding())) return denied(Reason.INCONSISTENT_FACTS);
        if (facts.resourceAccess() != Verification.VERIFIED) return denied(Reason.ACCESS_DENIED);
        if (facts.visibility() != Verification.VERIFIED) return denied(Reason.NOT_VISIBLE);
        if (assignment.scope().kind() == ScopeKind.ORGANIZATION) {
            if (facts.organizationMembership() != Verification.VERIFIED) return denied(Reason.ORGANIZATION_DENIED);
        } else if (facts.organizationMembership() != Verification.NOT_APPLICABLE) {
            return denied(Reason.INCONSISTENT_FACTS);
        }
        if (assignment.status() != Status.PUBLISHED) return denied(Reason.NOT_PUBLISHED);
        if (facts.publication() != Publication.CURRENT) {
            if (facts.publication() == Publication.SUPERSEDED) return denied(Reason.SUPERSEDED);
            if (facts.publication() == Publication.REVOKED) return denied(Reason.REVOKED);
            return denied(Reason.FACTS_UNAVAILABLE);
        }
        if (!assignment.definition().audience().memberIds().contains(subject.userId())) return denied(Reason.AUDIENCE_MISMATCH);
        if (now == null) return denied(Reason.CLOCK_UNAVAILABLE);
        var window = assignment.definition().availability();
        if (window.opensAt() != null && now.isBefore(window.opensAt())) return denied(Reason.NOT_OPEN);
        if (window.closesAt() != null && !now.isBefore(window.closesAt())) return denied(Reason.CLOSED);
        return new Decision(VERSION, Outcome.ALLOW, Reason.ALLOWED,
                new Reference(assignment.assignmentId(), assignment.id(), assignment.version()));
    }

    public static Decision denied(Reason reason) { return new Decision(VERSION, Outcome.DENY, reason, null); }
}
