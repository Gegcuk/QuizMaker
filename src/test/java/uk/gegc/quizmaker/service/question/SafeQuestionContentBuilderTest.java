package uk.gegc.quizmaker.service.question;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.application.SafeQuestionContentBuilder;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class SafeQuestionContentBuilderTest {

    private SafeQuestionContentBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SafeQuestionContentBuilder();
    }

    @Test
    void buildSafeContent_matching_hidesAnswers_andShufflesRight() throws Exception {
        String raw = """
                {
                  "left": [
                    {"id":1,"text":"Apple","matchId":10},
                    {"id":2,"text":"Banana","matchId":11}
                  ],
                  "right": [
                    {"id":10,"text":"Red"},
                    {"id":11,"text":"Yellow"}
                  ]
                }
                """;

        JsonNode safe = builder.buildSafeContent(QuestionType.MATCHING, raw);

        // Ensure shape exists
        assertNotNull(safe);
        assertTrue(safe.has("left"));
        assertTrue(safe.has("right"));
        assertTrue(safe.get("left").isArray());
        assertTrue(safe.get("right").isArray());

        // Left side: no matchId, contains id/text
        for (JsonNode l : safe.get("left")) {
            assertTrue(l.has("id"));
            assertTrue(l.has("text"));
            assertFalse(l.has("matchId"), "Left item should not expose matchId");
        }

        // Right side: contains id/text only
        for (JsonNode r : safe.get("right")) {
            assertTrue(r.has("id"));
            assertTrue(r.has("text"));
            assertFalse(r.has("matchId"));
        }

        // Validate counts and id sets match originals (order may differ)
        Set<Integer> leftIds = new HashSet<>();
        safe.get("left").forEach(n -> leftIds.add(n.get("id").asInt()));
        assertEquals(Set.of(1, 2), leftIds);

        Set<Integer> rightIds = new HashSet<>();
        safe.get("right").forEach(n -> rightIds.add(n.get("id").asInt()));
        assertEquals(Set.of(10, 11), rightIds);
    }

    @Test
    void buildSafeContent_mcq_preservesMediaAssetIdOnly() throws Exception {
        String assetId = UUID.randomUUID().toString();
        String raw = """
                {
                  "options": [
                    {"id":"a","text":"A","correct":true,"media":{"assetId":"%s","cdnUrl":"https://cdn/x.png","width":100}},
                    {"id":"b","text":"B","correct":false}
                  ]
                }
                """.formatted(assetId);

        JsonNode safe = builder.buildSafeContent(QuestionType.MCQ_SINGLE, raw);

        JsonNode option = safe.get("options").get(0);
        assertTrue(option.has("media"));
        assertEquals(1, option.get("media").size());
        assertEquals(assetId, option.get("media").get("assetId").asText());
    }

    @Test
    void buildSafeContent_mcq_invalidMediaAssetId_isSkipped() {
        String raw = """
                {
                  "options": [
                    {"id":"a","text":"A","correct":true,"media":{"assetId":"not-a-uuid"}},
                    {"id":"b","text":"B","correct":false}
                  ]
                }
                """;

        JsonNode safe = builder.buildSafeContent(QuestionType.MCQ_SINGLE, raw);

        JsonNode option = safe.get("options").get(0);
        assertFalse(option.has("media"));
    }

    @Test
    void buildSafeContent_fillGapWithoutOptions_omitsAnswersAndKeepsTypingMode() throws Exception {
        String raw = """
                {
                  "text": "The capital of {1} is {2}.",
                  "gaps": [
                    {"id":1,"answer":"France"},
                    {"id":2,"answer":"Paris"}
                  ]
                }
                """;

        JsonNode safe = builder.buildSafeContent(QuestionType.FILL_GAP, raw);

        assertEquals("The capital of {1} is {2}.", safe.get("text").asText());
        assertTrue(safe.has("gaps"));
        assertFalse(safe.has("options"));
        for (JsonNode gap : safe.get("gaps")) {
            assertTrue(gap.has("id"));
            assertFalse(gap.has("answer"));
        }
    }

    @Test
    void buildSafeContent_fillGapWithOptions_includesOptionsWithoutAnswers() throws Exception {
        String raw = """
                {
                  "text": "Cellular respiration occurs in the {1} and produces {2}.",
                  "gaps": [
                    {"id":1,"answer":"mitochondria"},
                    {"id":2,"answer":"ATP"}
                  ],
                  "options": ["mitochondria","ATP","chloroplast","NADH","ribosome","glucose","nucleus","oxygen"]
                }
                """;

        JsonNode safe = builder.buildSafeContent(QuestionType.FILL_GAP, raw, true);

        assertTrue(safe.has("options"));
        assertEquals(8, safe.get("options").size());
        assertFalse(safe.get("gaps").get(0).has("answer"));
        assertFalse(safe.get("gaps").get(1).has("answer"));

        List<String> options = new ArrayList<>();
        safe.get("options").forEach(option -> options.add(option.asText()));
        assertTrue(options.contains("mitochondria"));
        assertTrue(options.contains("ATP"));
        assertTrue(options.contains("chloroplast"));
    }

    @Test
    void buildSafeContent_fillGapWithOptions_deterministicKeepsOrder() throws Exception {
        String raw = """
                {
                  "text": "The capital of {1} is {2}.",
                  "gaps": [
                    {"id":1,"answer":"France"},
                    {"id":2,"answer":"Paris"}
                  ],
                  "options": ["France","Paris","Germany","Berlin","London","Madrid","Rome","Italy"]
                }
                """;

        JsonNode safe1 = builder.buildSafeContent(QuestionType.FILL_GAP, raw, true);
        JsonNode safe2 = builder.buildSafeContent(QuestionType.FILL_GAP, raw, true);

        assertEquals(safe1.get("options"), safe2.get("options"));
        assertEquals("France", safe1.get("options").get(0).asText());
        assertEquals("Paris", safe1.get("options").get(1).asText());
    }

    @Test
    void buildSafeContent_fillGapWithOptions_nonDeterministicShuffles() throws Exception {
        String raw = """
                {
                  "text": "The capital of {1} is {2}.",
                  "gaps": [
                    {"id":1,"answer":"France"},
                    {"id":2,"answer":"Paris"}
                  ],
                  "options": ["A","B","C","D","E","F","G","H"]
                }
                """;

        JsonNode baseline = builder.buildSafeContent(QuestionType.FILL_GAP, raw, true);
        boolean foundDifferentOrder = false;

        for (int i = 0; i < 20; i++) {
            JsonNode shuffled = builder.buildSafeContent(QuestionType.FILL_GAP, raw, false);
            if (!baseline.get("options").equals(shuffled.get("options"))) {
                foundDifferentOrder = true;
                break;
            }
        }

        assertTrue(foundDifferentOrder);
    }
}
