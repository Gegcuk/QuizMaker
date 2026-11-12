package uk.gegc.quizmaker.features.assignment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContractException.Reason.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;

class AssignmentContractTest {
    @ParameterizedTest
    @EnumSource(Status.class)
    void draftAndPublishedIdentityHaveExactVersionedBindings(Status status) {
        var value = snapshot(status);
        assertThat(value.schemaVersion()).isEqualTo(1);
        assertThat(value.version()).isEqualTo(1);
        assertThat(value.assignmentId()).isNotEqualTo(value.id());
        assertThat(value.ownerId()).isEqualTo(OWNER);
        assertThat(value.scope()).isEqualTo(new Scope(ScopeKind.ORGANIZATION, ORGANIZATION));
        assertThat(value.definition().quizRevisionId()).isEqualTo(CONTENT);
        assertThat(value.definition().policy().preset()).isEqualTo(new PresetReference("FREE_PRACTICE", 1));
        assertThat(value.definition().audience().sourceVersionId()).isEqualTo(AUDIENCE);
    }

    @Test
    void capturedMembershipCannotBeChangedThroughEitherInputOrAccessor() {
        var source = new ArrayList<>(List.of(MEMBER, OTHER_MEMBER));
        var audience = new Audience(1, AudienceKind.GROUP, AUDIENCE, source);
        source.clear();
        assertThat(audience.memberIds()).containsExactlyInAnyOrder(MEMBER, OTHER_MEMBER);
        assertThatThrownBy(() -> audience.memberIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new Audience(1, AudienceKind.GROUP, AUDIENCE, List.of(OTHER_MEMBER, MEMBER))).isEqualTo(audience);
        // Empty roster is explicit empty membership, never public access.
        assertThat(new Audience(1, AudienceKind.ROSTER, AUDIENCE, List.of()).memberIds()).isEmpty();
    }

