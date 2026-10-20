package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import static org.assertj.core.api.Assertions.*;

class HotspotSemanticsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void manualLegacyNullAndBlankDescriptionsRemainOptionalButAiRequiresThem() {
        ObjectNode content = described();
        content.remove("imageDescription");
        content.withArray("regions").forEach(region -> {
            ((ObjectNode) region).putNull("label").put("description", "  ");
        });
        assertThatCode(() -> QuestionContracts.validate(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR, content)).doesNotThrowAnyException();
        assertThatThrownBy(() -> HotspotSemantics.validate(content, true))
                .isInstanceOfSatisfying(QuestionContractException.class, e -> assertThat(e.getField()).isEqualTo("content.imageDescription"));
        var safe = QuestionContractTestSupport.safeContentBuilder().buildSafeContent(QuestionType.HOTSPOT, content.toString());
        assertThat(safe.has("imageDescription")).isFalse();
        safe.path("regions").forEach(region -> assertThat(region.has("label") || region.has("description")).isFalse());
    }

    @Test
    void safeDescriptionsStayAssociatedWithIdsAfterReorderWithoutCopyingSolutionFields() {
        ObjectNode content = described();
        content.put("solution", "private-canary");
        ((ObjectNode) content.path("regions").get(0)).put("answer", "private-canary");
        var shuffled = new QuestionContentShuffler(json).shuffleContent(content.toString(), QuestionType.HOTSPOT,
                () -> new java.util.Random() {
                    @Override public int nextInt(int bound) { return 0; }
                });
        var safe = QuestionContractTestSupport.safeContentBuilder().buildSafeContent(QuestionType.HOTSPOT, shuffled);
        assertThat(safe.at("/regions/0/id").intValue()).isEqualTo(2);
        assertThat(safe.toString()).doesNotContain("correct", "solution", "answer", "private-canary");
        for (var region : safe.path("regions")) {
            var original = content.path("regions").get(region.path("id").intValue() - 1);
            assertThat(region.path("label")).isEqualTo(original.path("label"));
            assertThat(region.path("description")).isEqualTo(original.path("description"));
        }
        assertThatCode(() -> QuestionContracts.validate(QuestionType.HOTSPOT, QuestionContracts.View.SAFE, safe)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"label", "description"})
    void strictAiRejectsMissingRegionDescriptionsAtTheirExactField(String field) {
        ObjectNode content = described();
        ((ObjectNode) content.path("regions").get(1)).remove(field);
        assertThatThrownBy(() -> HotspotSemantics.validate(content, true))
                .isInstanceOfSatisfying(QuestionContractException.class, e -> assertThat(e.getField()).isEqualTo("content.regions[1]." + field));
    }

    @Test
    void strictAiRequiresDistinctLabelsButManualContentMayReuseThem() {
        ObjectNode content = described();
        ((ObjectNode) content.path("regions").get(1)).put("label", " CIRCLE ");
        assertThatCode(() -> HotspotSemantics.validate(content, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> HotspotSemantics.validate(content, true))
                .isInstanceOfSatisfying(QuestionContractException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE_LABEL"));
    }

    @Test
    void stringLimitsCountUnicodeCodePointsAndNeverReflectPrivateValues() {
        ObjectNode content = described();
        ((ObjectNode) content.path("regions").get(0)).put("label", "🟢".repeat(120));
        assertThatCode(() -> HotspotSemantics.validate(content, true)).doesNotThrowAnyException();
        ((ObjectNode) content.path("regions").get(0)).put("label", "private-canary".repeat(20));
        assertThatThrownBy(() -> HotspotSemantics.validate(content, false))
                .isInstanceOfSatisfying(QuestionContractException.class, e -> {
                    assertThat(e.getField()).isEqualTo("content.regions[0].label");
                    assertThat(e.getMessage()).doesNotContain("private-canary");
                });
    }

    private ObjectNode described() {
        return (ObjectNode) QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR);
    }
}
