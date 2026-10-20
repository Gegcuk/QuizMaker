package uk.gegc.quizmaker.features.ai.application;

import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Keeps validated candidates from an interrupted chunk without combining repeated attempts. */
public final class ChunkGenerationBuffer {
    private static final ThreadLocal<ChunkGenerationBuffer> SUBMISSION = new ThreadLocal<>();
    private final Map<QuestionType, List<Question>> candidates = new EnumMap<>(QuestionType.class);
    private boolean frozen;
    private List<Question> completed;

    public static ChunkGenerationBuffer currentSubmission() {
        return SUBMISSION.get();
    }

    public <T> T submit(Supplier<T> action) {
        ChunkGenerationBuffer previous = SUBMISSION.get();
        SUBMISSION.set(this);
        try {
            return action.get();
        } finally {
            if (previous == null) SUBMISSION.remove(); else SUBMISSION.set(previous);
        }
    }

    public synchronized void record(QuestionType type, List<Question> questions) {
        if (frozen || ProviderDeadline.isExpired()) return;
        if (questions.size() > candidates.getOrDefault(type, List.of()).size()) {
            candidates.put(type, List.copyOf(questions));
        }
    }

    public synchronized void complete(List<Question> questions) {
        if (!frozen && !ProviderDeadline.isExpired()) completed = List.copyOf(questions);
    }

    public synchronized List<Question> freeze() {
        frozen = true;
        if (completed != null) return new ArrayList<>(completed);
        List<Question> result = new ArrayList<>();
        candidates.values().forEach(result::addAll);
        return result;
    }
}
