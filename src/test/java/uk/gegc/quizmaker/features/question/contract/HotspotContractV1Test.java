package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.StableResponseNormalizer;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.infra.handler.HotspotHandler;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HotspotContractV1Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HotspotResponseContractV1 response = new HotspotResponseContractV1();
    private final HotspotScoringContractV1 scoring = new HotspotScoringContractV1();

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void anyCorrectRegionAndUnknownIntegerKeepExactLegacyPoints(int selected) {
        var question = question(authored());
        var payload = JSON.createObjectNode().put("selectedRegionId", selected).put("ignored", "extension");
        var attempt = new Attempt(); var original = legacy(attempt, question, payload);
        response.validate(question, payload);
        assertThat(scoring.score(question, payload).earned()).isEqualByComparingTo(selected == 1 || selected == 3 ? "1" : "0")
                .isEqualByComparingTo(Double.toString(original.getScore()));
        assertThat(scoring.score(question, payload).possible()).isEqualByComparingTo("1");
        assertThat(attempt.getAnswers()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "{\"selectedRegionId\":null}",
            "{\"selectedRegionId\":\"1\"}", "{\"selectedRegionId\":true}", "{\"selectedRegionId\":[]}",
            "{\"selectedRegionId\":1.0}", "{\"selectedRegionId\":2147483648}"})
    void malformedResponseIsRejectedBeforeAnAnswerOrZeroGrade(String raw) throws Exception {
        var question = question(authored()); var payload = JSON.readTree(raw); var attempt = new Attempt();
        assertThatThrownBy(() -> legacy(attempt, question, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> response.validate(question, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(question, payload)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"missing-image", "blank-image", "numeric-image", "null-image",
            "too-few", "too-many", "duplicate-id", "missing-id", "fractional-id", "overflow-id", "missing-correct",
            "non-boolean-correct", "no-correct", "negative-x", "negative-height", "fractional-width", "missing-y",
            "image-description", "label-limit", "blank-label-limit", "description-shape"})
    void invalidAuthoredContentKeepsThePublicLegacyClassification(String defect) {
        var content = authored(); var original = question(content);
        var regions = content.withArray("regions"); var first = (ObjectNode) regions.get(0);
        switch (defect) {
            case "missing-image" -> content.remove("imageUrl");
            case "blank-image" -> content.put("imageUrl", "  ");
            case "numeric-image" -> content.put("imageUrl", 12);
            case "null-image" -> content.putNull("imageUrl");
            case "too-few" -> { regions.remove(2); regions.remove(1); }
            case "too-many" -> { for (int i = 4; i <= 7; i++) regions.add(first.deepCopy().put("id", i)); }
            case "duplicate-id" -> ((ObjectNode) regions.get(1)).put("id", 1);
            case "missing-id" -> first.remove("id");
            case "fractional-id" -> first.put("id", 1.0);
            case "overflow-id" -> first.put("id", 2147483648L);
            case "missing-correct" -> first.remove("correct");
            case "non-boolean-correct" -> first.put("correct", "true");
            case "no-correct" -> regions.forEach(r -> ((ObjectNode) r).put("correct", false));
            case "negative-x" -> first.put("x", -1);
            case "negative-height" -> first.put("height", -1);
            case "fractional-width" -> first.put("width", 1.5);
            case "missing-y" -> first.remove("y");
            case "image-description" -> content.put("imageDescription", "x".repeat(2_001));
            case "label-limit" -> first.put("label", "x".repeat(121));
            case "blank-label-limit" -> first.put("label", " ".repeat(121));
            case "description-shape" -> first.put("description", true);
            default -> throw new AssertionError(defect);
        }
        var question = content(original, content); var payload = JSON.createObjectNode().put("selectedRegionId", 1);
        var attempt = new Attempt();
        assertThatThrownBy(() -> legacy(attempt, question, payload)).isInstanceOf(QuestionContentUnavailableException.class);
        assertThatThrownBy(() -> response.validate(question, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(question, payload)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }

    @Test void zeroDimensionsOverlapLargeCoordinatesAndSignedIdsRemainValid() {
        var content = authored(); var regions = content.withArray("regions");
        ((ObjectNode) regions.get(0)).put("id", Integer.MIN_VALUE).put("x", 0).put("y", 0).put("width", 0).put("height", 0);
        ((ObjectNode) regions.get(2)).put("id", Integer.MAX_VALUE).put("x", 0).put("y", 0).put("width", Integer.MAX_VALUE);
        for (int i = 4; i <= 6; i++) regions.add(((ObjectNode) regions.get(0)).deepCopy().put("id", i));
        var question = question(content);
        for (int id : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 4, 5, 6)) {
            var payload = JSON.createObjectNode().put("selectedRegionId", id);
            assertThat(legacy(new Attempt(), question, payload).getScore()).isEqualTo(1);
            assertThat(scoring.score(question, payload).earned()).isEqualByComparingTo("1");
        }
    }

    @Test void optionalManualDescriptionsAndUnicodeLimitsStayFrozen() {
        var content = authored(); content.putNull("imageDescription");
        content.path("regions").forEach(r -> ((ObjectNode) r).put("label", " ".repeat(120)).putNull("description"));
        var question = question(content); var payload = JSON.createObjectNode().put("selectedRegionId", 1);
        assertThat(legacy(new Attempt(), question, payload).getScore()).isEqualTo(1);
        assertThat(scoring.score(question, payload).earned()).isEqualByComparingTo("1");
        var projected = (ParticipantContent.Hotspot) response.participantContent(question);
        assertThat(projected.imageDescription()).isNull();
        assertThat(projected.regions()).allSatisfy(r -> { assertThat(r.label()).isNull(); assertThat(r.description()).isNull(); });
        content.put("imageDescription", "🟢".repeat(2_000));
        ((ObjectNode) content.path("regions").get(0)).put("label", "🟢".repeat(120)).put("description", "🟢".repeat(1_000));
        assertThatCode(() -> response.validate(question(content), payload)).doesNotThrowAnyException();
        assertThat(legacy(new Attempt(), question(content), payload).getScore()).isEqualTo(1);
    }

    @Test void stableReferencesBindOnlyCapturedRegionsAndProjectionNeverContainsSolutionsOrUrls() throws Exception {
        var content = authored(); content.put("private", "private-canary");
        ((ObjectNode) content.path("regions").get(0)).put("answer", "private-canary");
        var question = question(content);
        var pair = new ResolvedQuestionContracts(SupportedQuestionContracts.binding(question), response, scoring);
        var raw = JSON.createObjectNode().put("selectedRegionId", question.options().get(0).id().toString().toUpperCase(Locale.ROOT));
        var stable = StableResponseNormalizer.canonical(QuestionType.HOTSPOT, raw);
        assertThat(scoring.score(question, StableResponseNormalizer.bind(question, pair, stable)).earned()).isEqualByComparingTo("1");
        raw.put("selectedRegionId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> StableResponseNormalizer.bind(question, pair,
                StableResponseNormalizer.canonical(QuestionType.HOTSPOT, raw))).isInstanceOf(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.class);
        var projected = (ParticipantContent.Hotspot) response.participantContent(question);
        assertThat(projected.regions().stream().map(ParticipantContent.HotspotRegion::id).toList())
                .isEqualTo(question.options().stream().map(QuestionRevision.Option::id).toList());
        assertThat(JSON.writeValueAsString(projected)).doesNotContain("correct", "sourceKey", "private-canary", "imageUrl", "assetId", "https:");
        assertThat(JSON.writeValueAsString(projected)).doesNotContain(question.attachmentAssetId().toString());
        assertThatThrownBy(() -> projected.regions().clear()).isInstanceOf(UnsupportedOperationException.class);
        var form = new ParticipantForm(UUID.randomUUID(), 1, List.of(new ParticipantForm.Item(UUID.randomUUID(), 0,
                QuestionType.HOTSPOT, "Diagram", projected)));
        assertThatThrownBy(() -> AttemptFormResponse.items(form)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingRetainedImageRejectsProjectionButDoesNotInventDifferentGrading() {
        var q = question(authored());
        var external = new QuestionRevision(q.id(), q.questionId(), 1, q.type(), q.difficulty(), q.questionText(),
                q.content(), null, null, null, null, q.createdAt(), q.options());
        var payload = JSON.createObjectNode().put("selectedRegionId", 1);
        assertThat(scoring.score(external, payload).earned()).isEqualByComparingTo("1");
        assertThatThrownBy(() -> response.participantContent(external)).isInstanceOf(ContractResolutionException.class);
        var mismatched = new QuestionRevision(q.id(), q.questionId(), 1, q.type(), q.difficulty(), q.questionText(),
                q.content(), null, null, "https://different.invalid/image", q.attachmentAssetId(), q.createdAt(), q.options());
        assertThatThrownBy(() -> response.participantContent(mismatched)).isInstanceOf(ContractResolutionException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"string", "array", "bytes"})
    void ignoredExtensionsCannotBypassTheFrozenResponseBounds(String limit) {
        var payload = JSON.createObjectNode().put("selectedRegionId", 1);
        switch (limit) {
            case "string" -> payload.put("ignored", "x".repeat(10_001));
            case "array" -> { var values = payload.putArray("ignored"); for (int i = 0; i < 101; i++) values.add(i); }
            case "bytes" -> { var values = payload.putArray("ignored"); for (int i = 0; i < 8; i++) values.add("x".repeat(9_000)); }
            default -> throw new AssertionError(limit);
        }
        var question = question(authored()); var attempt = new Attempt();
        assertThatThrownBy(() -> legacy(attempt, question, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> response.validate(question, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(question, payload)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }

    @Test void catalogueUsesExactVersionsAndDoesNotModifyTheFiveFamilyBaseline() {
        var registry = new QuestionContractRegistryImpl(List.of(response), List.of(scoring), new SimpleMeterRegistry());
        var question = question(authored()); var binding = SupportedQuestionContracts.binding(question);
        assertThat(registry.resolve(binding).scoring().version()).isEqualTo(SupportedQuestionContracts.HOTSPOT_SCORING);
        for (boolean unknownResponse : List.of(true, false))
            assertThatThrownBy(() -> registry.resolve(new QuestionContractBinding(question.id(),
                    unknownResponse ? new ContractVersion(binding.response().id(), 99) : binding.response(),
                    unknownResponse ? binding.scoring() : new ContractVersion(binding.scoring().id(), 99))))
                    .isInstanceOf(ContractResolutionException.class);
        assertThatThrownBy(() -> BaselineQuestionContracts.response(QuestionType.HOTSPOT)).isInstanceOf(ContractResolutionException.class);
    }

    private static ObjectNode authored() {
        try { return (ObjectNode) JSON.readTree("""
                {"imageUrl":"https://captured.invalid/diagram","imageDescription":"Three shapes","regions":[
                {"id":1,"x":0,"y":0,"width":20,"height":20,"correct":true,"label":"Circle","description":"Round shape"},
                {"id":2,"x":5,"y":5,"width":0,"height":0,"correct":false},
                {"id":3,"x":0,"y":0,"width":20,"height":20,"correct":true}]}
                """); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static QuestionRevision question(ObjectNode content) {
        var options = new ArrayList<QuestionRevision.Option>();
        for (var region : content.path("regions")) options.add(new QuestionRevision.Option(UUID.randomUUID(),
                region.path("id").asText(), options.size()));
        return new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.HOTSPOT, Difficulty.EASY,
                "Diagram", content.toString(), null, null, content.path("imageUrl").textValue(), UUID.randomUUID(), Instant.EPOCH, options);
    }
    private static QuestionRevision content(QuestionRevision q, ObjectNode content) {
        return new QuestionRevision(q.id(), q.questionId(), 1, q.type(), q.difficulty(), q.questionText(), content.toString(),
                q.hint(), q.explanation(), q.attachmentUrl(), q.attachmentAssetId(), q.createdAt(), q.options());
    }
    private static Answer legacy(Attempt attempt, QuestionRevision q, JsonNode payload) {
        var entity = new Question(); entity.setId(q.questionId()); entity.setType(q.type()); entity.setContent(q.content());
        return new HotspotHandler().handle(attempt, entity, new AnswerSubmissionRequest(q.questionId(), payload, false, false, false));
    }
}
