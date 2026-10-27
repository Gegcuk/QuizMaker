package uk.gegc.quizmaker.features.attempt.selection;

import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;

import java.util.List;
import java.util.UUID;

final class QuestionSelectionFixtures {
    static final UUID REVISION = new UUID(0, 900);
    static final UUID FIRST = new UUID(0, 30);
    static final UUID MIDDLE = new UUID(0, 20);
    static final UUID LAST = new UUID(0, 10);
    static final UUID FOREIGN = new UUID(0, 999);
    static final List<Item> ITEMS = List.of(new Item(LAST, Integer.MAX_VALUE), new Item(FIRST, 1), new Item(MIDDLE, 7));
    static final List<UUID> ORDERED = List.of(FIRST, MIDDLE, LAST);

    private QuestionSelectionFixtures() {}

    static Input input(Configuration configuration) { return input(configuration, List.of()); }

    static Input input(Configuration configuration, List<AtomicGroup> groups) {
        return new Input(1, REVISION, 1, configuration, ITEMS, groups);
    }

    static AtomicGroup group(UUID... ids) { return new AtomicGroup(1, List.of(ids)); }
}
