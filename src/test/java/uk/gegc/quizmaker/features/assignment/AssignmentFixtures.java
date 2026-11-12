package uk.gegc.quizmaker.features.assignment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContractException;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;

final class AssignmentFixtures {
    static final UUID ID = UUID.randomUUID(), ASSIGNMENT = UUID.randomUUID(), OWNER = UUID.randomUUID(),
            ORGANIZATION = UUID.randomUUID(), CONTENT = UUID.randomUUID(), AUDIENCE = UUID.randomUUID(),
            MEMBER = UUID.randomUUID(), OTHER_MEMBER = UUID.randomUUID();
    static final Instant TIME = Instant.parse("2026-11-11T12:00:00Z");
    private AssignmentFixtures() { }
    static Definition definition() {
        return new Definition(CONTENT, 1, new PolicyReference(1, new PresetReference("FREE_PRACTICE", 1)),
                new Audience(1, AudienceKind.GROUP, AUDIENCE, List.of(MEMBER)),
                new Availability(TIME, TIME.plusSeconds(3600)), new Capacity(30, CapacityEnforcement.HARD),
                new GuestAccess(false, DisplayNameRequirement.NONE, EmailRequirement.NONE));
    }
    static Snapshot snapshot(Status status) {
        return new Snapshot(ID, ASSIGNMENT, 1, 1, status, OWNER,
                new Scope(ScopeKind.ORGANIZATION, ORGANIZATION), definition(), TIME);
    }
    static void rejected(Runnable action, AssignmentContractException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AssignmentContractException.class, failure -> {
            org.assertj.core.api.Assertions.assertThat(failure.reason()).isEqualTo(reason);
            org.assertj.core.api.Assertions.assertThat(failure).hasMessage("Assignment contract rejected: " + reason);
            org.assertj.core.api.Assertions.assertThat(failure.getCause()).isNull();
        });
    }
}
