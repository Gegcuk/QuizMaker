package uk.gegc.quizmaker.features.attempt.retake;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.retake.RetakeFixtures.*;
import static uk.gegc.quizmaker.features.attempt.retake.RetakeEligibilityTest.*;

class RetakeEligibilityBoundaryTest {
    @ParameterizedTest @EnumSource(value = Access.class, names = {"DENIED", "MISSING"})
    void missingAuthorityNeverDisclosesHistoryOrAvailability(Access access) {
        var fixture = new Fixture(); fixture.access = access;
        denied(fixture, Reason.ACCESS_DENIED);
        fixture.starts = List.of(active()); fixture.entry = Entry.CLOSED; fixture.factsVersion = -1;
        denied(fixture, Reason.ACCESS_DENIED);
        fixture.scope = null; fixture.knowledge = Knowledge.UNAVAILABLE;
        denied(fixture, Reason.ACCESS_DENIED);
    }

    @Test void missingContextAndAuthorityFailClosed() {
        assertThat(RetakeEligibility.evaluate(null, NOW).reason()).isEqualTo(Reason.INVALID_INPUT);
        var fixture = new Fixture(); fixture.access = null; denied(fixture, Reason.ACCESS_DENIED);
        fixture = new Fixture(); fixture.scope = null; denied(fixture, Reason.INVALID_INPUT);
        fixture = new Fixture(); fixture.entry = null; denied(fixture, Reason.INVALID_INPUT);
        fixture.entry = Entry.UNKNOWN; denied(fixture, Reason.INVALID_INPUT);
        fixture = new Fixture();
        assertThat(RetakeEligibility.evaluate(fixture.build(), null).reason()).isEqualTo(Reason.CLOCK_UNAVAILABLE);
        var context = fixture.build();
        assertThat(RetakeEligibility.evaluate(new Context(1, SCOPE, Access.VERIFIED, Entry.OPEN, null, context.facts()), NOW).reason())
                .isEqualTo(Reason.INVALID_POLICY);
        assertThat(RetakeEligibility.evaluate(new Context(1, SCOPE, Access.VERIFIED, Entry.OPEN, context.policy(), null), NOW).reason())
                .isEqualTo(Reason.FACTS_UNAVAILABLE);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void everyFactAndPolicyVersionIsExplicitlyChecked(int version) {
        var fixture = new Fixture(); fixture.version = version; denied(fixture, Reason.UNSUPPORTED_VERSION);
        fixture = new Fixture(); fixture.policyVersion = version; denied(fixture, Reason.UNSUPPORTED_VERSION);
        fixture = new Fixture(); fixture.factsVersion = version; denied(fixture, Reason.UNSUPPORTED_VERSION);
        var start = terminal(Execution.COMPLETED, NOW);
        fixture = new Fixture(); fixture.starts = List.of(new StartFact(version, start.id(), SCOPE, start.commit(),
                start.execution(), start.startedAt(), start.terminalAt(), NO_RESULT));
        denied(fixture, Reason.UNSUPPORTED_VERSION);
        fixture.starts = List.of(new StartFact(1, start.id(), SCOPE, start.commit(), start.execution(), start.startedAt(),
                start.terminalAt(), new ResultFact(version, null, ResultState.NONE)));
        denied(fixture, Reason.UNSUPPORTED_VERSION);
        fixture.starts = List.of(start);
        var adjustment = adjustment(1);
        fixture.adjustments = List.of(new Adjustment(version, adjustment.id(), SCOPE, adjustment.actorId(),
                adjustment.auditId(), 1, NOW));
        denied(fixture, Reason.UNSUPPORTED_VERSION);
    }

    @Test void subjectContextKindIdentityAndVersionCannotBorrowAnotherHistory() {
        for (Scope scope : List.of(
                new Scope(UUID.randomUUID(), SCOPE.kind(), SCOPE.contextId(), SCOPE.contextVersion()),
                new Scope(SCOPE.subjectId(), ContextKind.PUBLICATION, SCOPE.contextId(), SCOPE.contextVersion()),
                new Scope(SCOPE.subjectId(), SCOPE.kind(), UUID.randomUUID(), SCOPE.contextVersion()),
                new Scope(SCOPE.subjectId(), SCOPE.kind(), SCOPE.contextId(), SCOPE.contextVersion() + 1))) {
            var fixture = new Fixture(); fixture.factsScope = scope;
            denied(fixture, Reason.INCONSISTENT_FACTS);
            fixture.factsScope = SCOPE;
            var start = active(); fixture.starts = List.of(new StartFact(1, start.id(), scope, start.commit(),
                    start.execution(), start.startedAt(), null, NO_RESULT));
            denied(fixture, Reason.INCONSISTENT_FACTS);
            fixture.starts = List.of(terminal(Execution.COMPLETED, NOW));
            fixture.adjustments = List.of(new Adjustment(1, UUID.randomUUID(), scope, UUID.randomUUID(), UUID.randomUUID(), 1, NOW));
            denied(fixture, Reason.INCONSISTENT_FACTS);
        }
    }

    @Test void malformedScopeCannotBeTreatedAsUnlimitedPublicPractice() {
        for (Scope scope : List.of(new Scope(null, SCOPE.kind(), SCOPE.contextId(), 1),
                new Scope(SCOPE.subjectId(), null, SCOPE.contextId(), 1),
                new Scope(SCOPE.subjectId(), SCOPE.kind(), null, 1),
                new Scope(SCOPE.subjectId(), SCOPE.kind(), SCOPE.contextId(), 0))) {
            var fixture = new Fixture(); fixture.scope = scope; fixture.maximum = null;
            denied(fixture, Reason.INVALID_INPUT);
        }
    }

    @Test void outageAndLegacyClassificationNeverBecomeAnEmptyHistory() {
        var fixture = new Fixture(); fixture.maximum = null;
        fixture.knowledge = Knowledge.UNAVAILABLE; denied(fixture, Reason.FACTS_UNAVAILABLE);
        fixture.knowledge = null; denied(fixture, Reason.FACTS_UNAVAILABLE);
        fixture.knowledge = Knowledge.LEGACY_UNCLASSIFIED; denied(fixture, Reason.LEGACY_CLASSIFICATION_REQUIRED);
        fixture.knowledge = Knowledge.COMPLETE; allowed(fixture, NOW);
    }

    @Test void nullDuplicateOrUnscopedStartEvidenceIsNotSilentlyIgnored() {
        var fixture = new Fixture(); var a = active();
        fixture.starts = Arrays.asList(a, null); denied(fixture, Reason.INCONSISTENT_FACTS);
        fixture.starts = List.of(a, a); denied(fixture, Reason.INCONSISTENT_FACTS);
        fixture.starts = List.of(new StartFact(1, null, SCOPE, Commit.COMMITTED, Execution.ACTIVE, NOW, null, NO_RESULT));
        denied(fixture, Reason.INCONSISTENT_FACTS);
        fixture.starts = List.of(new StartFact(1, a.id(), null, Commit.COMMITTED, Execution.ACTIVE, NOW, null, NO_RESULT));
        denied(fixture, Reason.INCONSISTENT_FACTS);
    }

    @Test void missingCommitExecutionAndResultEvidenceFailClosed() {
        var fixture = new Fixture(); var a = active();
        for (StartFact malformed : List.of(
                new StartFact(1, a.id(), SCOPE, null, Execution.ACTIVE, NOW, null, NO_RESULT),
                new StartFact(1, a.id(), SCOPE, Commit.COMMITTED, null, NOW, null, NO_RESULT),
                new StartFact(1, a.id(), SCOPE, Commit.COMMITTED, Execution.ACTIVE, NOW, null, null),
                new StartFact(1, a.id(), SCOPE, Commit.COMMITTED, Execution.ACTIVE, NOW, null, new ResultFact(1, null, null)))) {
            fixture.starts = List.of(malformed); denied(fixture, Reason.INCONSISTENT_FACTS);
        }
    }

    @Test void aTechnicalFailureClassificationCannotHideAnActuallyStartedAttempt() {
        var fixture = new Fixture();
        for (StartFact malformed : List.of(
                new StartFact(1, UUID.randomUUID(), SCOPE, Commit.TECHNICAL_FAILURE_BEFORE_COMMIT, Execution.ACTIVE, null, null, NO_RESULT),
                new StartFact(1, UUID.randomUUID(), SCOPE, Commit.TECHNICAL_FAILURE_BEFORE_COMMIT, Execution.NOT_STARTED, NOW, null, NO_RESULT),
                new StartFact(1, UUID.randomUUID(), SCOPE, Commit.TECHNICAL_FAILURE_BEFORE_COMMIT, Execution.NOT_STARTED, null, NOW, NO_RESULT),
                new StartFact(1, UUID.randomUUID(), SCOPE, Commit.TECHNICAL_FAILURE_BEFORE_COMMIT, Execution.NOT_STARTED, null, null,
                        new ResultFact(1, UUID.randomUUID(), ResultState.AVAILABLE)),
                new StartFact(1, UUID.randomUUID(), SCOPE, Commit.TECHNICAL_FAILURE_BEFORE_COMMIT, Execution.NOT_STARTED, null, null,
                        new ResultFact(1, UUID.randomUUID(), ResultState.NONE)))) {
            fixture.starts = List.of(malformed); denied(fixture, Reason.INCONSISTENT_FACTS);
        }
    }

    @Test void chronologyAndResultIdentityMustAgreeWithCommittedAttempt() {
        var fixture = new Fixture(); var id = UUID.randomUUID();
        for (StartFact malformed : List.of(
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.NOT_STARTED, NOW, null, NO_RESULT),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.ACTIVE, null, null, NO_RESULT),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.ACTIVE, NOW.plusNanos(1), null, NO_RESULT),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.ACTIVE, NOW, NOW, NO_RESULT),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.COMPLETED, NOW, null, NO_RESULT),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.COMPLETED, NOW, NOW.minusNanos(1), NO_RESULT),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.COMPLETED, NOW, NOW.plusNanos(1), NO_RESULT),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.COMPLETED, NOW, NOW,
                        new ResultFact(1, UUID.randomUUID(), ResultState.AVAILABLE)),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.ACTIVE, NOW, null, new ResultFact(1, id, ResultState.NONE)),
                new StartFact(1, id, SCOPE, Commit.COMMITTED, Execution.ACTIVE, NOW, null, new ResultFact(1, id, ResultState.PENDING)))) {
            fixture.starts = List.of(malformed); denied(fixture, Reason.INCONSISTENT_FACTS);
        }
    }

    @Test void invalidAdjustmentEvidenceCannotRestoreAllowance() {
        var fixture = new Fixture(); fixture.starts = List.of(terminal(Execution.COMPLETED, NOW));
        fixture.adjustments = Arrays.asList((Adjustment) null); denied(fixture, Reason.INCONSISTENT_FACTS);
        for (Adjustment malformed : List.of(
                new Adjustment(1, null, SCOPE, UUID.randomUUID(), UUID.randomUUID(), 1, NOW),
                new Adjustment(1, UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), 1, NOW),
                new Adjustment(1, UUID.randomUUID(), SCOPE, null, UUID.randomUUID(), 1, NOW),
                new Adjustment(1, UUID.randomUUID(), SCOPE, UUID.randomUUID(), null, 1, NOW),
                new Adjustment(1, UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), 1, null),
                new Adjustment(1, UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), 1, NOW.plusNanos(1)))) {
            fixture.adjustments = List.of(malformed); denied(fixture, Reason.INCONSISTENT_FACTS);
        }
    }

    @Test void invalidSavedPolicyNeverSelectsADefault() {
        var fixture = new Fixture(); fixture.maximum = -1L; denied(fixture, Reason.INVALID_POLICY);
        fixture.maximum = null; fixture.cooldown = null; denied(fixture, Reason.INVALID_POLICY);
        fixture.cooldown = Duration.ofNanos(-1); denied(fixture, Reason.INVALID_POLICY);
    }

    @Test void closedEntryBlocksNewStartWithoutInventingFinalityOrCertificateFacts() {
        var fixture = new Fixture(); allowed(fixture, NOW);
        fixture.entry = Entry.CLOSED; denied(fixture, Reason.ENTRY_CLOSED);
        fixture.entry = Entry.UNKNOWN; denied(fixture, Reason.INVALID_INPUT);
    }

    @Test void diagnosticRepresentationsAndDenialsDoNotExposePrivateScopeOrAuditIdentity() {
        var fixture = new Fixture(); var start = terminal(Execution.COMPLETED, NOW); var adjustment = adjustment(1);
        fixture.starts = List.of(start); fixture.adjustments = List.of(adjustment);
        var context = fixture.build();
        for (Object value : List.of(SCOPE, context.policy(), context.facts(), start, adjustment, context, NO_RESULT,
                RetakeEligibility.evaluate(context, NOW))) {
            assertThat(value.toString()).doesNotContain(SCOPE.subjectId().toString(), SCOPE.contextId().toString(),
                    start.id().toString(), adjustment.id().toString(), adjustment.actorId().toString(), adjustment.auditId().toString());
        }
    }

    @Test void invalidDecisionShapesCannotPromiseAvailabilityOrLeakADeadlineOnAnotherDenial() {
        assertThatThrownBy(() -> new Decision(2, Outcome.ALLOW, Reason.ALLOWED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(1, Outcome.DENY, Reason.ALLOWED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(1, Outcome.ALLOW, Reason.COOLDOWN, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(1, Outcome.DENY, Reason.COOLDOWN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(1, Outcome.DENY, Reason.ACCESS_DENIED, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
