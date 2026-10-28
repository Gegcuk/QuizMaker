package uk.gegc.quizmaker.features.attempt.trace;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

final class TraceFixtures {
    static final java.time.Instant NOW = java.time.Instant.parse("2026-10-28T12:00:00.123456Z");
    static final UUID OWNER = id("owner");
    static final UUID OTHER_OWNER = id("other-owner");

    static UUID id(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static QuizRevision revision(String key, int count) {
        var items = IntStream.range(0, count).mapToObj(index -> {
            var q = new QuestionRevision(id(key + "-question-" + index), id(key + "-draft-" + index), 1,
                    QuestionType.TRUE_FALSE, Difficulty.EASY, "Is the statement true?", "{\"answer\":true}",
                    null, null, null, null, Instant.parse("2026-10-24T12:00:00Z"), List.of());
            return new QuizRevision.Item(id(key + "-source-" + index), index, q);
        }).toList();
        return new QuizRevision(id(key), id("mutable-quiz"), OWNER, 1, "Quiz", null,
                Instant.parse("2026-10-24T12:00:00Z"), items);
    }

    static FixedForm form(String key, QuizRevision revision, int count) {
        return new FixedForm(id(key), 1, revision.id(), IntStream.range(0, count)
                .mapToObj(index -> new FixedForm.Item(id(key + "-item-" + index), revision.items().get(index).id(), index * 2))
                .toList());
    }
}
