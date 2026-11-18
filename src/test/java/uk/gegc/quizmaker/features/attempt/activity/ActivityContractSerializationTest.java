package uk.gegc.quizmaker.features.attempt.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.*;
import uk.gegc.quizmaker.features.attempt.infra.activity.ActivityContractJson;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.activity.ActivityFixtures.*;
import static uk.gegc.quizmaker.features.attempt.activity.ActivityContractTest.rejected;
import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException.Reason.*;

class ActivityContractSerializationTest {
    private final ActivityContractJson codec = new ActivityContractJson();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void completedUngradedEnvelopeHasAnExplicitDiscriminatorAndNoNullableAssessmentFields() throws Exception {
        var completed = new Completed(1, TIME);
        var encoded = codec.encodeOutcome(completed);
        assertThat(encoded).isEqualTo("{\"version\":1,\"kind\":\"UNGRADED\",\"completion\":{\"state\":\"COMPLETED\",\"completedAt\":\"2026-11-18T12:00:00.123456Z\"}}");
        assertThat(codec.decodeOutcome(encoded)).isEqualTo(completed);
        assertThat(new ActivityContractJson().decodeOutcome(encoded)).isEqualTo(completed);
        assertNoAssessmentFields(json.readTree(encoded));
    }

    @ParameterizedTest
    @EnumSource(IncompleteState.class)
    void incompleteAbandonedAndExpiredOutcomesRoundTripWithoutCompletedAt(IncompleteState state) throws Exception {
        var outcome = new Incomplete(1, state);
        var encoded = codec.encodeOutcome(outcome);
        var tree = json.readTree(encoded);
        assertThat(tree.get("completion").size()).isEqualTo(1);
        assertThat(tree.at("/completion/state").textValue()).isEqualTo(state.name());
        assertThat(codec.decodeOutcome(encoded)).isEqualTo(outcome);
        assertNoAssessmentFields(tree);
    }

