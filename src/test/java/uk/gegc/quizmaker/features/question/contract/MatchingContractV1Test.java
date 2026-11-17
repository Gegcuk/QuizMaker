package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.StableResponseNormalizer;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.config.*;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.infra.handler.MatchingHandler;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import static org.assertj.core.api.Assertions.*;

class MatchingContractV1Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MatchingResponseContractV1 response = new MatchingResponseContractV1();
    private final MatchingScoringContractV1 scoring = new MatchingScoringContractV1();

    static Stream<Arguments> golden() {
        return Stream.of(
                Arguments.of("[{\"leftId\":1,\"rightId\":10},{\"leftId\":2,\"rightId\":11}]", 1),
                Arguments.of("[{\"leftId\":2,\"rightId\":11},{\"leftId\":1,\"rightId\":10}]", 1),
                Arguments.of("[]", 0), Arguments.of("[{\"leftId\":1,\"rightId\":10}]", 0),
                Arguments.of("[{\"leftId\":1,\"rightId\":11},{\"leftId\":2,\"rightId\":10}]", 0),
                Arguments.of("[{\"leftId\":1,\"rightId\":11},{\"leftId\":1,\"rightId\":10},{\"leftId\":2,\"rightId\":11}]", 1),
                Arguments.of("[{\"leftId\":1,\"rightId\":10},{\"leftId\":1,\"rightId\":11},{\"leftId\":2,\"rightId\":11}]", 0),
                // Legacy algorithms ignore unused response references; the stable transport rejects foreign UUIDs separately.
                Arguments.of("[{\"leftId\":1,\"rightId\":10},{\"leftId\":2,\"rightId\":11},{\"leftId\":99,\"rightId\":10}]", 1));
    }

    @ParameterizedTest @MethodSource("golden")
    void publicLegacyHandlerAndFrozenAdapterKeepExactPointsAndAcceptance(String pairs, int points) throws Exception {
        var content = authored(); var q = question(content); var payload = JSON.readTree("{\"matches\":" + pairs + "}");
        var attempt = new Attempt(); var before = legacy(attempt, q, payload);
        response.validate(q, payload);
        assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo(Integer.toString(points))
                .isEqualByComparingTo(Double.toString(before.getScore()));
        assertThat(scoring.score(q, payload).possible()).isEqualByComparingTo("1");
        assertThat(attempt.getAnswers()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "{\"matches\":null}", "{\"matches\":[null]}",
            "{\"matches\":[{}]}", "{\"matches\":[{\"leftId\":1,\"rightId\":null}]}",
            "{\"matches\":[{\"leftId\":1.0,\"rightId\":10}]}", "{\"matches\":[{\"leftId\":1,\"rightId\":2147483648}]}"})
    void malformedResponsesFailAtBothBoundariesWithoutAnAnswer(String raw) throws Exception {
        var q = question(authored()); var payload = JSON.readTree(raw); var attempt = new Attempt();
        assertThatThrownBy(() -> legacy(attempt, q, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> response.validate(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"missing-right", "duplicate-left", "duplicate-right", "invalid-media", "missing-text", "fractional-id", "too-few-left", "too-few-right"})
    void invalidAuthorDataIsNotAnAcceptedWrongAnswerAndFixturesReachTheirIntendedRule(String defect) {
        var content = authored();
        switch (defect) {
            case "missing-right" -> ((ObjectNode) content.path("left").get(0)).put("matchId", 999);
            case "duplicate-left" -> ((ObjectNode) content.path("left").get(1)).put("id", 1);
            case "duplicate-right" -> ((ObjectNode) content.path("right").get(1)).put("id", 10);
            case "invalid-media" -> ((ObjectNode) content.path("left").get(0)).putObject("media").put("assetId", "not-a-uuid");
            case "missing-text" -> ((ObjectNode) content.path("left").get(0)).put("text", "   ");
            case "fractional-id" -> ((ObjectNode) content.path("left").get(0)).put("id", 1.0);
            case "too-few-left" -> ((com.fasterxml.jackson.databind.node.ArrayNode) content.get("left")).remove(1);
            case "too-few-right" -> ((com.fasterxml.jackson.databind.node.ArrayNode) content.get("right")).remove(1);
            default -> throw new AssertionError(defect);
        }
        if (!defect.startsWith("too-few")) {
            assertThat(content.path("left")).hasSize(2); assertThat(content.path("right")).hasSize(2);
        }
        var q = question(content); var payload = QuestionContracts.example(QuestionType.MATCHING, QuestionContracts.View.RESPONSE);
        var attempt = new Attempt();
        assertThatThrownBy(() -> legacy(attempt, q, payload)).isInstanceOf(QuestionContentUnavailableException.class);
        assertThatThrownBy(() -> response.validate(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }

    @Test void manyToOneAndIntegerBoundaryIdsKeepLegacySemantics() throws Exception {
        var content = authored();
        ((ObjectNode) content.path("left").get(0)).put("id", Integer.MIN_VALUE).put("matchId", Integer.MAX_VALUE);
        ((ObjectNode) content.path("left").get(1)).put("matchId", Integer.MAX_VALUE);
        ((ObjectNode) content.path("right").get(0)).put("id", Integer.MAX_VALUE);
        var payload = JSON.readTree("{\"matches\":[{\"leftId\":-2147483648,\"rightId\":2147483647},{\"leftId\":2,\"rightId\":2147483647}]}");
        var q = question(content);
        assertThat(legacy(new Attempt(), q, payload).getScore()).isEqualTo(1.0);
        assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo("1");
    }

    @Test void mediaOnlyValidatesAndGradesWithoutOpeningStorageButPresentationRequiresResolvedPublicationMetadata() throws Exception {
        var content = authored(); var item = (ObjectNode) content.path("left").get(0); item.remove("text");
        var media = item.putObject("media").put("assetId", UUID.randomUUID().toString());
        var payload = QuestionContracts.example(QuestionType.MATCHING, QuestionContracts.View.RESPONSE);
        var q = question(content);
        assertThat(legacy(new Attempt(), q, payload).getScore()).isEqualTo(1);
        assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo("1");
        response.validate(q, payload);
        assertThatThrownBy(() -> response.participantContent(q)).isInstanceOf(ContractResolutionException.class);
        media.put("cdnUrl", "https://cdn.example.invalid/retained/original.png").put("width", 640).put("height", 480).put("mimeType", "image/png");
        var rendered = (ParticipantContent.Matching) response.participantContent(question(content));
        assertThat(rendered.left().get(0).text()).isNull(); assertThat(rendered.left().get(0).image().width()).isEqualTo(640);
        assertThat(JSON.writeValueAsString(rendered)).doesNotContain("matchId", "assetId", "private", "sourceKey");
        media.put("cdnUrl", "javascript:alert(1)");
        assertThatThrownBy(() -> response.participantContent(question(content))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void stableTransportPreservesDuplicatesAndRejectsForeignAndWrongSideIds() throws Exception {
        var q = question(authored()); var binding = SupportedQuestionContracts.binding(q);
        var contracts = new uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts(binding, response, scoring);
        var input = JSON.createObjectNode(); var pairs = input.putArray("matches");
        pairs.addObject().put("leftId", q.options().get(0).id().toString()).put("rightId", q.options().get(3).id().toString());
        pairs.addObject().put("leftId", q.options().get(0).id().toString()).put("rightId", q.options().get(2).id().toString());
        pairs.addObject().put("leftId", q.options().get(1).id().toString()).put("rightId", q.options().get(3).id().toString());
        var stable = StableResponseNormalizer.canonical(QuestionType.MATCHING, input);
        assertThat(stable).isEqualTo(input); assertThat(stable.path("matches")).hasSize(3);
        var normalized = StableResponseNormalizer.bind(q, contracts, stable);
        assertThat(scoring.score(q, normalized).earned()).isEqualByComparingTo("1");
        ((ObjectNode) stable.path("matches").get(0)).put("leftId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> StableResponseNormalizer.bind(q, contracts, stable)).isInstanceOf(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.class);
        ((ObjectNode) stable.path("matches").get(0)).put("leftId", q.options().get(2).id().toString());
        assertThatThrownBy(() -> StableResponseNormalizer.bind(q, contracts, stable)).isInstanceOf(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.class);
    }

    @Test void capturedLayoutPinsLeftBlockAndDoesNotExposeSolutions() throws Exception {
        var q = question(authored()); var layout = response.optionLayout(q);
        assertThat(layout.blocks().get(0).pin()).isEqualTo(OptionLayout.Pin.FIRST);
        assertThat(layout.blocks().get(0).optionIds()).isEqualTo(q.options().subList(0, 2).stream().map(QuestionRevision.Option::id).toList());
        var value = (ParticipantContent.Matching) response.participantContent(q);
        assertThat(value.left().get(0).id()).isEqualTo(q.options().get(0).id());
        assertThat(JSON.writeValueAsString(value)).doesNotContain("matchId", "sourceKey", "private", "correct");
        assertThatThrownBy(() -> value.left().clear()).isInstanceOf(UnsupportedOperationException.class);
        var broken = BaselineFamilyFixtures.options(q, q.options().subList(0, 3));
        assertThatThrownBy(() -> response.optionLayout(broken)).isInstanceOf(QuestionContractException.class);
    }

    @Test void exactSpringCatalogueAddsMatchingWithoutMakingHotspotOrComplianceAvailable() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(BaselineQuestionContractConfig.class, OpenQuestionContractConfig.class,
                    MatchingQuestionContractConfig.class, QuestionContractRegistryImpl.class);
            context.refresh(); var registry = context.getBean(QuestionContractRegistry.class);
            var q = question(authored()); var binding = SupportedQuestionContracts.binding(q);
            assertThat(registry.resolve(binding).scoring().version()).isEqualTo(SupportedQuestionContracts.MATCHING_SCORING);
            assertThatThrownBy(() -> registry.resolve(new QuestionContractBinding(q.id(), binding.response(),
                    new ContractVersion(binding.scoring().id(), 99)))).isInstanceOf(ContractResolutionException.class);
            for (var type : List.of(QuestionType.COMPLIANCE, QuestionType.HOTSPOT))
                assertThatThrownBy(() -> SupportedQuestionContracts.response(type)).isInstanceOf(ContractResolutionException.class);
        }
    }

    @Test void existingBoundsApplyToBothColumnsResponsesAndUnknownExtensions() {
        var content = authored(); var left = content.withArray("left");
        for (int i = 3; i <= 100; i++) left.addObject().put("id", i).put("text", "L" + i).put("matchId", 10);
        var q = question(content); var payload = JSON.createObjectNode(); var pairs = payload.putArray("matches");
        for (int i = 0; i < 100; i++) pairs.addObject().put("leftId", 1).put("rightId", 10);
        response.validate(q, payload);
        pairs.addObject().put("leftId", 2).put("rightId", 11);
        assertThatThrownBy(() -> response.validate(q, payload)).isInstanceOf(QuestionContractException.class);
        left.addObject().put("id", 101).put("text", "L101").put("matchId", 10);
        assertThatThrownBy(() -> response.optionLayout(question(content))).isInstanceOf(QuestionContractException.class);
        var large = authored(); large.put("unknown", "x".repeat(10001));
        assertThatThrownBy(() -> response.optionLayout(question(large))).isInstanceOf(QuestionContractException.class);
    }

    private static ObjectNode authored() { return (ObjectNode) QuestionContracts.example(QuestionType.MATCHING, QuestionContracts.View.AUTHOR); }
    static QuestionRevision question(JsonNode content) {
        var options = new ArrayList<QuestionRevision.Option>();
        for (String side : List.of("left", "right")) for (var item : content.path(side))
            options.add(new QuestionRevision.Option(UUID.randomUUID(), side + "/" + item.path("id").asText(), options.size()));
        // Invalid duplicated author IDs are tested independently of revision identity constraints.
        if (options.stream().map(QuestionRevision.Option::sourceKey).distinct().count() != options.size()) options.clear();
        return new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.MATCHING, Difficulty.EASY,
                "Frozen matching", content.toString(), "private hint", "private explanation", null, null,
                Instant.parse("2026-11-17T12:00:00Z"), options);
    }
    private static Answer legacy(Attempt attempt, QuestionRevision q, JsonNode payload) {
        var mutable = new Question(); mutable.setId(q.questionId()); mutable.setType(q.type()); mutable.setContent(q.content());
        return new MatchingHandler().handle(attempt, mutable, new AnswerSubmissionRequest(q.questionId(), payload, false, false, false));
    }
}
