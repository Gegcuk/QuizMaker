package uk.gegc.quizmaker.features.assignment;

import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;

import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;

/** Real immutable #531/#535 contracts; current-state/access observations are local reader fixtures. */
final class EligibilityFixture {
    int version = 1, factsVersion = 1, assignmentVersion = 1;
    UUID assignmentId = ASSIGNMENT, snapshotId = ID, ownerId = OWNER;
    Scope scope = new Scope(ScopeKind.ORGANIZATION, ORGANIZATION);
    Audience audience = new Audience(1, AudienceKind.GROUP, AUDIENCE, List.of(MEMBER));
    Availability availability = new Availability(TIME, TIME.plusSeconds(3600));
    Status status = Status.PUBLISHED;
    AttemptSubject subject = AttemptSubject.authenticated(UUID.randomUUID(), MEMBER);
    Publication publication = Publication.CURRENT;
    Verification access = Verification.VERIFIED, visibility = Verification.VERIFIED, organization = Verification.VERIFIED;
    Binding bindingOverride;

    Snapshot assignment() {
        var original = definition();
        var configuration = new Definition(CONTENT, 1, original.policy(), audience, availability,
                original.capacity(), original.guests());
        return new Snapshot(snapshotId, assignmentId, assignmentVersion, 1, status, ownerId, scope, configuration, TIME);
    }

    Context context() {
        var assignment = assignment();
        var binding = bindingOverride == null && subject != null ? Binding.of(assignment, subject) : bindingOverride;
        return new Context(version, assignment, subject,
                new Facts(factsVersion, binding, publication, access, visibility, organization));
    }
}
