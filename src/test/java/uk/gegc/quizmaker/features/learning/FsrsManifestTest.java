package uk.gegc.quizmaker.features.learning;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.FsrsFixtures.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;

class FsrsManifestTest {
    private static final double[] EXPECTED = {0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966,
            0.0069, 1.5261, 0.112, 1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191, 3.0004,
            0.7536, 0.3332, 0.1437, 0.2};

    @Test
    void approvedManifestPinsEverySettingInsteadOfUsingLibraryDefaults() {
        var manifest = FsrsManifest.approved();
        assertThat(manifest.parameters().stream().mapToDouble(Double::doubleValue).toArray()).containsExactly(EXPECTED);
        assertThat(manifest.artifact()).isEqualTo("io.github.open-spaced-repetition:fsrs:1.0.0");
        assertThat(manifest.releaseCommit()).isEqualTo("c561ee6c56239b621a0a45b16a6c2e688441eae5");
        assertThat(manifest.algorithm()).isEqualTo("FSRS-6");
        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.timeMappingVersion()).isEqualTo(1);
        assertThat(manifest.desiredRetention()).isEqualTo(0.90);
        assertThat(manifest.learningStepsSeconds()).containsExactly(60L, 600L);
        assertThat(manifest.relearningStepsSeconds()).containsExactly(600L);
        assertThat(manifest.maximumIntervalDays()).isEqualTo(3650);
        assertThat(manifest.fuzz()).isFalse();
    }

    @Test
    void callerArraysAndListsCannotChangeACapturedManifestOrLaterCalculations() {
        var weights = EXPECTED.clone();
        var learning = new long[]{60, 600};
        var relearning = new long[]{600};
        var captured = FsrsManifest.fromArrays(weights, learning, relearning);
        var state = STRATEGY.initial(IDENTITY, captured, TIME);
        var command = command(state, uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating.EASY, TIME);
        var expected = STRATEGY.review(state, command);
        weights[0] = 999; learning[0] = 999; relearning[0] = 999;
        assertThat(captured).isEqualTo(FsrsManifest.approved());
        assertThat(STRATEGY.review(state, command)).isEqualTo(expected);
        assertThatThrownBy(() -> captured.parameters().set(0, 999.0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> captured.learningStepsSeconds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> captured.relearningStepsSeconds().clear()).isInstanceOf(UnsupportedOperationException.class);

        var list = new ArrayList<>(captured.parameters());
        var listCaptured = new FsrsManifest(1, captured.artifact(), captured.releaseCommit(), "FSRS-6", 1,
                list, .9, learningList(), List.of(600L), 3650, false);
        list.set(1, 99.0);
        assertThat(listCaptured).isEqualTo(captured);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2, 2147483647})
    void unknownManifestAndTimeMappingVersionsNeverBecomeCurrentDefaults(int version) {
        var m = FsrsManifest.approved();
        rejected(() -> new FsrsManifest(version, m.artifact(), m.releaseCommit(), m.algorithm(), 1,
                m.parameters(), .9, learningList(), List.of(600L), 3650, false), UNSUPPORTED_VERSION);
        rejected(() -> new FsrsManifest(1, m.artifact(), m.releaseCommit(), m.algorithm(), version,
                m.parameters(), .9, learningList(), List.of(600L), 3650, false), UNSUPPORTED_VERSION);
    }

    @Test
    void unsupportedArtifactParametersStepsRetentionCeilingAndFuzzAreRejected() {
        var m = FsrsManifest.approved();
        for (var bad : List.of("fsrs:latest", "io.github.open-spaced-repetition:fsrs:1.0.1", "private-value")) {
            rejected(() -> new FsrsManifest(1, bad, m.releaseCommit(), m.algorithm(), 1, m.parameters(), .9,
                    learningList(), List.of(600L), 3650, false), UNSUPPORTED_MANIFEST);
            rejected(() -> new FsrsManifest(1, m.artifact(), bad, m.algorithm(), 1, m.parameters(), .9,
                    learningList(), List.of(600L), 3650, false), UNSUPPORTED_MANIFEST);
            rejected(() -> new FsrsManifest(1, m.artifact(), m.releaseCommit(), bad, 1, m.parameters(), .9,
                    learningList(), List.of(600L), 3650, false), UNSUPPORTED_MANIFEST);
        }
        for (double bad : new double[]{0, .85, .95, Double.NaN, Double.POSITIVE_INFINITY}) {
            rejected(() -> new FsrsManifest(1, m.artifact(), m.releaseCommit(), m.algorithm(), 1, m.parameters(), bad,
                    learningList(), List.of(600L), 3650, false), UNSUPPORTED_MANIFEST);
        }
        for (int index = 0; index < EXPECTED.length; index++) {
            var bad = EXPECTED.clone(); bad[index] += .001;
            rejected(() -> FsrsManifest.fromArrays(bad, new long[]{60, 600}, new long[]{600}), UNSUPPORTED_MANIFEST);
        }
        rejected(() -> FsrsManifest.fromArrays(new double[20], new long[]{60, 600}, new long[]{600}), UNSUPPORTED_MANIFEST);
        rejected(() -> FsrsManifest.fromArrays(EXPECTED, new long[]{600, 60}, new long[]{600}), UNSUPPORTED_MANIFEST);
        rejected(() -> FsrsManifest.fromArrays(EXPECTED, new long[]{60, 600}, new long[]{60}), UNSUPPORTED_MANIFEST);
        rejected(() -> new FsrsManifest(1, m.artifact(), m.releaseCommit(), m.algorithm(), 1, m.parameters(), .9,
                learningList(), List.of(600L), 36500, false), UNSUPPORTED_MANIFEST);
        rejected(() -> new FsrsManifest(1, m.artifact(), m.releaseCommit(), m.algorithm(), 1, m.parameters(), .9,
                learningList(), List.of(600L), 3650, true), UNSUPPORTED_MANIFEST);
        rejected(() -> FsrsManifest.fromArrays(null, new long[]{60, 600}, new long[]{600}), INVALID_INPUT);
        rejected(() -> FsrsManifest.fromArrays(EXPECTED, null, new long[]{600}), INVALID_INPUT);
        rejected(() -> FsrsManifest.fromArrays(EXPECTED, new long[]{60, 600}, null), INVALID_INPUT);
        var nullWeight = new ArrayList<>(m.parameters()); nullWeight.set(0, null);
        rejected(() -> new FsrsManifest(1, m.artifact(), m.releaseCommit(), m.algorithm(), 1, nullWeight, .9,
                learningList(), List.of(600L), 3650, false), INVALID_INPUT);
    }

    private static List<Long> learningList() { return List.of(60L, 600L); }
}
