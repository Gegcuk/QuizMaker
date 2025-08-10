package uk.gegc.quizmaker.service.question;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.HashSet;
import java.util.Set;

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
}


