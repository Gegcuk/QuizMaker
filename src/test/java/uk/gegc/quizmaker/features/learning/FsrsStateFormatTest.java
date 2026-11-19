package uk.gegc.quizmaker.features.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.FsrsFixtures.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;

class FsrsStateFormatTest {
    private final FsrsStateFormat format = new FsrsStateFormat();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void manifestHasIndependentFrozenBytesAndNoReferenceToMutableLibrarySerialization() {
        var expected = "{\"algorithm\":\"FSRS-6\",\"artifact\":\"io.github.open-spaced-repetition:fsrs:1.0.0\","
                + "\"desiredRetention\":0.9,\"fuzz\":false,\"learningStepsSeconds\":[60,600],\"maximumIntervalDays\":3650,"
                + "\"parameters\":[0.2172,1.1771,3.2602,16.1507,7.0114,0.57,2.0966,0.0069,1.5261,0.112,1.0178,1.849,"
                + "0.1133,0.3127,2.2934,0.2191,3.0004,0.7536,0.3332,0.1437,0.2],\"relearningStepsSeconds\":[600],"
                + "\"releaseCommit\":\"c561ee6c56239b621a0a45b16a6c2e688441eae5\",\"timeMappingVersion\":1,\"version\":1}";
        assertThat(format.encodeManifest(FsrsManifest.approved())).isEqualTo(expected);
        assertThat(new FsrsStateFormat().decodeManifest(expected)).isEqualTo(FsrsManifest.approved());
        assertThat(format.encodeManifest(format.decodeManifest(expected))).isEqualTo(expected);
    }

    @Test
    void stateAndReviewEvidenceRetainFullIdentityNanosecondsAndNullNewStateWithoutAssessmentData() throws Exception {
        var original = fresh(TIME);
        var encoded = format.encodeState(original);
        assertThat(format.decodeState(encoded)).isEqualTo(original);
        var root = object(encoded);
        assertThat(root.path("dueAt").textValue()).isEqualTo("2026-10-24T09:00:00.123456789Z");
        for (String field : List.of("stability", "difficulty", "lastReviewAt")) assertThat(root.get(field).isNull()).isTrue();
        assertThat(root.path("sequence").longValue()).isZero();
        assertThat(root.path("identity").size()).isEqualTo(5);
        assertThat(encoded).doesNotContain("cardId", "randomSeed", "@class", "io.github.openspacedrepetition");
        assertThat(root.fieldNames()).toIterable().doesNotContain("score", "mastery", "sessionId", "answer", "correctness");

        var review = command(original, Rating.EASY, TIME);
        var transition = STRATEGY.review(original, review);
        var saved = format.encodeTransition(transition);
        assertThat(new FsrsStateFormat().decodeTransition(saved)).isEqualTo(transition);
        assertThat(format.encodeTransition(format.decodeTransition(saved))).isEqualTo(saved);
        assertThat(json.readTree(saved).at("/review/rating").textValue()).isEqualTo("EASY");
        assertThat(json.readTree(saved).at("/review/reviewedAt").textValue()).isEqualTo(TIME.toString());
        assertThat(transition.state().dueAt()).isEqualTo(TIME.plusSeconds(16 * 86400));
    }

