package uk.gegc.quizmaker.features.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContractException;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility.evaluate;

class AssignmentEligibilityTest {
    @ParameterizedTest @EnumSource(AudienceKind.class)
    void eligibleRosterAndGroupMembersReceiveTheExactPublishedVersion(AudienceKind kind) {
        var f = new EligibilityFixture();
        f.audience = new Audience(1, kind, AUDIENCE, List.of(OTHER_MEMBER, MEMBER));
        f.assignmentVersion = 7;
        var decision = evaluate(f.context(), TIME);
        assertThat(decision).isEqualTo(evaluate(f.context(), TIME));
        assertThat(decision.version()).isEqualTo(1);
        assertThat(decision.outcome()).isEqualTo(Outcome.ALLOW);
        assertThat(decision.reason()).isEqualTo(Reason.ALLOWED);
        assertThat(decision.eligibleVersion()).isEqualTo(new Reference(ASSIGNMENT, ID, 7));
    }

    @Test void openingIsInclusiveAndClosingIsExclusiveWithoutRoundingTheServerClock() {
        var f = new EligibilityFixture();
        denied(f.context(), TIME.minusNanos(1), Reason.NOT_OPEN);
        allowed(f.context(), TIME);
        allowed(f.context(), TIME.plusSeconds(3600).minusNanos(1));
        denied(f.context(), TIME.plusSeconds(3600), Reason.CLOSED);
        denied(f.context(), TIME.plusSeconds(3600).plusNanos(1), Reason.CLOSED);
        // The stored #531 contract normalizes timestamps once, not on every decision.
        f.availability = new Availability(TIME.plusNanos(1234), TIME.plusSeconds(1).plusNanos(1234));
        denied(f.context(), TIME.plusNanos(999), Reason.NOT_OPEN);
        allowed(f.context(), TIME.plusNanos(1000));
        denied(f.context(), TIME.plusSeconds(1).plusNanos(1000), Reason.CLOSED);
    }

    @Test void absentBoundsAreExplicitlyUnboundedAndOneSidedBoundsRemainEffective() {
        var f = new EligibilityFixture();
        f.availability = new Availability(null, null);
        allowed(f.context(), TIME.minusSeconds(1));
        allowed(f.context(), TIME.plusSeconds(86_400));
        f.availability = new Availability(TIME, null);
        denied(f.context(), TIME.minusNanos(1), Reason.NOT_OPEN);
        allowed(f.context(), TIME.plusSeconds(86_400));
        f.availability = new Availability(null, TIME);
        allowed(f.context(), TIME.minusNanos(1));
        denied(f.context(), TIME, Reason.CLOSED);
    }

    @Test void ownershipAndEmptyOrForeignAudienceNeverGrantParticipation() {
        var f = new EligibilityFixture();
        allowed(f.context(), TIME);
        f.subject = AttemptSubject.authenticated(f.subject.attemptId(), OWNER);
        denied(f.context(), TIME, Reason.AUDIENCE_MISMATCH);
        f.subject = AttemptSubject.authenticated(f.subject.attemptId(), MEMBER);
        f.audience = new Audience(1, AudienceKind.ROSTER, AUDIENCE, List.of());
        denied(f.context(), TIME, Reason.AUDIENCE_MISMATCH);
        f.audience = new Audience(1, AudienceKind.ROSTER, AUDIENCE, List.of(OTHER_MEMBER));
        denied(f.context(), TIME, Reason.AUDIENCE_MISMATCH);
        f.audience = new Audience(1, AudienceKind.ROSTER, AUDIENCE, List.of(MEMBER));
        allowed(f.context(), TIME);
    }

    @ParameterizedTest @EnumSource(value = Verification.class, names = "VERIFIED", mode = EnumSource.Mode.EXCLUDE)
    void permissionVisibilityAndOrganizationMembershipAreIndependentAndDefaultDeny(Verification missing) {
        var f = new EligibilityFixture();
        f.access = missing; denied(f.context(), TIME, Reason.ACCESS_DENIED);
        f.access = Verification.VERIFIED;
        f.visibility = missing; denied(f.context(), TIME, Reason.NOT_VISIBLE);
        f.visibility = Verification.VERIFIED;
        f.organization = missing; denied(f.context(), TIME, Reason.ORGANIZATION_DENIED);
        f.organization = Verification.VERIFIED; allowed(f.context(), TIME);
    }

