package uk.gegc.quizmaker.features.question.contract;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import uk.gegc.quizmaker.features.attempt.application.StableResponseNormalizer;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.question.contract.BaselineFamilyFixtures.*;

class StableResponseNormalizerTest {
    @ParameterizedTest @EnumSource(value = QuestionType.class, names = {"MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE", "ORDERING", "FILL_GAP"})
    void stableIdsAreBoundToSavedKeysAndPreserveRealV1ValidationAndScoring(QuestionType type) throws Exception {
        var q = question(type, QuestionContracts.example(type, QuestionContracts.View.AUTHOR));
        var source = QuestionContracts.example(type, QuestionContracts.View.RESPONSE);
        var input = source.deepCopy();
        if (type == QuestionType.MCQ_SINGLE)
            ((com.fasterxml.jackson.databind.node.ObjectNode) input).put("selectedOptionId", q.options().stream()
                    .filter(o -> o.sourceKey().equals(source.path("selectedOptionId").asText())).findFirst().orElseThrow().id().toString());
        for (String field : List.of("selectedOptionIds", "orderedItemIds")) if (source.has(field)) {
            var a = ((com.fasterxml.jackson.databind.node.ObjectNode) input).putArray(field);
            source.path(field).forEach(key -> a.add(q.options().stream().filter(o -> o.sourceKey().equals(key.asText()))
                    .findFirst().orElseThrow().id().toString()));
        }
        var pair = new ResolvedQuestionContracts(BaselineQuestionContracts.binding(q), new FamilyResponseContractV1(type), new FamilyScoringContractV1(type));
        var normalized = StableResponseNormalizer.bind(q, pair, StableResponseNormalizer.canonical(type, input));
        assertThat(pair.scoring().score(q, normalized)).isEqualTo(pair.scoring().score(q, source));
        assertThat(input).isNotSameAs(normalized);
        // Reversing display order has no role in this conversion: IDs, not positions, are authoritative.
        if (!q.options().isEmpty()) {
            var changed = new ArrayList<>(q.options()); Collections.reverse(changed);
            assertThat(StableResponseNormalizer.bind(options(q, changed), pair, StableResponseNormalizer.canonical(type, input))).isEqualTo(normalized);
        }
    }

    @Test void multiChoiceSetOrderAndDuplicatesDoNotChangeCanonicalIdentityButOrderingDoes() throws Exception {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var one = JSON.createObjectNode(); one.putArray("selectedOptionIds").add(a).add(b).add(a);
        var two = JSON.createObjectNode(); two.putArray("selectedOptionIds").add(b).add(a);
        assertThat(StableResponseNormalizer.canonical(QuestionType.MCQ_MULTI, one))
                .isEqualTo(StableResponseNormalizer.canonical(QuestionType.MCQ_MULTI, two));
        one.removeAll().putArray("orderedItemIds").add(a).add(b);
        two.removeAll().putArray("orderedItemIds").add(b).add(a);
        assertThat(StableResponseNormalizer.canonical(QuestionType.ORDERING, one))
                .isNotEqualTo(StableResponseNormalizer.canonical(QuestionType.ORDERING, two));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "{\"answer\":1}", "{\"answer\":null}"})
    void malformedBooleanNeverBecomesAnAcceptedFalse(String raw) throws Exception {
        var input = JSON.readTree(raw);
        assertThatThrownBy(() -> StableResponseNormalizer.canonical(QuestionType.TRUE_FALSE, input)).isInstanceOf(ResponseAcceptanceException.class);
    }

    @Test void unknownOptionsAreRejectedAndRawSourceKeysOrIndexesCannotAddressOptions() throws Exception {
        var q = question(QuestionType.MCQ_SINGLE, QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR));
        var pair = new ResolvedQuestionContracts(BaselineQuestionContracts.binding(q), new FamilyResponseContractV1(q.type()), new FamilyScoringContractV1(q.type()));
        for (var value : List.of("0", "a", "1-1-1-1-1", UUID.randomUUID().toString())) {
            var raw = JSON.createObjectNode().put("selectedOptionId", value);
            assertThatThrownBy(() -> StableResponseNormalizer.bind(q, pair, StableResponseNormalizer.canonical(q.type(), raw)))
                    .isInstanceOf(ResponseAcceptanceException.class).hasNoCause();
        }
    }

    @Test void gapMembershipDuplicateIdsAndBoundsAreEnforcedWithoutLeakingText() {
        var q = question(QuestionType.FILL_GAP, QuestionContracts.example(QuestionType.FILL_GAP, QuestionContracts.View.AUTHOR));
        var pair = new ResolvedQuestionContracts(BaselineQuestionContracts.binding(q), new FamilyResponseContractV1(q.type()), new FamilyScoringContractV1(q.type()));
        var raw = JSON.createObjectNode(); raw.putArray("answers").addObject().put("gapId", Integer.MAX_VALUE).put("answer", "private-marker");
        assertThatThrownBy(() -> StableResponseNormalizer.bind(q, pair, StableResponseNormalizer.canonical(q.type(), raw)))
                .isInstanceOf(ResponseAcceptanceException.class).hasMessageNotContaining("private-marker");
        raw.putArray("answers").addObject().put("gapId", 1).put("answer", "x");
        ((com.fasterxml.jackson.databind.node.ArrayNode) raw.path("answers")).addObject().put("gapId", 1).put("answer", "y");
        assertThatThrownBy(() -> StableResponseNormalizer.canonical(q.type(), raw)).isInstanceOf(ResponseAcceptanceException.class);
        raw.putArray("answers").addObject().put("gapId", 1).put("answer", "x".repeat(10001));
        assertThatThrownBy(() -> StableResponseNormalizer.canonical(q.type(), raw)).isInstanceOf(ResponseAcceptanceException.class);
        raw.putArray("answers").addObject().put("gapId", 1).put("answer", "x");
        raw.put("ignored", "x".repeat(65536));
        assertThatThrownBy(() -> StableResponseNormalizer.canonical(q.type(), raw)).isInstanceOf(ResponseAcceptanceException.class);
        var many = JSON.createObjectNode(); var values = many.putArray("selectedOptionIds");
        for (int i = 0; i < 101; i++) values.add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> StableResponseNormalizer.canonical(QuestionType.MCQ_MULTI, many)).isInstanceOf(ResponseAcceptanceException.class);
    }

    @Test void unicodeCharacterAndTotalByteLimitsAreIndependent() {
        var input = JSON.createObjectNode();
        var answers = input.putArray("answers");
        answers.addObject().put("gapId", 1).put("answer", "😀".repeat(10000));
        assertThat(StableResponseNormalizer.canonical(QuestionType.FILL_GAP, input)).isEqualTo(input);
        answers.addObject().put("gapId", 2).put("answer", "😀".repeat(10000));
        assertThatThrownBy(() -> StableResponseNormalizer.canonical(QuestionType.FILL_GAP, input))
                .isInstanceOf(ResponseAcceptanceException.class).hasNoCause();
    }
}
