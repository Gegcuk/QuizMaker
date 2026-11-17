package uk.gegc.quizmaker.features.attempt.application.impl;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import uk.gegc.quizmaker.features.attempt.application.impl.LegacyAttemptTimeoutMySqlIntegrationTest.Outcome;
import uk.gegc.quizmaker.features.attempt.application.impl.LegacyAttemptTimeoutMySqlIntegrationTest.RaceWatchdog;
import static org.assertj.core.api.Assertions.*;

/** Plain tests of the failure reporting; these do not claim a database interleaving. */
@Timeout(180)
class LegacyRaceWatchdogTest {
    @Test void reachedGateAllowsAPendingWorkerAndCleanupReleasesAndJoinsIt() {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var race = new RaceWatchdog(release);
        try (race) {
            race.first.submit(() -> { reached.countDown(); race.holdFirst(); return new Outcome(true, null); });
            race.awaitGate(reached, race.first, "synthetic held checkpoint");
            assertThat(race.first.future.isDone()).isFalse();
        }
        assertThat(race.first.executor.isTerminated()).isTrue();
        assertThat(race.second.executor.isTerminated()).isTrue();
    }

    @Test void originalWorkerExceptionIsReportedBeforeAnUnreachedGate() {
        var original = new IllegalStateException("synthetic worker failure");
        assertThatThrownBy(() -> {
            try (var race = new RaceWatchdog(new CountDownLatch(1))) {
                race.first.future = CompletableFuture.failedFuture(original);
                race.awaitGate(new CountDownLatch(1), race.first, "first locked time check");
            }
        }).isInstanceOf(AssertionError.class).hasCause(original)
                .hasMessageContaining("legacy-time-first failed before first locked time check")
                .hasMessageContaining("future=DONE").hasMessageContaining("legacy-time-second");
    }

    @Test void businessRejectionBeforeTheGateKeepsTheActualReason() {
        try (var race = new RaceWatchdog(new CountDownLatch(1))) {
            race.first.future = CompletableFuture.completedFuture(new Outcome(false, "Cannot submit answer to attempt with status COMPLETED"));
            assertThatThrownBy(() -> race.awaitGate(new CountDownLatch(1), race.first, "first locked time check"))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("completed before first locked time check")
                    .hasMessageContaining("success=false").hasMessageContaining("Cannot submit answer to attempt with status COMPLETED");
        }
    }

    @Test void successfulCompletionDoesNotSubstituteForARequiredGate() {
        try (var race = new RaceWatchdog(new CountDownLatch(1))) {
            race.first.future = CompletableFuture.completedFuture(new Outcome(true, null));
            assertThatThrownBy(() -> race.awaitGate(new CountDownLatch(1), race.first, "required checkpoint"))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("completed before required checkpoint").hasMessageContaining("success=true");
        }
    }

    @Test void cancellationBeforeTheGateIsReportedInsteadOfWaitingForTheWatchdog() {
        try (var race = new RaceWatchdog(new CountDownLatch(1))) {
            var cancelled = new CompletableFuture<Outcome>(); cancelled.cancel(false); race.first.future = cancelled;
            assertThatThrownBy(() -> race.awaitGate(new CountDownLatch(1), race.first, "required checkpoint"))
                    .isInstanceOf(AssertionError.class).hasCauseInstanceOf(java.util.concurrent.CancellationException.class)
                    .hasMessageContaining("was cancelled before required checkpoint").hasMessageContaining("future=CANCELLED");
        }
    }

    @Test void reachedGateAndACompletedWorkerAreAValidObservation() {
        try (var race = new RaceWatchdog(new CountDownLatch(1))) {
            race.first.future = CompletableFuture.completedFuture(new Outcome(true, null));
            assertThatCode(() -> race.awaitGate(new CountDownLatch(0), race.first, "already reached checkpoint")).doesNotThrowAnyException();
            assertThat(race.result(race.first).success()).isTrue();
        }
    }

    @Test void timeoutReportsPhasesFutureStatesAndBothParticipantsWithoutLosingCleanup() throws Exception {
        var started = new CountDownLatch(1); var blocked = new CountDownLatch(1);
        var race = new RaceWatchdog(new CountDownLatch(1), Duration.ZERO, Duration.ofSeconds(120));
        try (race) {
            race.first.submit(() -> { started.countDown(); blocked.await(); return new Outcome(true, null); });
            assertThat(started.await(120, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> race.awaitGate(new CountDownLatch(1), race.first, "unreached checkpoint"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Timed out waiting for unreached checkpoint")
                    .hasMessageContaining("legacy-time-first phase=COMMAND_STARTED future=PENDING state=").hasMessageContaining("\n  at ")
                    .hasMessageContaining("legacy-time-second phase=NOT_STARTED future=NOT_SUBMITTED state=NOT_STARTED");
        }
        assertThat(race.first.executor.isTerminated()).isTrue();
        assertThat(race.second.executor.isTerminated()).isTrue();
    }
}
