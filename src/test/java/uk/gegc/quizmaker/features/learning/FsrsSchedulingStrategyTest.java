package uk.gegc.quizmaker.features.learning;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gegc.quizmaker.features.learning.application.impl.FsrsSchedulingStrategyImpl;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.FsrsFixtures.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;

class FsrsSchedulingStrategyTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void pinnedGoldenTransitionsUseReachableStatesAndSurviveReconstruction(String name, String[] row) {
        var source = source(row[0]);
        assertState(source, row, 1, 2, 3, 4, 6);
        assertThat(source.lastReviewAt()).isEqualTo(instant(row[5]));
        var command = command(source, Rating.valueOf(row[8]), Instant.parse(row[7]));
        var result = STRATEGY.review(source, command);
        assertState(result.state(), row, 9, 10, 11, 12, 13);
        assertThat(result.state().sequence()).isEqualTo(source.sequence() + 1);
        assertThat(result.state().lastReviewAt()).isEqualTo(command.reviewedAt());
        assertThat(result.state().identity()).isEqualTo(IDENTITY);
        assertThat(result.review()).isEqualTo(command);
        assertThat(result.state().manifest()).isEqualTo(FsrsManifest.approved());
        var codec = new FsrsStateFormat();
        var restored = new FsrsStateFormat().decodeState(codec.encodeState(source));
        assertThat(new FsrsSchedulingStrategyImpl().review(restored, command)).isEqualTo(result);
        assertThat(new FsrsStateFormat().decodeTransition(codec.encodeTransition(result))).isEqualTo(result);
        assertThat(STRATEGY.review(source, command)).isEqualTo(result);
        assertThat(source(row[0])).isEqualTo(source); // Calculating never mutates the captured input.
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> goldenCases() throws Exception {
        try (var input = FsrsSchedulingStrategyTest.class.getResourceAsStream("/learning/fsrs-v1-golden.tsv")) {
            assertThat(input).isNotNull();
            var rows = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank()).map(line -> line.split("\t")).toList();
            assertThat(rows).hasSize(28);
            return rows.stream().map(row -> org.junit.jupiter.params.provider.Arguments.of(row[0] + "/" + row[8], row));
        }
    }

    /** Histories also used to freeze the direct upstream binary fixture, independent of this adapter. */
    static State source(String name) {
        return switch (name) {
            case "fresh" -> fresh(TIME);
            case "learning-short" -> apply(fresh(TIME.minusSeconds(30)), Rating.AGAIN, TIME.minusSeconds(30));
            case "learning-long" -> apply(fresh(TIME.minusSeconds(172800)), Rating.GOOD, TIME.minusSeconds(172800));
            case "review-long" -> apply(fresh(TIME.minusSeconds(1728000)), Rating.EASY, TIME.minusSeconds(1728000));
            case "review-short" -> apply(fresh(TIME.minusSeconds(86399)), Rating.EASY, TIME.minusSeconds(86399));
            case "relearning-short" -> apply(source("review-long"), Rating.AGAIN, TIME.minusSeconds(600));
            case "relearning-long" -> apply(apply(fresh(TIME.minusSeconds(1900800)), Rating.EASY,
                    TIME.minusSeconds(1900800)), Rating.AGAIN, TIME.minusSeconds(172800));
            default -> throw new AssertionError("Unknown fixture");
        };
    }

    @Test
    void firstRatingsHaveIndependentApprovedIntervalsIncludingSixteenDayEasy() {
        var state = fresh(TIME);
        assertThat(apply(state, Rating.AGAIN, TIME).dueAt()).isEqualTo(TIME.plusSeconds(60));
        assertThat(apply(state, Rating.HARD, TIME).dueAt()).isEqualTo(TIME.plusSeconds(330));
        assertThat(apply(state, Rating.GOOD, TIME).dueAt()).isEqualTo(TIME.plusSeconds(600));
        assertThat(apply(state, Rating.EASY, TIME).dueAt()).isEqualTo(TIME.plus(16, ChronoUnit.DAYS));
    }

    @Test
    void fullElapsedDayBoundaryIsDifferentFromProfileMidnightOrOverdueDeadline() {
        var initial = apply(fresh(TIME), Rating.EASY, TIME);
        var early = apply(initial, Rating.GOOD, TIME.plusSeconds(86399));
        var day = apply(initial, Rating.GOOD, TIME.plusSeconds(86400));
        assertThat(early.stability()).isEqualTo(16.1507);
        assertThat(day.stability()).isGreaterThan(early.stability());
        var overdue = apply(initial, Rating.GOOD, TIME.plus(20, ChronoUnit.DAYS));
        assertThat(overdue.lastReviewAt()).isEqualTo(TIME.plus(20, ChronoUnit.DAYS));
        assertThat(overdue.dueAt()).isAfter(overdue.lastReviewAt());
        assertThat(Duration.between(overdue.lastReviewAt(), overdue.dueAt()).toDays())
                .isEqualTo(Math.round(overdue.stability()));
    }

    @ParameterizedTest
    @MethodSource("dstDates")
    void suppliedClockInstantPreservesFullDayArithmeticAcrossDst(Instant start) {
        // Reach a one-day REVIEW through actual AGAIN -> EASY transitions.
        var learning = apply(fresh(start.minusSeconds(60)), Rating.AGAIN, start.minusSeconds(60));
        for (String zone : List.of("Europe/London", "UTC", "America/New_York", "Asia/Tokyo")) {
            var clock = Clock.fixed(start, ZoneId.of(zone));
            var next = apply(learning, Rating.EASY, clock.instant());
            assertThat(next.dueAt()).isEqualTo(start.plus(1, ChronoUnit.DAYS));
            assertThat(Duration.between(start, next.dueAt())).isEqualTo(Duration.ofHours(24));
        }
        var london = ZoneId.of("Europe/London");
        assertThat(start.atZone(london).getHour()).isNotEqualTo(start.plus(1, ChronoUnit.DAYS).atZone(london).getHour());
    }

    static Stream<Instant> dstDates() {
        return Stream.of(Instant.parse("2026-10-24T09:00:00Z"), Instant.parse("2026-03-28T10:00:00Z"));
    }

    @Test
    void repeatedReachableReviewsHitTheApprovedCeilingAndNeverAddFuzz() {
        var state = fresh(TIME);
        boolean ceilingReached = false;
        for (int index = 0; index < 20; index++) {
            var command = command(state, Rating.EASY, state.dueAt());
            var next = STRATEGY.review(state, command);
            var duration = Duration.between(command.reviewedAt(), next.state().dueAt());
            assertThat(duration).isEqualTo(Duration.ofDays(Math.min(3650, Math.max(1, Math.round(next.state().stability())))));
            ceilingReached |= duration.equals(Duration.ofDays(3650));
            for (int repeat = 0; repeat < 8; repeat++) assertThat(STRATEGY.review(state, command)).isEqualTo(next);
            state = new FsrsStateFormat().decodeState(new FsrsStateFormat().encodeState(next.state()));
        }
        assertThat(ceilingReached).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Rating.class)
    void invalidIdentitySequenceAndTimeDoNotAdvanceOrChangeTheInput(Rating rating) {
        var state = source("review-long");
        var identities = List.of(new Identity(id(99), id(2), id(3), id(4), id(5)),
                new Identity(id(1), id(99), id(3), id(4), id(5)),
                new Identity(id(1), id(2), id(99), id(4), id(5)),
                new Identity(id(1), id(2), id(3), id(99), id(5)),
                new Identity(id(1), id(2), id(3), id(4), id(99)));
        for (var identity : identities) rejected(() -> STRATEGY.review(state,
                new Review(id(100), identity, state.sequence(), rating, TIME)), WRONG_SCHEDULE);
        rejected(() -> STRATEGY.review(state, new Review(id(100), IDENTITY, 0, rating, TIME)), STALE_STATE);
        rejected(() -> apply(state, rating, state.lastReviewAt().minusNanos(1)), INVALID_TIME);
        rejected(() -> apply(state, rating, Instant.MAX), INVALID_TIME);
        rejected(() -> apply(fresh(TIME), rating, TIME.minusNanos(1)), INVALID_TIME);
        assertThat(state).isEqualTo(source("review-long"));
        assertThat(apply(state, rating, TIME).sequence()).isEqualTo(state.sequence() + 1);
    }

    @Test
    void replayUsesTheOriginalInputAndDurableDedupRemainsTheWriterResponsibility() {
        var original = fresh(TIME);
        var command = command(original, Rating.GOOD, TIME);
        var committedCandidate = STRATEGY.review(original, command);
        assertThat(STRATEGY.review(new FsrsStateFormat().decodeState(new FsrsStateFormat().encodeState(original)), command))
                .isEqualTo(committedCandidate);
        rejected(() -> STRATEGY.review(committedCandidate.state(), command), STALE_STATE);
        // A different review ID is a distinct candidate, not proof of another committed review.
        var distinct = new Review(id(999), IDENTITY, 0, Rating.GOOD, TIME);
        assertThat(STRATEGY.review(original, distinct).state()).isEqualTo(committedCandidate.state());
        assertThat(STRATEGY.review(original, distinct).review().reviewId()).isNotEqualTo(command.reviewId());
    }

    @Test
    void concurrentCalculationsDoNotShareMutableEngineOrLearnerState() throws Exception {
        var state = source("review-long");
        var executor = Executors.newFixedThreadPool(4);
        try {
            var commands = java.util.stream.IntStream.range(0, 64).mapToObj(index -> {
                var identity = new Identity(id(1000 + index), id(2), id(3), id(4), id(2000 + index));
                var input = new State(1, identity, state.manifest(), state.sequence(), state.phase(), state.step(),
                        state.stability(), state.difficulty(), state.dueAt(), state.lastReviewAt());
                var review = command(input, Rating.values()[index % 4], TIME);
                return new java.util.AbstractMap.SimpleImmutableEntry<>(input, review);
            }).toList();
            var expected = commands.stream().map(entry -> STRATEGY.review(entry.getKey(), entry.getValue())).toList();
            var futures = commands.stream().map(entry -> executor.submit(
                    () -> STRATEGY.review(entry.getKey(), entry.getValue()))).toList();
            for (int index = 0; index < futures.size(); index++) {
                assertThat(futures.get(index).get(15, TimeUnit.SECONDS)).isEqualTo(expected.get(index));
            }
            assertThat(state).isEqualTo(source("review-long"));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void missingInputsAndUnrepresentableArithmeticFailWithBoundedReasons() {
        rejected(() -> STRATEGY.initial(null, FsrsManifest.approved(), TIME), INVALID_INPUT);
        rejected(() -> STRATEGY.initial(IDENTITY, null, TIME), INVALID_INPUT);
        rejected(() -> STRATEGY.initial(IDENTITY, FsrsManifest.approved(), null), INVALID_INPUT);
        rejected(() -> STRATEGY.review(null, command(fresh(TIME), Rating.GOOD, TIME)), INVALID_INPUT);
        rejected(() -> STRATEGY.review(fresh(TIME), null), INVALID_INPUT);
        // Reached engine calculation: its due Instant would overflow; no wrapped library detail escapes.
        rejected(() -> apply(fresh(Instant.MAX), Rating.EASY, Instant.MAX), CALCULATION_FAILED);
        // Numeric fixture for the pinned engine's long-to-int interval narrowing, not enrollment proof.
        var huge = new State(1, IDENTITY, FsrsManifest.approved(), 1, Phase.REVIEW,
                null, 1e12, 5.0, TIME.plusSeconds(86400), TIME);
        rejected(() -> apply(huge, Rating.GOOD, TIME), CALCULATION_FAILED);
        assertThat(apply(source("review-long"), Rating.GOOD, TIME).dueAt()).isAfter(TIME);
    }

    private static void assertState(State actual, String[] row, int phase, int step, int stability, int difficulty, int due) {
        assertThat(actual.phase()).isEqualTo(Phase.valueOf(row[phase]));
        assertThat(actual.step()).isEqualTo(row[step].equals("-") ? null : Integer.valueOf(row[step]));
        assertGoldenNumber(actual.stability(), row[stability]);
        assertGoldenNumber(actual.difficulty(), row[difficulty]);
        assertThat(actual.dueAt()).isEqualTo(Instant.parse(row[due]));
    }

    private static Instant instant(String value) { return value.equals("-") ? null : Instant.parse(value); }
}
