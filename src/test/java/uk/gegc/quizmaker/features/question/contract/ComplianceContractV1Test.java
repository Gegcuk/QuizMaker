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
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.config.*;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.infra.handler.ComplianceHandler;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import static org.assertj.core.api.Assertions.*;

class ComplianceContractV1Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ComplianceResponseContractV1 response = new ComplianceResponseContractV1();
    private final ComplianceScoringContractV1 scoring = new ComplianceScoringContractV1();

    static Stream<Arguments> golden() {
        return Stream.of(Arguments.of("[1,3]", 1), Arguments.of("[3,1]", 1), Arguments.of("[]", 0),
                Arguments.of("[1]", 0), Arguments.of("[1,2,3]", 0), Arguments.of("[1,1,3]", 0),
                Arguments.of("[1,3,999]", 0), Arguments.of("[999]", 0));
    }
    @ParameterizedTest @MethodSource("golden")
    void publicLegacyHandlerAndFrozenAdapterKeepExactAcceptanceAndPoints(String selected, int points) throws Exception {
        var q = question(authored()); var payload = JSON.readTree("{\"selectedStatementIds\":" + selected + "}");
        var attempt = new Attempt(); var before = legacy(attempt, q, payload);
        response.validate(q, payload);
        assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo(Integer.toString(points))
                .isEqualByComparingTo(Double.toString(before.getScore()));
        assertThat(scoring.score(q, payload).possible()).isEqualByComparingTo("1");
        assertThat(attempt.getAnswers()).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "{\"selectedStatementIds\":null}",
            "{\"selectedStatementIds\":[null]}", "{\"selectedStatementIds\":[\"1\"]}",
            "{\"selectedStatementIds\":[true]}", "{\"selectedStatementIds\":[1.0]}", "{\"selectedStatementIds\":[2147483648]}"})
    void malformedResponseCannotBecomeAnAcceptedWrongAnswer(String raw) throws Exception {
        var q = question(authored()); var payload = JSON.readTree(raw); var attempt = new Attempt();
        assertThatThrownBy(() -> legacy(attempt, q, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> response.validate(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"missing-flag", "non-boolean-flag", "none-compliant", "duplicate-id",
            "missing-id", "fractional-id", "too-few", "too-many", "invalid-media", "missing-text"})
    void invalidAuthorContentHasNoAcceptanceOrGrade(String defect) {
        var content = authored(); var statements = content.withArray("statements");
        var first = (ObjectNode) statements.get(0);
        switch (defect) {
            case "missing-flag" -> first.remove("compliant");
            case "non-boolean-flag" -> first.put("compliant", "yes");
            case "none-compliant" -> statements.forEach(s -> ((ObjectNode) s).put("compliant", false));
            case "duplicate-id" -> ((ObjectNode) statements.get(1)).put("id", 1);
            case "missing-id" -> first.remove("id");
            case "fractional-id" -> first.put("id", 1.0);
            case "too-few" -> { statements.remove(2); statements.remove(1); }
            case "too-many" -> { for (int i = 4; i <= 7; i++) statements.addObject().put("id", i).put("text", "S").put("compliant", false); }
            case "invalid-media" -> first.putObject("media").put("assetId", "not-a-uuid");
            case "missing-text" -> first.put("text", "   ");
            default -> throw new AssertionError(defect);
        }
        var q = question(content); var payload = JSON.createObjectNode().putArray("selectedStatementIds").add(1).add(3);
        var input = JSON.createObjectNode().set("selectedStatementIds", payload); var attempt = new Attempt();
        assertThatThrownBy(() -> legacy(attempt, q, input)).isInstanceOf(QuestionContentUnavailableException.class);
        assertThatThrownBy(() -> response.validate(q, input)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(q, input)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }
    @Test void signedIntegerBoundariesAndSixStatementsKeepLegacyRules() throws Exception {
        var content = authored(); var statements = content.withArray("statements");
        ((ObjectNode) statements.get(0)).put("id", Integer.MIN_VALUE);
        ((ObjectNode) statements.get(2)).put("id", Integer.MAX_VALUE);
        for (int i = 4; i <= 6; i++) statements.addObject().put("id", i).put("text", "S" + i).put("compliant", false);
        var payload = JSON.readTree("{\"selectedStatementIds\":[2147483647,-2147483648]}"); var q = question(content);
        assertThat(legacy(new Attempt(), q, payload).getScore()).isEqualTo(1);
        assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo("1");
    }
    @Test void stableTransportSortsWithoutDeduplicatingAndRejectsForeignReferences() {
        var q = question(authored()); var contracts = new ResolvedQuestionContracts(SupportedQuestionContracts.binding(q), response, scoring);
        var input = JSON.createObjectNode(); var ids = input.putArray("selectedStatementIds");
        ids.add(q.options().get(2).id().toString()).add(q.options().get(0).id().toString()).add(q.options().get(0).id().toString());
        var stable = StableResponseNormalizer.canonical(QuestionType.COMPLIANCE, input);
        assertThat(stable.path("selectedStatementIds")).hasSize(3);
        assertThat(scoring.score(q, StableResponseNormalizer.bind(q, contracts, stable)).earned()).isEqualByComparingTo("0");
        ids.remove(2);
        assertThat(scoring.score(q, StableResponseNormalizer.bind(q, contracts,
                StableResponseNormalizer.canonical(QuestionType.COMPLIANCE, input))).earned()).isEqualByComparingTo("1");
        ids.add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> StableResponseNormalizer.bind(q, contracts,
                StableResponseNormalizer.canonical(QuestionType.COMPLIANCE, input))).isInstanceOf(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.class);
    }
    @Test void mediaOnlyStatementKeepsValidationAndScoringButRequiresRetainedMetadataAndCurrentAuthorityToRender() throws Exception {
        var content = authored(); var item = (ObjectNode) content.path("statements").get(0); item.remove("text");
        var media = item.putObject("media").put("assetId", UUID.randomUUID().toString());
        var payload = JSON.readTree("{\"selectedStatementIds\":[1,3]}"); var q = question(content);
        assertThat(legacy(new Attempt(), q, payload).getScore()).isEqualTo(1);
        assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo("1");
        assertThatThrownBy(() -> response.participantContent(q)).isInstanceOf(ContractResolutionException.class);
        media.put("cdnUrl", "https://cdn.example.invalid/private").put("width", 640).put("height", 480).put("mimeType", "image/png");
        var projected = (ParticipantContent.Compliance) response.participantContent(question(content));
        assertThat(projected.statements().get(0).text()).isNull();
        assertThat(projected.statements().get(0).image().width()).isEqualTo(640);
        assertThat(JSON.writeValueAsString(projected)).doesNotContain("compliant", "assetId", "sourceKey", "private", "cdnUrl");
        var form = new uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm(UUID.randomUUID(), 1,
                List.of(new uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm.Item(UUID.randomUUID(), 0, QuestionType.COMPLIANCE, "Media", projected)));
        assertThatThrownBy(() -> AttemptFormResponse.items(form)).isInstanceOf(
                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class);
    }
    @Test void layoutPreservesStableIdentitiesAndProjectionHasNoSolutionFields() throws Exception {
        var q = question(authored()); var projected = (ParticipantContent.Compliance) response.participantContent(q);
        assertThat(response.optionLayout(q).blocks()).hasSize(3);
        assertThat(projected.statements().stream().map(ParticipantContent.ComplianceStatement::id).toList())
                .isEqualTo(q.options().stream().map(QuestionRevision.Option::id).toList());
        assertThat(JSON.writeValueAsString(projected)).doesNotContain("compliant", "sourceKey", "private");
        assertThatThrownBy(() -> projected.statements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.optionLayout(BaselineFamilyFixtures.options(q, q.options().subList(0, 2))))
                .isInstanceOf(QuestionContractException.class);
    }
    @Test void springCatalogueAddsOnlyTheDeclaredFamilyAndFailsClosedOnUnknownVersions() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(BaselineQuestionContractConfig.class, OpenQuestionContractConfig.class,
                    MatchingQuestionContractConfig.class, ComplianceQuestionContractConfig.class, QuestionContractRegistryImpl.class);
            context.refresh(); var registry = context.getBean(QuestionContractRegistry.class);
            var q = question(authored()); var binding = SupportedQuestionContracts.binding(q);
            assertThat(registry.resolve(binding).scoring().version()).isEqualTo(SupportedQuestionContracts.COMPLIANCE_SCORING);
            for (boolean unknownResponse : List.of(true, false))
                assertThatThrownBy(() -> registry.resolve(new QuestionContractBinding(q.id(),
                        unknownResponse ? new ContractVersion(binding.response().id(), 99) : binding.response(),
                        unknownResponse ? binding.scoring() : new ContractVersion(binding.scoring().id(), 99))))
                        .isInstanceOf(ContractResolutionException.class);
            assertThatThrownBy(() -> SupportedQuestionContracts.response(QuestionType.HOTSPOT)).isInstanceOf(ContractResolutionException.class);
            assertThatThrownBy(() -> BaselineQuestionContracts.response(QuestionType.COMPLIANCE)).isInstanceOf(ContractResolutionException.class);
        }
    }
    @Test void existingResponseAndUnknownExtensionBoundsRemainEnforced() {
        var q = question(authored()); var payload = JSON.createObjectNode(); var ids = payload.putArray("selectedStatementIds");
        for (int i = 0; i < 100; i++) ids.add(1);
        response.validate(q, payload); assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo("0");
        ids.add(1); assertThatThrownBy(() -> response.validate(q, payload)).isInstanceOf(QuestionContractException.class);
        var content = authored(); content.put("unknown", "x".repeat(10001));
        assertThatThrownBy(() -> response.optionLayout(question(content))).isInstanceOf(QuestionContractException.class);
    }
    static ObjectNode authored() {
        var value = JSON.createObjectNode(); var statements = value.putArray("statements");
        for (int i = 1; i <= 3; i++) statements.addObject().put("id", i).put("text", "Statement " + i).put("compliant", i != 2);
        return value;
    }
    static QuestionRevision question(JsonNode content) {
        var options = new ArrayList<QuestionRevision.Option>();
        for (var statement : content.path("statements"))
            options.add(new QuestionRevision.Option(UUID.randomUUID(), statement.path("id").asText("missing-" + options.size()), options.size()));
        if (options.stream().map(QuestionRevision.Option::sourceKey).distinct().count() != options.size()) options.clear();
        return new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.COMPLIANCE, Difficulty.EASY,
                "Frozen compliance", content.toString(), "private hint", "private explanation", null, null,
                Instant.parse("2026-11-17T18:00:00Z"), options);
    }
    private static Answer legacy(Attempt attempt, QuestionRevision q, JsonNode payload) {
        var mutable = new Question(); mutable.setId(q.questionId()); mutable.setType(q.type()); mutable.setContent(q.content());
        return new ComplianceHandler().handle(attempt, mutable, new AnswerSubmissionRequest(q.questionId(), payload, false, false, false));
    }
}
