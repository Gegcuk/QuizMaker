package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.*;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.time.Instant;
import java.util.*;

final class BaselineFamilyFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);

    static QuestionHandler legacy(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> new McqSingleHandler(); case MCQ_MULTI -> new McqMultiHandler();
            case TRUE_FALSE -> new TrueFalseHandler(); case FILL_GAP -> new FillGapHandler();
            case ORDERING -> new OrderingHandler(); default -> throw new AssertionError(type);
        };
    }

    static QuestionRevision question(QuestionType type, JsonNode content) {
        var options = new ArrayList<QuestionRevision.Option>();
        JsonNode source = content.path(type == QuestionType.ORDERING ? "items" : "options");
        if (source.isArray()) for (int i = 0; i < source.size(); i++) {
            String key = type == QuestionType.FILL_GAP ? "options/" + i : source.get(i).path("id").asText();
            options.add(new QuestionRevision.Option(UUID.randomUUID(), key, i));
        }
        return new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, type, Difficulty.EASY,
                "Frozen question", content.toString(), "private hint", "private explanation", null, null,
                Instant.parse("2026-11-05T00:00:00Z"), options);
    }

    static QuestionRevision options(QuestionRevision q, List<QuestionRevision.Option> options) {
        return new QuestionRevision(q.id(), q.questionId(), q.schemaVersion(), q.type(), q.difficulty(),
                q.questionText(), q.content(), q.hint(), q.explanation(), q.attachmentUrl(), q.attachmentAssetId(), q.createdAt(), options);
    }
}
