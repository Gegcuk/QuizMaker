package uk.gegc.quizmaker.features.quiz.revision;

import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class RevisionFixtures {
    static final Instant CREATED = Instant.parse("2026-10-21T12:00:00.123456Z");
    static final UUID OWNER = id("owner");

    static UUID id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    static QuestionRevision question(String key) {
        return new QuestionRevision(id("revision-" + key), id("question-" + key), 1,
                QuestionType.MCQ_SINGLE, Difficulty.EASY, "What is the capital of France?",
                "{\"options\":[{\"id\":\"a\",\"text\":\"Paris\",\"correct\":true},{\"id\":\"b\",\"text\":\"London\",\"correct\":false}]}",
                "Think of France", "Paris is the capital of France.", null, null, CREATED,
                List.of(new QuestionRevision.Option(id(key + "-a"), "a", 0),
                        new QuestionRevision.Option(id(key + "-b"), "b", 1)));
    }

    static QuizRevision quiz(String key, QuestionRevision... questions) {
        var items = new java.util.ArrayList<QuizRevision.Item>();
        for (int position = 0; position < questions.length; position++) {
            items.add(new QuizRevision.Item(id(key + "-item-" + position), position, questions[position]));
        }
        return new QuizRevision(id(key), id("authoring-quiz"), OWNER, 1, "French geography", "Practice capitals", CREATED, items);
    }
}
