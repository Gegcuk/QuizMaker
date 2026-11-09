package uk.gegc.quizmaker.features.attempt.retake;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.retake.RetakeFixtures.*;

class RetakeEligibilityTest {
    @Test void emptyCompleteHistoryAllowsFirstStartButMissingHistoryDoesNot() {
        var fixture = new Fixture();
        allowed(fixture, NOW);
        fixture.starts = null;
        denied(fixture, Reason.FACTS_UNAVAILABLE);
        fixture.starts = List.of(); fixture.adjustments = null;
        denied(fixture, Reason.FACTS_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(value = Execution.class, names = {"COMPLETED", "EXPIRED", "ABANDONED", "CANCELLED"})
    void everyCommittedTerminalStartConsumesRegardlessOfOutcome(Execution execution) {
        var fixture = new Fixture(); fixture.maximum = 1L;
        fixture.starts = List.of(terminal(execution, NOW));
        denied(fixture, Reason.LIMIT_EXHAUSTED);
        fixture.maximum = 2L;
        allowed(fixture, NOW);
    }

    @ParameterizedTest
    @EnumSource(value = ResultState.class, names = {"PENDING", "AVAILABLE", "INVALIDATED"})
    void resultProductionOrInvalidationNeverRefundsCommittedStart(ResultState state) {
        var fixture = new Fixture(); fixture.maximum = 1L;
        fixture.starts = List.of(result(terminal(Execution.COMPLETED, NOW), state));
        denied(fixture, Reason.LIMIT_EXHAUSTED);
        fixture.adjustments = List.of(adjustment(1));
        allowed(fixture, NOW);
    }

    @Test void classifiedPrecommitTechnicalFailuresConsumeNothingAndStartNoCooldown() {
        var fixture = new Fixture(); fixture.maximum = 1L; fixture.cooldown = Duration.ofDays(1);
        fixture.starts = List.of(failed(), failed(), failed());
        allowed(fixture, NOW);
        var success = terminal(Execution.COMPLETED, NOW.minus(Duration.ofDays(2)));
        fixture.starts = List.of(failed(), success, failed());
        denied(fixture, Reason.LIMIT_EXHAUSTED);
    }

    @Test void unknownCommitIsNotTreatedAsFailedCreationEvenUnderUnlimitedPolicy() {
        var fixture = new Fixture(); fixture.maximum = null;
        fixture.starts = List.of(new StartFact(1, UUID.randomUUID(), SCOPE, Commit.UNKNOWN,
                Execution.NOT_STARTED, null, null, NO_RESULT));
        denied(fixture, Reason.UNKNOWN_OUTCOME);
    }

    @Test void unlimitedHasNoCountCeilingButStillRequiresOneActiveAndKnownContext() {
        var fixture = new Fixture(); fixture.maximum = null;
        fixture.starts = List.of(terminal(Execution.COMPLETED, NOW.minusSeconds(120)), terminal(Execution.CANCELLED, NOW));
        allowed(fixture, NOW);
        fixture.starts = List.of(active());
        denied(fixture, Reason.ACTIVE_ATTEMPT);
        fixture.starts = List.of(active(), active());
        denied(fixture, Reason.INCONSISTENT_FACTS);
        fixture.starts = List.of(); fixture.entry = Entry.CLOSED;
        denied(fixture, Reason.ENTRY_CLOSED);
    }

    @ParameterizedTest
    @EnumSource(value = Execution.class, names = {"COMPLETED", "EXPIRED", "ABANDONED", "CANCELLED"})
    void cooldownUsesActualTerminalTimeAndAllowsExactBoundary(Execution execution) {
        var fixture = new Fixture(); fixture.cooldown = Duration.ofHours(1);
        var end = NOW.minusSeconds(1); var next = end.plusSeconds(3600);
        fixture.starts = List.of(terminal(execution, end));
        assertThat(RetakeEligibility.evaluate(fixture.build(), next.minusNanos(1)))
                .isEqualTo(new Decision(1, Outcome.DENY, Reason.COOLDOWN, next));
        allowed(fixture, next);
        allowed(fixture, next.plusNanos(1));
    }

    @Test void mostRecentTerminalTimeWinsIndependentlyOfListOrderAndResultReadiness() {
        var fixture = new Fixture(); fixture.maximum = 10L; fixture.cooldown = Duration.ofMinutes(30);
        var old = terminal(Execution.COMPLETED, NOW.minusSeconds(3600));
        var recent = result(terminal(Execution.EXPIRED, NOW.minusSeconds(60)), ResultState.PENDING);
        var facts = new ArrayList<>(List.of(old, recent, failed()));
        fixture.starts = facts;
        var expected = new Decision(1, Outcome.DENY, Reason.COOLDOWN, NOW.plusSeconds(1740));
        for (int seed = 0; seed < 30; seed++) {
            Collections.shuffle(facts, new Random(seed));
            assertThat(RetakeEligibility.evaluate(fixture.build(), NOW)).isEqualTo(expected);
        }
    }

    @Test void restorationDoesNotBypassCooldownOrActiveAttemptAndDoesNotChangeHistory() {
        var fixture = new Fixture(); fixture.maximum = 1L; fixture.cooldown = Duration.ofHours(1);
        var committed = terminal(Execution.CANCELLED, NOW);
        fixture.starts = List.of(committed); fixture.adjustments = List.of(adjustment(1));
        assertThat(RetakeEligibility.evaluate(fixture.build(), NOW).reason()).isEqualTo(Reason.COOLDOWN);
        allowed(fixture, NOW.plusSeconds(3600));
        fixture.starts = List.of(active());
        denied(fixture, Reason.ACTIVE_ATTEMPT);
        assertThat(committed.execution()).isEqualTo(Execution.CANCELLED);
    }

    @Test void exhaustedLimitHasNoMisleadingPromiseOfFutureEligibility() {
        var fixture = new Fixture(); fixture.maximum = 1L; fixture.cooldown = Duration.ofHours(1);
        fixture.starts = List.of(terminal(Execution.COMPLETED, NOW));
        denied(fixture, Reason.LIMIT_EXHAUSTED);
        deniedAt(fixture, NOW.plusSeconds(3601), Reason.LIMIT_EXHAUSTED);
    }

    @Test void zeroAndLargeExplicitLimitsAreExactWithoutHiddenDefaultsOrOverflow() {
        var fixture = new Fixture(); fixture.maximum = 0L;
        denied(fixture, Reason.LIMIT_EXHAUSTED);
        fixture.maximum = Long.MAX_VALUE;
        allowed(fixture, NOW);
    }

    @Test void onlyExplicitAuthorizedRestorationsReduceConsumption() {
        var fixture = new Fixture(); fixture.maximum = 2L;
        fixture.starts = List.of(terminal(Execution.COMPLETED, NOW.minusSeconds(120)), terminal(Execution.CANCELLED, NOW));
        denied(fixture, Reason.LIMIT_EXHAUSTED);
        fixture.adjustments = List.of(adjustment(1)); allowed(fixture, NOW);
        fixture.adjustments = List.of(adjustment(2)); allowed(fixture, NOW);
        fixture.adjustments = List.of(adjustment(3)); denied(fixture, Reason.INCONSISTENT_FACTS);
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidRestorationCannotIncreaseAllowance(long amount) {
        var fixture = new Fixture(); fixture.starts = List.of(terminal(Execution.COMPLETED, NOW));
        fixture.adjustments = List.of(adjustment(amount));
        denied(fixture, Reason.INCONSISTENT_FACTS);
    }

    @Test void duplicateRestorationOrAuditReceiptCannotBeCountedTwice() {
        var fixture = new Fixture(); fixture.starts = List.of(terminal(Execution.COMPLETED, NOW.minusSeconds(120)), terminal(Execution.EXPIRED, NOW));
        var first = adjustment(1);
        fixture.adjustments = List.of(first, first); denied(fixture, Reason.INCONSISTENT_FACTS);
        fixture.adjustments = List.of(first, new Adjustment(1, UUID.randomUUID(), SCOPE,
                first.actorId(), first.auditId(), 1, NOW));
        denied(fixture, Reason.INCONSISTENT_FACTS);
    }

    @Test void adjustmentSumAndCooldownOverflowFailClosed() {
        var fixture = new Fixture();
        fixture.adjustments = List.of(adjustment(Long.MAX_VALUE), adjustment(1));
        denied(fixture, Reason.INCONSISTENT_FACTS);
        fixture.adjustments = List.of(); fixture.cooldown = Duration.ofSeconds(Long.MAX_VALUE);
        fixture.starts = List.of(terminal(Execution.COMPLETED, NOW));
        denied(fixture, Reason.INVALID_POLICY);
    }

    @Test void completeInputIsDefensivelyCopiedAndDecisionsDoNotMutateEvidence() {
        var fixture = new Fixture();
        fixture.starts = new ArrayList<>(List.of(terminal(Execution.COMPLETED, NOW)));
        fixture.adjustments = new ArrayList<>(List.of(adjustment(1)));
        var snapshot = fixture.build(); fixture.starts.clear(); fixture.adjustments.clear();
        var expected = RetakeEligibility.evaluate(snapshot, NOW);
        for (int i = 0; i < 10; i++) assertThat(RetakeEligibility.evaluate(snapshot, NOW)).isEqualTo(expected);
        assertThat(snapshot.facts().starts()).hasSize(1); assertThat(snapshot.facts().adjustments()).hasSize(1);
        assertThatThrownBy(() -> snapshot.facts().starts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.facts().adjustments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void addingOneCommittedStartCannotCreateMoreAllowanceAcrossDecisionTable() {
        for (long maximum = 0; maximum <= 5; maximum++) {
            var fixture = new Fixture(); fixture.maximum = maximum;
            for (int consumed = 0; consumed <= 6; consumed++) {
                var starts = new ArrayList<StartFact>();
                for (int i = 0; i < consumed; i++) starts.add(terminal(Execution.COMPLETED, NOW.minusSeconds(i * 120L)));
                fixture.starts = starts;
                var decision = RetakeEligibility.evaluate(fixture.build(), NOW);
                assertThat(decision.outcome()).isEqualTo(consumed < maximum ? Outcome.ALLOW : Outcome.DENY);
                assertThat(decision.nextEligibleAt()).isNull();
            }
        }
    }

    static void allowed(Fixture fixture, Instant now) {
        assertThat(RetakeEligibility.evaluate(fixture.build(), now)).isEqualTo(new Decision(1, Outcome.ALLOW, Reason.ALLOWED, null));
    }
    static void denied(Fixture fixture, Reason reason) { deniedAt(fixture, NOW, reason); }
    static void deniedAt(Fixture fixture, Instant now, Reason reason) {
        assertThat(RetakeEligibility.evaluate(fixture.build(), now)).isEqualTo(RetakeEligibility.denied(reason));
    }
}
