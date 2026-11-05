package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.config.BaselineQuestionContractConfig;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.question.contract.BaselineFamilyFixtures.*;

@Isolated("Changes default locale to prove the explicitly approved ROOT contract")
class FamilyContractV1Test {
    static Stream<Arguments> golden() throws Exception {
        try (var input = FamilyContractV1Test.class.getResourceAsStream("/question-contracts/baseline-v1-golden.json")) {
            var cases = JSON.readTree(Objects.requireNonNull(input));
            var result = new ArrayList<Arguments>();
            cases.forEach(c -> result.add(Arguments.of(c.path("name").textValue(), c)));
            return result.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("golden")
    void frozenLegacyPayloadsKeepValidationAndExactBinaryPoints(String name, JsonNode fixture) {
        var type = QuestionType.valueOf(fixture.path("family").textValue());
        var snapshot = question(type, fixture.get("content"));
        var response = fixture.get("response");
        var mutable = new Question(); mutable.setId(snapshot.questionId()); mutable.setType(type); mutable.setContent(snapshot.content());
        var attempt = new Attempt();
        var request = new AnswerSubmissionRequest(mutable.getId(), response, false, false, false);
        var validator = new FamilyResponseContractV1(type);
        var scoring = new FamilyScoringContractV1(type);
        if (fixture.get("score").isNull()) {
            assertThatThrownBy(() -> legacy(type).handle(attempt, mutable, request)).isInstanceOf(QuestionContractException.class);
            assertThatThrownBy(() -> validator.validate(snapshot, response)).isInstanceOf(QuestionContractException.class);
            assertThatThrownBy(() -> scoring.score(snapshot, response)).isInstanceOf(QuestionContractException.class);
            assertThat(attempt.getAnswers()).isEmpty();
        } else {
            validator.validate(snapshot, response);
            var before = legacy(type).handle(attempt, mutable, request);
            var after = scoring.score(snapshot, response);
            assertThat(after.earned()).isEqualByComparingTo(fixture.get("score").decimalValue());
            assertThat(after.possible()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(after.earned()).isEqualByComparingTo(BigDecimal.valueOf(before.getScore()));
            assertThat(before.getIsCorrect()).isEqualTo(after.earned().signum() > 0);
            assertThat(attempt.getAnswers()).hasSize(1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = QuestionType.class, names = {"MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE", "FILL_GAP", "ORDERING"})
    void sourceKeysAndSafeOptionIdsRemainStableWithoutLeakingSolutions(QuestionType type) throws Exception {
        var content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
        content.put("privateProvenance", "private-source-marker");
        var q = question(type, content);
        var response = new FamilyResponseContractV1(type);
        var safe = response.participantContent(q);
        String serialized = JSON.writeValueAsString(safe);
        assertThat(serialized).doesNotContain("correct", "private-source-marker", "private hint", "private explanation", "sourceKey");
        var layout = response.optionLayout(q);
        assertThat(layout.blocks().stream().flatMap(b -> b.optionIds().stream()).toList())
                .containsExactlyElementsOf(q.options().stream().map(o -> o.id()).toList());
        q.options().forEach(o -> assertThat(serialized).contains(o.id().toString()));
        var reversed = new ArrayList<>(q.options()); Collections.reverse(reversed);
        assertThat(response.participantContent(options(q, reversed))).isEqualTo(safe);
        if (!q.options().isEmpty()) {
            assertThatThrownBy(() -> response.participantContent(options(q, List.of())))
                    .isInstanceOf(QuestionContractException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "tr-TR", "lt-LT"})
    void fillGapRootNormalizationIsIndependentOfDefaultLocale(String language) throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(language));
            var content = JSON.readTree("{\"text\":\"{1}\",\"gaps\":[{\"id\":1,\"answer\":\"I\"}]}");
            var q = question(QuestionType.FILL_GAP, content);
            var response = JSON.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\" i \"}]}");
            assertThat(new FamilyScoringContractV1(QuestionType.FILL_GAP).score(q, response).earned()).isEqualByComparingTo("1");
            // Pool validation uses the same normalization as grading, including on Turkish hosts.
            ((ObjectNode) content).putArray("options").add("i").add("a").add("b").add("c").add("d").add("e").add("f");
            new FamilyResponseContractV1(QuestionType.FILL_GAP).validate(question(QuestionType.FILL_GAP, content), response);
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void theOwnerApprovedLocaleDifferenceDoesNotChangeLegacy() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var content = JSON.readTree("{\"text\":\"{1}\",\"gaps\":[{\"id\":1,\"answer\":\"I\"}]}");
            var response = JSON.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"i\"}]}");
            var legacy = new Question(); legacy.setType(QuestionType.FILL_GAP); legacy.setContent(content.toString()); legacy.setId(UUID.randomUUID());
            assertThat(legacy(QuestionType.FILL_GAP).handle(new Attempt(), legacy,
                    new AnswerSubmissionRequest(legacy.getId(), response, false, false, false)).getScore()).isZero();
            assertThat(new FamilyScoringContractV1(QuestionType.FILL_GAP).score(question(QuestionType.FILL_GAP, content), response).earned())
                    .isEqualByComparingTo("1");
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void realSpringCatalogueResolvesExactlyFiveFamiliesAndFailsClosedOnUnknownVersions() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(BaselineQuestionContractConfig.class, QuestionContractRegistryImpl.class);
            context.refresh();
            var registry = context.getBean(QuestionContractRegistry.class);
            for (var type : QuestionType.values()) {
                var q = question(type, QuestionContracts.example(type, QuestionContracts.View.AUTHOR));
                if (FAMILIES.contains(type)) {
                    var binding = BaselineQuestionContracts.binding(q);
                    var pair = registry.resolve(binding);
                    var response = QuestionContracts.example(type, QuestionContracts.View.RESPONSE);
                    pair.response().validate(q, response);
                    assertThat(pair.scoring().score(q, response).earned()).isEqualByComparingTo("1");
                    assertThatThrownBy(() -> registry.resolve(new QuestionContractBinding(q.id(),
                            new ContractVersion(binding.response().id(), 2), binding.scoring())))
                            .isInstanceOfSatisfying(ContractResolutionException.class, e -> assertThat(e.reason()).isEqualTo(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT));
                } else {
                    assertThatThrownBy(() -> BaselineQuestionContracts.binding(q)).isInstanceOf(ContractResolutionException.class);
                }
            }
        }
    }

    @Test
    void wrongFamilyAndBrokenStoredContentNeverProduceAGradeOrEchoContent() throws Exception {
        var valid = question(QuestionType.TRUE_FALSE, JSON.readTree("{\"answer\":true}"));
        assertThatThrownBy(() -> new FamilyScoringContractV1(QuestionType.MCQ_SINGLE).score(valid, JSON.createObjectNode()))
                .isInstanceOf(ContractResolutionException.class);
        var broken = question(QuestionType.MCQ_SINGLE, JSON.readTree("{\"options\":[{\"id\":\"a\",\"text\":\"secret-canary\"},{\"id\":\"b\",\"text\":\"b\"}]}"));
        assertThatThrownBy(() -> new FamilyScoringContractV1(QuestionType.MCQ_SINGLE).score(broken, JSON.readTree("{\"selectedOptionId\":\"a\"}")))
                .isInstanceOf(QuestionContractException.class).hasMessageNotContaining("secret-canary");
    }

    @Test
    void mediaCanBeGradedButTextOnlyProjectionMustNotDropIt() throws Exception {
        var content = (ObjectNode) QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR);
        ((ObjectNode) content.path("options").get(0)).putObject("media").put("assetId", UUID.randomUUID().toString());
        var q = question(QuestionType.MCQ_SINGLE, content);
        var response = QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.RESPONSE);
        assertThat(new FamilyScoringContractV1(QuestionType.MCQ_SINGLE).score(q, response).earned()).isEqualByComparingTo("1");
        assertThatThrownBy(() -> new FamilyResponseContractV1(QuestionType.MCQ_SINGLE).participantContent(q))
                .isInstanceOf(ContractResolutionException.class);
    }

    static Stream<Arguments> invalidContent() {
        return Stream.of(
                Arguments.of(QuestionType.MCQ_SINGLE, "{\"options\":[{\"id\":\"a\",\"text\":\"A\",\"correct\":true},{\"id\":\"b\",\"text\":\"B\",\"correct\":true}]}"),
                Arguments.of(QuestionType.MCQ_MULTI, "{\"options\":[{\"id\":\"a\",\"text\":\"A\"},{\"id\":\"b\",\"text\":\"B\"}]}"),
                Arguments.of(QuestionType.FILL_GAP, "{\"text\":\"{2}\",\"gaps\":[{\"id\":2,\"answer\":\"A\"}]}"),
                Arguments.of(QuestionType.FILL_GAP, "{\"text\":\"{2}\",\"gaps\":[{\"id\":1,\"answer\":\"A\"}]}"),
                Arguments.of(QuestionType.FILL_GAP, "{\"text\":\"{99999999999999999999}\",\"gaps\":[{\"id\":1,\"answer\":\"A\"}]}"),
                Arguments.of(QuestionType.FILL_GAP, "{\"text\":\"{1}\",\"gaps\":[{\"id\":1,\"answer\":\" \"}]}"),
                Arguments.of(QuestionType.FILL_GAP, "{\"text\":\"{1}\",\"gaps\":[{\"id\":1,\"answer\":\"A\"}],\"options\":[\"A\",\"B\"]}"),
                Arguments.of(QuestionType.FILL_GAP, "{\"text\":\"{1}\",\"gaps\":[{\"id\":1,\"answer\":\"A\"}],\"options\":[\"A\",\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"),
                Arguments.of(QuestionType.FILL_GAP, "{\"text\":\"{1}\",\"gaps\":[{\"id\":1,\"answer\":\"A\"}],\"options\":[\"x\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}"));
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void invalidStoredAuthoringRulesNeverBecomeGrades(QuestionType type, String raw) throws Exception {
        var q = question(type, JSON.readTree(raw));
        var response = QuestionContracts.example(type, QuestionContracts.View.RESPONSE);
        assertThatThrownBy(() -> new FamilyResponseContractV1(type).validate(q, response)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> new FamilyScoringContractV1(type).score(q, response)).isInstanceOf(QuestionContractException.class);
    }

    @Test
    void exactStringAndArrayLimitsKeepLegacyAcceptanceAndBoundedErrors() {
        var type = QuestionType.MCQ_MULTI;
        var content = QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
        var snapshot = question(type, content);
        var mutable = new Question(); mutable.setId(snapshot.questionId()); mutable.setType(type); mutable.setContent(snapshot.content());
        for (int count : List.of(100, 101)) {
            var response = JSON.createObjectNode();
            var ids = response.putArray("selectedOptionIds");
            for (int i = 0; i < count; i++) ids.add("unknown");
            var request = new AnswerSubmissionRequest(mutable.getId(), response, false, false, false);
            if (count == 100) {
                assertThat(legacy(type).handle(new Attempt(), mutable, request).getScore()).isZero();
                assertThat(new FamilyScoringContractV1(type).score(snapshot, response).earned()).isEqualByComparingTo("0");
            } else {
                assertThatThrownBy(() -> legacy(type).handle(new Attempt(), mutable, request)).isInstanceOf(QuestionContractException.class);
                assertThatThrownBy(() -> new FamilyResponseContractV1(type).validate(snapshot, response)).isInstanceOf(QuestionContractException.class);
            }
        }
        var single = question(QuestionType.MCQ_SINGLE, QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR));
        var bounded = JSON.createObjectNode().put("selectedOptionId", "x".repeat(10_000));
        assertThat(new FamilyScoringContractV1(QuestionType.MCQ_SINGLE).score(single, bounded).earned()).isEqualByComparingTo("0");
        bounded.put("selectedOptionId", "x".repeat(10_001));
        assertThatThrownBy(() -> new FamilyScoringContractV1(QuestionType.MCQ_SINGLE).score(single, bounded))
                .isInstanceOf(QuestionContractException.class).hasMessageNotContaining("xxxxx");
        var tooManyBytes = JSON.createObjectNode().put("selectedOptionId", "a");
        for (int i = 0; i < 7; i++) tooManyBytes.put("extension" + i, "x".repeat(10_000));
        assertThatThrownBy(() -> new FamilyResponseContractV1(QuestionType.MCQ_SINGLE).validate(single, tooManyBytes))
                .isInstanceOf(QuestionContractException.class);
    }
}