    @Test void missingAccessObservationsAndPersonalScopeAreNotImplicitOrganizationGrants() {
        var f = new EligibilityFixture();
        f.access = null; denied(f.context(), TIME, Reason.ACCESS_DENIED);
        f.access = Verification.VERIFIED;
        f.visibility = null; denied(f.context(), TIME, Reason.NOT_VISIBLE);
        f.visibility = Verification.VERIFIED;
        f.organization = null; denied(f.context(), TIME, Reason.ORGANIZATION_DENIED);
        f.scope = new Scope(ScopeKind.PERSONAL, null);
        for (var observation : Verification.values()) {
            f.organization = observation;
            if (observation == Verification.NOT_APPLICABLE) allowed(f.context(), TIME);
            else denied(f.context(), TIME, Reason.INCONSISTENT_FACTS);
        }
    }

    @Test void accessEvidenceCannotMoveToAnotherParticipantOwnerOrganizationOrVersion() {
        var f = new EligibilityFixture();
        var bound = f.context().facts().binding();
        var foreign = UUID.randomUUID();
        for (Binding mismatch : List.of(
                new Binding(foreign, ID, 1, MEMBER, OWNER, f.scope),
                new Binding(ASSIGNMENT, foreign, 1, MEMBER, OWNER, f.scope),
                new Binding(ASSIGNMENT, ID, 2, MEMBER, OWNER, f.scope),
                new Binding(ASSIGNMENT, ID, 1, OTHER_MEMBER, OWNER, f.scope),
                new Binding(ASSIGNMENT, ID, 1, MEMBER, foreign, f.scope),
                new Binding(ASSIGNMENT, ID, 1, MEMBER, OWNER, new Scope(ScopeKind.ORGANIZATION, foreign)),
                new Binding(null, ID, 1, MEMBER, OWNER, f.scope),
                new Binding(ASSIGNMENT, ID, 0, MEMBER, OWNER, f.scope))) {
            f.bindingOverride = mismatch;
            denied(f.context(), TIME, Reason.INCONSISTENT_FACTS);
        }
        f.bindingOverride = bound; allowed(f.context(), TIME);
        f.assignmentVersion = 2; denied(f.context(), TIME, Reason.INCONSISTENT_FACTS);
        f.bindingOverride = null; allowed(f.context(), TIME);
    }

    @Test void draftSupersededRevokedAndUnknownStateNeverAuthorizeStartsOrMutateAnOldDecision() {
        var f = new EligibilityFixture();
        var old = evaluate(f.context(), TIME);
        f.status = Status.DRAFT; denied(f.context(), TIME, Reason.NOT_PUBLISHED);
        f.status = Status.PUBLISHED;
        f.publication = Publication.SUPERSEDED; denied(f.context(), TIME, Reason.SUPERSEDED);
        f.publication = Publication.REVOKED; denied(f.context(), TIME, Reason.REVOKED);
        f.publication = Publication.UNKNOWN; denied(f.context(), TIME, Reason.FACTS_UNAVAILABLE);
        f.publication = null; denied(f.context(), TIME, Reason.FACTS_UNAVAILABLE);
        f.publication = Publication.CURRENT; allowed(f.context(), TIME);
        assertThat(old.outcome()).isEqualTo(Outcome.ALLOW);
        // Old allow is not a capability; consumers must obtain the new observations at start.
        assertThat(old.eligibleVersion()).isEqualTo(new Reference(ASSIGNMENT, ID, 1));
    }