    @Test
    void rejectsAmbiguousAudienceWithoutEchoingIdentity() {
        rejected(() -> new Audience(1, null, AUDIENCE, List.of()), INVALID_AUDIENCE);
        rejected(() -> new Audience(1, AudienceKind.ROSTER, null, List.of()), INVALID_AUDIENCE);
        rejected(() -> new Audience(1, AudienceKind.ROSTER, AUDIENCE, null), INVALID_AUDIENCE);
        rejected(() -> new Audience(1, AudienceKind.ROSTER, AUDIENCE, List.of(MEMBER, MEMBER)), INVALID_AUDIENCE);
        rejected(() -> new Audience(1, AudienceKind.ROSTER, AUDIENCE, java.util.Arrays.asList(MEMBER, null)), INVALID_AUDIENCE);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void unknownSchemaVersionsNeverLoadCurrentDefinitions(int version) {
        rejected(() -> new Audience(version, AudienceKind.ROSTER, AUDIENCE, List.of()), UNKNOWN_VERSION);
        rejected(() -> new PolicyReference(version, new PresetReference("FREE_PRACTICE", 1)), UNKNOWN_VERSION);
        rejected(() -> new Definition(CONTENT, version, definition().policy(), definition().audience(),
                definition().availability(), definition().capacity(), definition().guests()), UNKNOWN_VERSION);
        rejected(() -> new Snapshot(ID, ASSIGNMENT, 1, version, Status.PUBLISHED, OWNER,
                snapshot(Status.PUBLISHED).scope(), definition(), TIME), UNKNOWN_VERSION);
    }

    @Test
    void onlyExactDeliveredPresetVersionsAreAccepted() {
        for (var ref : List.of(new PresetReference("FREE_PRACTICE", 2), new PresetReference("free_practice", 1),
                new PresetReference("latest", 1), new PresetReference("FORMAL", 1))) {
            rejected(() -> new PolicyReference(1, ref), UNKNOWN_POLICY);
        }
        rejected(() -> new PolicyReference(1, null), UNKNOWN_POLICY);
    }

    @Test
    void scopeIsExplicitAndDoesNotGrantOrganizationOrOwnershipAuthority() {
        assertThat(new Scope(ScopeKind.PERSONAL, null).organizationId()).isNull();
        rejected(() -> new Scope(ScopeKind.PERSONAL, ORGANIZATION), INVALID_SCOPE);
        rejected(() -> new Scope(ScopeKind.ORGANIZATION, null), INVALID_SCOPE);
        rejected(() -> new Scope(null, null), INVALID_SCOPE);
    }

    @Test
    void timeBoundsNormalizeToDatabasePrecisionBeforeValidation() {
        assertThat(new Availability(TIME.plusNanos(1234), TIME.plusSeconds(1)).opensAt()).isEqualTo(TIME.plusNanos(1000));
        assertThat(new Availability(null, null).closesAt()).isNull();
        assertThat(new Availability(null, TIME).closesAt()).isEqualTo(TIME);
        assertThat(new Availability(TIME, null).opensAt()).isEqualTo(TIME);
        rejected(() -> new Availability(TIME, TIME), INVALID_WINDOW);
        rejected(() -> new Availability(TIME.plusSeconds(1), TIME), INVALID_WINDOW);
        rejected(() -> new Availability(TIME.plusNanos(1), TIME.plusNanos(999)), INVALID_WINDOW);
        rejected(() -> new Availability(Instant.parse("0999-12-31T23:59:59Z"), null), INVALID_WINDOW);
        rejected(() -> new Availability(null, Instant.parse("+10000-01-01T00:00:00Z")), INVALID_WINDOW);
    }

    @Test
    void configuredCapacityHasApprovedBoundsAndExplicitEnforcement() {
        assertThat(new Capacity(null, null).limit()).isNull();
        for (var mode : CapacityEnforcement.values()) {
            assertThat(new Capacity(1, mode).enforcement()).isEqualTo(mode);
            assertThat(new Capacity(10_000, mode).limit()).isEqualTo(10_000);
        }
        rejected(() -> new Capacity(null, CapacityEnforcement.HARD), INVALID_CAPACITY);
        rejected(() -> new Capacity(1, null), INVALID_CAPACITY);
        rejected(() -> new Capacity(0, CapacityEnforcement.HARD), INVALID_CAPACITY);
        rejected(() -> new Capacity(10_001, CapacityEnforcement.SOFT), INVALID_CAPACITY);
    }

    @Test
    void guestIdentityDimensionsRemainIndependentWithoutImplicitCollection() {
        for (var name : DisplayNameRequirement.values()) for (var email : EmailRequirement.values()) {
            assertThat(new GuestAccess(true, name, email).email()).isEqualTo(email);
        }
        assertThat(new GuestAccess(false, DisplayNameRequirement.NONE, EmailRequirement.NONE).enabled()).isFalse();
        rejected(() -> new GuestAccess(false, DisplayNameRequirement.REQUIRED, EmailRequirement.NONE), INVALID_GUEST_POLICY);
        rejected(() -> new GuestAccess(false, DisplayNameRequirement.NONE, EmailRequirement.OPTIONAL), INVALID_GUEST_POLICY);
        rejected(() -> new GuestAccess(true, null, EmailRequirement.NONE), INVALID_GUEST_POLICY);
        rejected(() -> new GuestAccess(true, DisplayNameRequirement.NONE, null), INVALID_GUEST_POLICY);
    }

    @Test
    void missingSnapshotFieldsAndInvalidVersionFailClosed() {
        var scope = snapshot(Status.DRAFT).scope();
        rejected(() -> new Snapshot(null, ASSIGNMENT, 1, 1, Status.DRAFT, OWNER, scope, definition(), TIME), INVALID_IDENTITY);
        rejected(() -> new Snapshot(ID, null, 1, 1, Status.DRAFT, OWNER, scope, definition(), TIME), INVALID_IDENTITY);
        rejected(() -> new Snapshot(ID, ASSIGNMENT, 1, 1, Status.DRAFT, null, scope, definition(), TIME), INVALID_IDENTITY);
        rejected(() -> new Snapshot(ID, ASSIGNMENT, 0, 1, Status.DRAFT, OWNER, scope, definition(), TIME), INVALID_SNAPSHOT);
        rejected(() -> new Snapshot(ID, ASSIGNMENT, 1, 1, null, OWNER, scope, definition(), TIME), INVALID_SNAPSHOT);
        rejected(() -> new Snapshot(ID, ASSIGNMENT, 1, 1, Status.DRAFT, OWNER, null, definition(), TIME), INVALID_SNAPSHOT);
        rejected(() -> new Snapshot(ID, ASSIGNMENT, 1, 1, Status.DRAFT, OWNER, scope, null, TIME), INVALID_SNAPSHOT);
        rejected(() -> new Snapshot(ID, ASSIGNMENT, 1, 1, Status.DRAFT, OWNER, scope, definition(), null), INVALID_SNAPSHOT);
    }

    @Test
    void incompleteDefinitionsCannotMasqueradeAsValidatedSnapshots() {
        var d = definition();
        rejected(() -> new Definition(null, 1, d.policy(), d.audience(), d.availability(), d.capacity(), d.guests()), INVALID_SNAPSHOT);
        rejected(() -> new Definition(CONTENT, 1, null, d.audience(), d.availability(), d.capacity(), d.guests()), INVALID_SNAPSHOT);
        rejected(() -> new Definition(CONTENT, 1, d.policy(), null, d.availability(), d.capacity(), d.guests()), INVALID_SNAPSHOT);
        rejected(() -> new Definition(CONTENT, 1, d.policy(), d.audience(), null, d.capacity(), d.guests()), INVALID_SNAPSHOT);
        rejected(() -> new Definition(CONTENT, 1, d.policy(), d.audience(), d.availability(), null, d.guests()), INVALID_SNAPSHOT);
        rejected(() -> new Definition(CONTENT, 1, d.policy(), d.audience(), d.availability(), d.capacity(), null), INVALID_SNAPSHOT);
    }

    @Test
    void diagnosticsContainNoRosterOrResourceIdentifiers() {
        var value = snapshot(Status.PUBLISHED);
        for (Object redacted : List.of(value, value.scope(), value.definition(), value.definition().audience())) {
            for (UUID id : List.of(ID, ASSIGNMENT, OWNER, ORGANIZATION, CONTENT, AUDIENCE, MEMBER))
                assertThat(redacted.toString()).doesNotContain(id.toString());
        }
    }

    @Test
    void existingShareLinksRemainLegacyCapabilitiesNotPublishedAssignments() {
        var finding = LegacyCompatibility.classify(LegacyCompatibility.Family.SHARE_LINK,
                new LegacyCompatibility.Observation(UUID.randomUUID(), false, false, false, false, false, false));
        assertThat(finding).isEqualTo(LegacyCompatibility.Finding.LEGACY_CAPABILITY);
        assertThat(finding.classification()).isEqualTo(LegacyCompatibility.Classification.LEGACY_ONLY);
        assertThat(finding.ownerIssue()).isEqualTo(623);
    }
}
