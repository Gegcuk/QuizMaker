package uk.gegc.quizmaker.features.learning.domain.model;

import java.util.Arrays;
import java.util.List;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

/** Q49–Q54/Q67/Q91. Changing any setting requires a new explicitly supported manifest. */
public record FsrsManifest(int version, String artifact, String releaseCommit, String algorithm,
                           int timeMappingVersion, List<Double> parameters, double desiredRetention,
                           List<Long> learningStepsSeconds, List<Long> relearningStepsSeconds,
                           int maximumIntervalDays, boolean fuzz) {
    public static final String ARTIFACT = "io.github.open-spaced-repetition:fsrs:1.0.0";
    public static final String RELEASE_COMMIT = "c561ee6c56239b621a0a45b16a6c2e688441eae5";
    private static final List<Double> WEIGHTS = List.of(
            0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069, 1.5261, 0.112,
            1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2);

    public FsrsManifest {
        require(version == 1 && timeMappingVersion == 1, UNSUPPORTED_VERSION);
        require(parameters != null && learningStepsSeconds != null && relearningStepsSeconds != null
                && parameters.stream().allMatch(v -> v != null)
                && learningStepsSeconds.stream().allMatch(v -> v != null)
                && relearningStepsSeconds.stream().allMatch(v -> v != null), INVALID_INPUT);
        parameters = List.copyOf(parameters);
        learningStepsSeconds = List.copyOf(learningStepsSeconds);
        relearningStepsSeconds = List.copyOf(relearningStepsSeconds);
        require(ARTIFACT.equals(artifact) && RELEASE_COMMIT.equals(releaseCommit) && "FSRS-6".equals(algorithm)
                && WEIGHTS.equals(parameters) && Double.compare(desiredRetention, 0.90) == 0
                && List.of(60L, 600L).equals(learningStepsSeconds)
                && List.of(600L).equals(relearningStepsSeconds) && maximumIntervalDays == 3650 && !fuzz,
                UNSUPPORTED_MANIFEST);
    }

    public static FsrsManifest approved() {
        return new FsrsManifest(1, ARTIFACT, RELEASE_COMMIT, "FSRS-6", 1, WEIGHTS, 0.90,
                List.of(60L, 600L), List.of(600L), 3650, false);
    }

    /** Copies caller arrays; neither these nor upstream mutable arrays become manifest authority. */
    public static FsrsManifest fromArrays(double[] parameters, long[] learningSteps, long[] relearningSteps) {
        require(parameters != null && learningSteps != null && relearningSteps != null, INVALID_INPUT);
        return new FsrsManifest(1, ARTIFACT, RELEASE_COMMIT, "FSRS-6", 1,
                Arrays.stream(parameters).boxed().toList(), 0.90,
                Arrays.stream(learningSteps).boxed().toList(), Arrays.stream(relearningSteps).boxed().toList(), 3650, false);
    }

    @Override public String toString() { return "FsrsManifest[version=" + version + "]"; }
}