    @Test void unavailableInputsAndClockFailWithoutInventingFacts() {
        var f = new EligibilityFixture(); var c = f.context();
        denied(null, TIME, Reason.INVALID_INPUT);
        denied(new Context(1, null, c.subject(), c.facts()), TIME, Reason.ASSIGNMENT_UNAVAILABLE);
        denied(new Context(1, c.assignment(), null, c.facts()), TIME, Reason.SUBJECT_UNAVAILABLE);
        denied(new Context(1, c.assignment(), c.subject(), null), TIME, Reason.FACTS_UNAVAILABLE);
        denied(new Context(1, c.assignment(), c.subject(), new Facts(1, null, Publication.CURRENT,
                Verification.VERIFIED, Verification.VERIFIED, Verification.VERIFIED)), TIME, Reason.INCONSISTENT_FACTS);
        denied(c, null, Reason.CLOCK_UNAVAILABLE);
        allowed(c, TIME);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void unknownDecisionAndFactVersionsFailAndUnknownStoredVersionsCannotBecomeSnapshots(int version) {
        var f = new EligibilityFixture(); f.version = version;
        denied(f.context(), TIME, Reason.UNSUPPORTED_VERSION);
        f.version = 1; f.factsVersion = version;
        denied(f.context(), TIME, Reason.UNSUPPORTED_VERSION);
        var s = f.assignment();
        assertThatThrownBy(() -> new Snapshot(s.id(), s.assignmentId(), s.version(), version, s.status(),
                s.ownerId(), s.scope(), s.definition(), s.recordedAt()))
                .isInstanceOfSatisfying(AssignmentContractException.class, error ->
                        assertThat(error.reason()).isEqualTo(AssignmentContractException.Reason.UNKNOWN_VERSION));
        assertThatThrownBy(() -> new Audience(version, AudienceKind.ROSTER, AUDIENCE, List.of(MEMBER)))
                .isInstanceOfSatisfying(AssignmentContractException.class, error ->
                        assertThat(error.reason()).isEqualTo(AssignmentContractException.Reason.UNKNOWN_VERSION));
    }

    @Test void everyPublicDenialHasTheSameShapeAndNoReasonsIdentifiersOrTiming() throws Exception {
        var f = new EligibilityFixture(); var c = f.context(); var cases = new ArrayList<Decision>();
        cases.add(evaluate(new Context(1, null, c.subject(), c.facts()), TIME));
        f.audience = new Audience(1, AudienceKind.GROUP, AUDIENCE, List.of()); cases.add(evaluate(f.context(), TIME));
        f.audience = c.assignment().definition().audience();
        f.visibility = Verification.DENIED; cases.add(evaluate(f.context(), TIME));
        f.visibility = Verification.VERIFIED;
        f.organization = Verification.DENIED; cases.add(evaluate(f.context(), TIME));
        f.organization = Verification.VERIFIED;
        f.status = Status.DRAFT; cases.add(evaluate(f.context(), TIME));
        f.status = Status.PUBLISHED; cases.add(evaluate(f.context(), TIME.plusSeconds(3600)));
        var json = new ObjectMapper();
        for (var decision : cases) {
            assertThat(decision.outcome()).isEqualTo(Outcome.DENY);
            assertThat(decision.eligibleVersion()).isNull();
            assertThat(json.writeValueAsString(decision.publicDecision())).isEqualTo("{\"version\":1,\"outcome\":\"DENY\"}");
        }
        allowed(f.context(), TIME);
        for (Object safe : List.of(c, c.facts(), c.facts().binding(), evaluate(c, TIME), evaluate(c, TIME).eligibleVersion())) {
            for (UUID id : List.of(ID, ASSIGNMENT, MEMBER, OWNER, ORGANIZATION, AUDIENCE, c.subject().attemptId()))
                assertThat(safe.toString()).doesNotContain(id.toString());
        }
    }

    @Test void evaluationUsesAnImmutableAudienceAndLegacyLinksRemainExplicitlySeparate() {
        var f = new EligibilityFixture(); var members = new ArrayList<>(List.of(MEMBER));
        f.audience = new Audience(1, AudienceKind.ROSTER, AUDIENCE, members);
        var context = f.context(); members.clear();
        allowed(context, TIME);
        assertThat(context.assignment().definition().audience().memberIds()).containsExactly(MEMBER);
        assertThatThrownBy(() -> context.assignment().definition().audience().memberIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(LegacyCompatibility.classify(LegacyCompatibility.Family.SHARE_LINK,
                new LegacyCompatibility.Observation(UUID.randomUUID(), false, false, false, false, false, false)))
                .isEqualTo(LegacyCompatibility.Finding.LEGACY_CAPABILITY);
    }

    @Test void decisionsCannotMisrepresentDenialAsAnEligibleVersion() {
        var reference = new Reference(ASSIGNMENT, ID, 1);
        assertThatThrownBy(() -> new Decision(1, Outcome.DENY, Reason.CLOSED, reference)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(1, Outcome.ALLOW, Reason.ALLOWED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(1, Outcome.ALLOW, Reason.CLOSED, reference)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicDecision(2, Outcome.DENY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reference(ASSIGNMENT, ID, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssignmentEligibility.denied(Reason.ALLOWED)).isInstanceOf(IllegalArgumentException.class);
    }

    static void denied(Context context, Instant now, Reason reason) {
        var decision = evaluate(context, now);
        assertThat(decision.outcome()).isEqualTo(Outcome.DENY);
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.eligibleVersion()).isNull();
    }
    static void allowed(Context context, Instant now) {
        assertThat(evaluate(context, now).outcome()).isEqualTo(Outcome.ALLOW);
    }
}
