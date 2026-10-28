package uk.gegc.quizmaker.features.question.domain.model.contract;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Closed text-only presentation vocabulary. No raw JSON, answer, source key or grading fields. */
public sealed interface ParticipantContent {
    record Option(UUID id, String text) {
        public Option {
            require(id != null && text != null && text.codePointCount(0, text.length()) <= 10_000);
        }
    }

    record Gap(int id) { }

    record Choices(List<Option> options) implements ParticipantContent {
        public Choices { options = copyOptions(options); }
    }

    record TrueFalse() implements ParticipantContent { }

    record FillGap(String text, List<Gap> gaps, List<Option> options) implements ParticipantContent {
        public FillGap {
            require(text != null && text.codePointCount(0, text.length()) <= 10_000 && gaps != null);
            var ids = new HashSet<Integer>();
            for (var gap : gaps) require(gap != null && ids.add(gap.id()));
            gaps = List.copyOf(gaps);
            options = copyOptions(options);
        }
    }

    record Ordering(List<Option> items) implements ParticipantContent {
        public Ordering { items = copyOptions(items); }
    }

    private static List<Option> copyOptions(List<Option> options) {
        require(options != null);
        var ids = new HashSet<UUID>();
        for (var option : options) require(option != null && ids.add(option.id()));
        return List.copyOf(options);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid participant content");
    }
}