    @ParameterizedTest
    @EnumSource(ResponseRetention.class)
    void ungradedPoliciesKeepExplicitRetentionAndRequiredFlagsWithoutAHiddenScoringPolicy(ResponseRetention retention) throws Exception {
        var policy = ungraded(retention);
        var encoded = codec.encodePolicy(policy);
        assertThat(codec.decodePolicy(encoded)).isEqualTo(policy);
        assertThat(codec.encodePolicy(codec.decodePolicy(encoded))).isEqualTo(encoded);
        assertThat(json.readTree(encoded).path("responseRetention").textValue()).isEqualTo(retention.name());
        assertThat(json.readTree(encoded).at("/participation/items/0/required").booleanValue()).isTrue();
        assertThat(json.readTree(encoded).at("/participation/items/1/required").booleanValue()).isFalse();
        assertNoAssessmentFields(json.readTree(encoded));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void existingGradedResultRoundTripsIncludingActualZeroAndPassSemantics(int earned) {
        var expected = new GradedOutcome(1, gradedResult(earned));
        var encoded = codec.encodeOutcome(expected);
        assertThat(codec.decodeOutcome(encoded)).isEqualTo(expected);
        assertThat(codec.encodeOutcome(codec.decodeOutcome(encoded))).isEqualTo(encoded);
        assertThat(((GradedOutcome) codec.decodeOutcome(encoded)).result().score().earned()).isEqualTo(earned);
    }

    @Test
    void gradedResultWithUnansweredItemsKeepsItsExplicitNullResponseReference() throws Exception {
        var original = gradedResult(0);
        var item = original.items().get(0);
        var unanswered = new AutomaticResult.Item(item.formItemId(), item.questionRevisionId(), null,
                item.position(), item.responseContract(), item.scoringContract(), 0);
        var result = new AutomaticResult(original.id(), original.version(), original.submissionId(), original.attemptId(),
                original.formId(), original.policySnapshotId(), original.quizRevisionId(), original.policyHash(),
                original.score(), original.createdAt(), List.of(unanswered, original.items().get(1)));
        var encoded = codec.encodeOutcome(new GradedOutcome(1, result));
        assertThat(json.readTree(encoded).at("/result/items/0/responseId").isNull()).isTrue();
        assertThat(codec.decodeOutcome(encoded)).isEqualTo(new GradedOutcome(1, result));
        // Absence of the versioned field and null required identity/score remain distinct errors.
        for (String field : List.of("formItemId", "questionRevisionId", "responseContract", "scoringContract")) {
            var invalid = object(encoded); ((ObjectNode) invalid.at("/result/items/0")).putNull(field);
            rejected(() -> codec.decodeOutcome(invalid.toString()), INVALID_INPUT);
        }
        var missing = object(encoded); ((ObjectNode) missing.at("/result/items/0")).remove("responseId");
        rejected(() -> codec.decodeOutcome(missing.toString()), INVALID_INPUT);
        var nullPosition = object(encoded); ((ObjectNode) nullPosition.at("/result/items/0")).putNull("position");
        rejected(() -> codec.decodeOutcome(nullPosition.toString()), INVALID_INPUT);
        var nullScore = object(encoded); ((ObjectNode) nullScore.get("result")).putNull("score");
        rejected(() -> codec.decodeOutcome(nullScore.toString()), INVALID_INPUT);
    }

    @Test
    void gradedPolicyKeepsExistingCanonicalBytesHashAndProvenance() {
        var policy = gradedPolicy();
        var format = new CanonicalPolicyFormat();
        var before = format.encode(BuiltInPracticePreset.REFERENCE, policy, Map.of());
        var decoded = (GradedPolicy) codec.decodePolicy(codec.encodePolicy(new GradedPolicy(1, policy)));
        assertThat(decoded.policy()).isEqualTo(policy);
        assertThat(format.encode(BuiltInPracticePreset.REFERENCE, decoded.policy(), Map.of())).isEqualTo(before);
        assertThat(format.verify(before)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"score", "denominator", "possible", "isCorrect", "correctness", "passed", "grading", "selfRating", "confidence", "result", "answer"})
    void assessmentAndResponseFieldsAreRejectedAtEveryUngradedEnvelopeLevelEvenWhenNullOrZero(String field) throws Exception {
        for (var value : List.of(json.nullNode(), json.getNodeFactory().numberNode(0))) {
            var outcome = object(codec.encodeOutcome(new Completed(1, TIME)));
            outcome.set(field, value);
            rejected(() -> codec.decodeOutcome(outcome.toString()), INVALID_INPUT);
            var nested = object(codec.encodeOutcome(new Completed(1, TIME)));
            ((ObjectNode) nested.get("completion")).set(field, value);
            rejected(() -> codec.decodeOutcome(nested.toString()), INVALID_INPUT);
            var policy = object(codec.encodePolicy(ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES)));
            policy.set(field, value);
            rejected(() -> codec.decodePolicy(policy.toString()), INVALID_INPUT);
            var participation = object(codec.encodePolicy(ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES)));
            ((ObjectNode) participation.get("participation")).set(field, value);
            rejected(() -> codec.decodePolicy(participation.toString()), INVALID_INPUT);
            var item = object(codec.encodePolicy(ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES)));
            ((ObjectNode) item.at("/participation/items/0")).set(field, value);
            rejected(() -> codec.decodePolicy(item.toString()), INVALID_INPUT);
        }
    }

    @Test
    void incompleteAndCompletedShapesCannotBorrowEachOthersFieldsOrOmitRequiredFields() throws Exception {
        var completed = codec.encodeOutcome(new Completed(1, TIME));
        var incomplete = codec.encodeOutcome(new Incomplete(1, IncompleteState.IN_PROGRESS));
        for (String field : List.of("version", "kind", "completion")) {
            var missing = object(completed); missing.remove(field);
            rejected(() -> codec.decodeOutcome(missing.toString()), INVALID_INPUT);
            var nil = object(completed); nil.putNull(field);
            rejected(() -> codec.decodeOutcome(nil.toString()), INVALID_INPUT);
        }
        var missingTime = object(completed); ((ObjectNode) missingTime.get("completion")).remove("completedAt");
        rejected(() -> codec.decodeOutcome(missingTime.toString()), INVALID_INPUT);
        var missingState = object(completed); ((ObjectNode) missingState.get("completion")).remove("state");
        rejected(() -> codec.decodeOutcome(missingState.toString()), INVALID_INPUT);
        var nilTime = object(completed); ((ObjectNode) nilTime.get("completion")).putNull("completedAt");
        rejected(() -> codec.decodeOutcome(nilTime.toString()), INVALID_INPUT);
        var invalidTime = object(completed); ((ObjectNode) invalidTime.get("completion")).put("completedAt", "private-invalid-time");
        rejected(() -> codec.decodeOutcome(invalidTime.toString()), INVALID_INPUT);
        var numericTime = object(completed); ((ObjectNode) numericTime.get("completion")).put("completedAt", 1);
        rejected(() -> codec.decodeOutcome(numericTime.toString()), INVALID_INPUT);
        var contradictory = object(incomplete); ((ObjectNode) contradictory.get("completion")).put("completedAt", TIME.toString());
        rejected(() -> codec.decodeOutcome(contradictory.toString()), INVALID_INPUT);
        var unknown = object(incomplete); ((ObjectNode) unknown.get("completion")).put("state", "SUBMITTED");
        rejected(() -> codec.decodeOutcome(unknown.toString()), UNKNOWN_VALUE);
        var notObject = object(completed); notObject.put("completion", "COMPLETED");
        rejected(() -> codec.decodeOutcome(notObject.toString()), INVALID_INPUT);
    }

    @Test
    void noRetentionAndParticipationCannotBeInferredFromMissingOrMalformedFields() throws Exception {
        var valid = codec.encodePolicy(ungraded(ResponseRetention.COMPLETION_ONLY_NO_RESPONSE_RETENTION));
        for (String field : List.of("version", "kind", "responseRetention", "participation")) {
            var missing = object(valid); missing.remove(field);
            rejected(() -> codec.decodePolicy(missing.toString()), INVALID_INPUT);
            var nil = object(valid); nil.putNull(field);
            rejected(() -> codec.decodePolicy(nil.toString()), INVALID_INPUT);
        }
        var mutations = new ArrayList<ObjectNode>();
        var noItems = object(valid); ((ObjectNode) noItems.get("participation")).remove("items"); mutations.add(noItems);
        var nilItems = object(valid); ((ObjectNode) nilItems.get("participation")).putNull("items"); mutations.add(nilItems);
        var wrongItems = object(valid); ((ObjectNode) wrongItems.get("participation")).put("items", true); mutations.add(wrongItems);
        var nullItem = object(valid); nullItem.withObject("participation").withArray("items").set(0, json.nullNode()); mutations.add(nullItem);
        var duplicate = object(valid); duplicate.withObject("participation").withArray("items").add(duplicate.at("/participation/items/0").deepCopy()); mutations.add(duplicate);
        for (String field : List.of("required", "formItemId")) {
            var missing = object(valid); ((ObjectNode) missing.at("/participation/items/0")).remove(field); mutations.add(missing);
            var nil = object(valid); ((ObjectNode) nil.at("/participation/items/0")).putNull(field); mutations.add(nil);
        }
        var stringBoolean = object(valid); ((ObjectNode) stringBoolean.at("/participation/items/0")).put("required", "true"); mutations.add(stringBoolean);
        var numberBoolean = object(valid); ((ObjectNode) numberBoolean.at("/participation/items/0")).put("required", 1); mutations.add(numberBoolean);
        var shortId = object(valid); ((ObjectNode) shortId.at("/participation/items/0")).put("formItemId", "0-0-0-0-1"); mutations.add(shortId);
        var invalidId = object(valid); ((ObjectNode) invalidId.at("/participation/items/0")).put("formItemId", "private-invalid-id"); mutations.add(invalidId);
        for (var bad : mutations) rejected(() -> codec.decodePolicy(bad.toString()), INVALID_INPUT);
        var unknownRetention = object(valid); unknownRetention.put("responseRetention", "LATEST_DEFAULT");
        rejected(() -> codec.decodePolicy(unknownRetention.toString()), UNKNOWN_VALUE);
    }

    @Test
    void unknownVersionsKindsAndScalarCoercionsNeverBecomeLegacyOrCurrentDefaults() throws Exception {
        var valid = codec.encodeOutcome(new Completed(1, TIME));
        for (String bad : List.of("null", "[]", "{}", "true", "1", "\"UNGRADED\"", "", " ")) {
            rejected(() -> codec.decodePolicy(bad), INVALID_INPUT);
            rejected(() -> codec.decodeOutcome(bad), INVALID_INPUT);
        }
        for (String bad : List.of("-1", "0", "2", "2147483647")) {
            var root = object(valid); root.set("version", json.readTree(bad));
            rejected(() -> codec.decodeOutcome(root.toString()), UNSUPPORTED_VERSION);
            rejected(() -> codec.decodePolicy(root.toString()), UNSUPPORTED_VERSION);
        }
        for (String bad : List.of("\"1\"", "1.0", "true", "2147483648")) {
            var root = object(valid); root.set("version", json.readTree(bad));
            rejected(() -> codec.decodeOutcome(root.toString()), INVALID_INPUT);
            rejected(() -> codec.decodePolicy(root.toString()), INVALID_INPUT);
        }
        for (String bad : List.of("LEGACY", "ungraded", "UNGRADED ", "private-response-secret")) {
            var root = object(valid); root.put("kind", bad);
            rejected(() -> codec.decodeOutcome(root.toString()), UNKNOWN_KIND);
            rejected(() -> codec.decodePolicy(root.toString()), UNKNOWN_KIND);
        }
        rejected(() -> codec.decodeOutcome(valid + valid), INVALID_INPUT);
        rejected(() -> codec.decodePolicy(valid + valid), INVALID_INPUT);
        rejected(() -> codec.decodeOutcome(valid.replace("\"version\":1", "\"version\":1,\"version\":1")), INVALID_INPUT);
        rejected(() -> codec.decodeOutcome(valid.replace("\"state\":\"COMPLETED\"", "\"state\":\"IN_PROGRESS\",\"state\":\"COMPLETED\"")), INVALID_INPUT);
        rejected(() -> codec.decodePolicy(null), INVALID_INPUT);
        rejected(() -> codec.decodeOutcome(null), INVALID_INPUT);
        rejected(() -> codec.encodePolicy(null), INVALID_INPUT);
        rejected(() -> codec.encodeOutcome(null), INVALID_INPUT);
    }

    @Test
    void malformedGradedDataCannotUseTheUngradedBranchOrChangeExistingScoreRules() throws Exception {
        var encoded = codec.encodeOutcome(new GradedOutcome(1, gradedResult(0)));
        var disguised = object(encoded); disguised.put("kind", "UNGRADED");
        rejected(() -> codec.decodeOutcome(disguised.toString()), INVALID_INPUT);
        var contradictory = object(encoded); ((ObjectNode) contradictory.at("/result/score")).put("passed", true);
        rejected(() -> codec.decodeOutcome(contradictory.toString()), INVALID_INPUT);
        var scalarCoercion = object(encoded); ((ObjectNode) scalarCoercion.at("/result/score")).put("earned", 0.5);
        rejected(() -> codec.decodeOutcome(scalarCoercion.toString()), INVALID_INPUT);
        var missing = object(encoded); ((ObjectNode) missing.get("result")).remove("score");
        rejected(() -> codec.decodeOutcome(missing.toString()), INVALID_INPUT);
        var unknown = object(encoded); ((ObjectNode) unknown.get("result")).put("privateExtra", "secret");
        rejected(() -> codec.decodeOutcome(unknown.toString()), INVALID_INPUT);
        var nil = object(encoded); nil.putNull("result");
        rejected(() -> codec.decodeOutcome(nil.toString()), INVALID_INPUT);
        var policy = object(codec.encodePolicy(new GradedPolicy(1, gradedPolicy())));
        ((ObjectNode) policy.get("policy")).put("privateExtra", "secret");
        rejected(() -> codec.decodePolicy(policy.toString()), INVALID_INPUT);
        assertThat(codec.decodeOutcome(encoded)).isEqualTo(new GradedOutcome(1, gradedResult(0)));
    }

    private ObjectNode object(String value) throws Exception { return (ObjectNode) json.readTree(value); }

    private static void assertNoAssessmentFields(JsonNode tree) {
        if (tree.isObject()) tree.fields().forEachRemaining(field -> {
            assertThat(field.getKey()).doesNotMatch("(?i).*(score|correct|pass|denominator|grading|rating|answer|feedback|payload).*");
            assertNoAssessmentFields(field.getValue());
        });
        else if (tree.isArray()) tree.forEach(ActivityContractSerializationTest::assertNoAssessmentFields);
    }
}