    @Test
    void objectPropertyOrderCannotChangeCanonicalOutputOrReplayedCalculation() throws Exception {
        var state = FsrsSchedulingStrategyTest.source("review-long");
        var encoded = format.encodeState(state);
        var reordered = reverse(object(encoded));
        assertThat(format.encodeState(format.decodeState(reordered.toString()))).isEqualTo(encoded);
        var review = command(state, Rating.GOOD, TIME);
        assertThat(STRATEGY.review(format.decodeState(reordered.toString()), review)).isEqualTo(STRATEGY.review(state, review));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "true", "1", "\"private-input\"", "", " ", "{", "NaN"})
    void malformedOrWrongRootValuesAreRejectedWithoutEchoingInput(String value) {
        rejected(() -> format.decodeState(value), INVALID_FORMAT);
        rejected(() -> format.decodeManifest(value), INVALID_FORMAT);
        rejected(() -> format.decodeTransition(value), INVALID_FORMAT);
    }

    @Test
    void duplicateTrailingUnknownAndOversizedInputsNeverSelectADefault() throws Exception {
        var valid = format.encodeState(fresh(TIME));
        for (String bad : List.of(valid + valid, valid + " true",
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"), " ".repeat(16385))) {
            rejected(() -> format.decodeState(bad), INVALID_FORMAT);
        }
        for (String pointer : List.of("", "/manifest", "/identity")) {
            var root = object(valid); ((ObjectNode) root.at(pointer)).put("private-unknown", "must-not-be-logged");
            rejected(() -> format.decodeState(root.toString()), INVALID_FORMAT);
        }
        rejected(() -> format.decodeState(null), INVALID_FORMAT);
        rejected(() -> format.decodeManifest(null), INVALID_FORMAT);
        rejected(() -> format.decodeTransition(null), INVALID_FORMAT);
        rejected(() -> format.encodeState(null), INVALID_FORMAT);
        rejected(() -> format.encodeManifest(null), INVALID_FORMAT);
        rejected(() -> format.encodeTransition(null), INVALID_FORMAT);
    }

    @Test
    void allFieldsIncludingExplicitNullableFreshValuesMustBePresent() throws Exception {
        var valid = object(format.encodeState(fresh(TIME)));
        for (String pointer : List.of("", "/manifest", "/identity")) {
            var names = new ArrayList<String>(); valid.at(pointer).fieldNames().forEachRemaining(names::add);
            for (String field : names) {
                var missing = valid.deepCopy(); ((ObjectNode) missing.at(pointer)).remove(field);
                rejected(() -> format.decodeState(missing.toString()), INVALID_FORMAT);
            }
        }
        for (String field : List.of("version", "sequence", "identity", "manifest", "phase", "dueAt", "step")) {
            var nil = valid.deepCopy(); nil.putNull(field);
            assertThatThrownBy(() -> format.decodeState(nil.toString())).isInstanceOf(LearningScheduleException.class);
        }
        for (String field : List.of("learnerId", "quizId", "quizRevisionId", "questionRevisionId", "cycleId")) {
            var nil = valid.deepCopy(); ((ObjectNode) nil.get("identity")).putNull(field);
            rejected(() -> format.decodeState(nil.toString()), INVALID_INPUT);
        }
    }

    @Test
    void versionAndManifestChangesFailClosedInsteadOfMigratingOrReinterpretingState() throws Exception {
        var valid = format.encodeState(fresh(TIME));
        for (String pointer : List.of("", "/manifest")) {
            var root = object(valid); ((ObjectNode) root.at(pointer)).put("version", 2);
            rejected(() -> format.decodeState(root.toString()), UNSUPPORTED_VERSION);
        }
        var timeMapping = object(valid); ((ObjectNode) timeMapping.get("manifest")).put("timeMappingVersion", 2);
        rejected(() -> format.decodeState(timeMapping.toString()), UNSUPPORTED_VERSION);
        for (String field : List.of("artifact", "releaseCommit", "algorithm")) {
            var bad = object(valid); ((ObjectNode) bad.get("manifest")).put(field, "private-unsupported");
            rejected(() -> format.decodeState(bad.toString()), UNSUPPORTED_MANIFEST);
        }
        var fuzz = object(valid); ((ObjectNode) fuzz.get("manifest")).put("fuzz", true);
        rejected(() -> format.decodeState(fuzz.toString()), UNSUPPORTED_MANIFEST);
        var parameter = object(valid); parameter.withObject("manifest").withArray("parameters").set(20, json.getNodeFactory().numberNode(.1));
        rejected(() -> format.decodeState(parameter.toString()), UNSUPPORTED_MANIFEST);
        var ceiling = object(valid); ((ObjectNode) ceiling.get("manifest")).put("maximumIntervalDays", 36500);
        rejected(() -> format.decodeState(ceiling.toString()), UNSUPPORTED_MANIFEST);
    }

    @Test
    void scalarCoercionsAndUnknownEnumsAreRejectedAtTheStateBoundary() throws Exception {
        var valid = format.encodeState(fresh(TIME));
        for (String literal : List.of("\"1\"", "1.0", "true", "2147483648")) {
            var root = object(valid); root.set("version", json.readTree(literal));
            rejected(() -> format.decodeState(root.toString()), INVALID_FORMAT);
        }
        var unknown = object(valid); unknown.put("phase", "CUSTOM");
        rejected(() -> format.decodeState(unknown.toString()), INVALID_FORMAT);
        var ordinal = object(valid); ordinal.put("phase", 0);
        rejected(() -> format.decodeState(ordinal.toString()), INVALID_FORMAT);
        var stringBoolean = object(valid); ((ObjectNode) stringBoolean.get("manifest")).put("fuzz", "false");
        rejected(() -> format.decodeState(stringBoolean.toString()), INVALID_FORMAT);
        var invalidTime = object(valid); invalidTime.put("dueAt", "private-invalid-time");
        rejected(() -> format.decodeState(invalidTime.toString()), INVALID_FORMAT);
        var numericTime = object(valid); numericTime.put("dueAt", 1792832400);
        rejected(() -> format.decodeState(numericTime.toString()), INVALID_FORMAT);
        var unknownRating = object(format.encodeTransition(STRATEGY.review(fresh(TIME), command(fresh(TIME), Rating.GOOD, TIME))));
        ((ObjectNode) unknownRating.get("review")).put("rating", "AUTO_GOOD");
        rejected(() -> format.decodeTransition(unknownRating.toString()), INVALID_FORMAT);
    }

    @Test
    void contradictoryStateAndMismatchedTransitionEvidenceCannotBeRestored() throws Exception {
        var valid = format.encodeState(FsrsSchedulingStrategyTest.source("review-long"));
        for (String field : List.of("stability", "difficulty", "lastReviewAt", "dueAt")) {
            var root = object(valid); root.putNull(field);
            rejected(() -> format.decodeState(root.toString()), INVALID_STATE);
        }
        var step = object(valid); step.put("step", 0);
        rejected(() -> format.decodeState(step.toString()), INVALID_STATE);
        var zero = object(valid); zero.put("sequence", 0);
        rejected(() -> format.decodeState(zero.toString()), INVALID_STATE);
        for (double bad : new double[]{-1, 0, .0009, Double.NaN, Double.POSITIVE_INFINITY}) {
            rejected(() -> new State(1, IDENTITY, FsrsManifest.approved(), 1, Phase.REVIEW,
                    null, bad, 5.0, TIME.plusSeconds(60), TIME), INVALID_STATE);
        }
        for (double bad : new double[]{.9, 10.1, Double.NaN, Double.NEGATIVE_INFINITY}) {
            rejected(() -> new State(1, IDENTITY, FsrsManifest.approved(), 1, Phase.REVIEW,
                    null, 1.0, bad, TIME.plusSeconds(60), TIME), INVALID_STATE);
        }
        rejected(() -> new State(1, IDENTITY, FsrsManifest.approved(), 1, Phase.REVIEW,
                null, 1.0, 5.0, TIME, TIME), INVALID_STATE);
        for (int bad : List.of(-1, 2, Integer.MAX_VALUE)) {
            rejected(() -> new State(1, IDENTITY, FsrsManifest.approved(), 1, Phase.LEARNING,
                    bad, 1.0, 5.0, TIME.plusSeconds(60), TIME), INVALID_STATE);
        }
        var original = fresh(TIME);
        var result = STRATEGY.review(original, command(original, Rating.GOOD, TIME));
        var transition = format.encodeTransition(result);
        for (String field : List.of("learnerId", "quizId", "quizRevisionId", "questionRevisionId", "cycleId")) {
            var root = object(transition); ((ObjectNode) root.at("/review/identity")).put(field, id(999).toString());
            rejected(() -> format.decodeTransition(root.toString()), INVALID_STATE);
        }
        var seq = object(transition); ((ObjectNode) seq.get("review")).put("expectedSequence", 1);
        rejected(() -> format.decodeTransition(seq.toString()), INVALID_STATE);
        var time = object(transition); ((ObjectNode) time.get("review")).put("reviewedAt", Instant.EPOCH.toString());
        rejected(() -> format.decodeTransition(time.toString()), INVALID_STATE);
    }

    private ObjectNode object(String value) throws Exception { return (ObjectNode) json.readTree(value); }
    private ObjectNode reverse(ObjectNode original) {
        var names = new ArrayList<String>(); original.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        var result = json.createObjectNode();
        for (String name : names) {
            JsonNode value = original.get(name);
            result.set(name, value.isObject() ? reverse((ObjectNode) value) : value);
        }
        return result;
    }
}
