package uk.gegc.quizmaker.features.attempt.form;

import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppend.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Metadata contract fixtures only; constructing State/Receipt does not simulate durable commit. */
final class ProgressiveFormAppendFixtures {
    static final Reference REFERENCE = new Reference(id("form"), id("revision"), 1);
    static final RevisionScope REVISION = new RevisionScope(1, REFERENCE.quizRevisionId(), 1,
            IntStream.range(0, 8).mapToObj(i -> id("source-" + i)).collect(Collectors.toSet()));

    private ProgressiveFormAppendFixtures() { }

    static UUID id(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }

    static Item item(int number, int position) { return new Item(id("item-" + number), id("source-" + number), position); }

    static Decision decision(String name) { return new Decision(1, DecisionKind.ADAPTIVE, id(name), id(name + "-evidence")); }

    static State state(long version, Item... items) {
        return new State(1, FormKind.PROGRESSIVE, REFERENCE, version, List.of(items));
    }

    static Request request(String decision, long expected, Item... items) {
        return new Request(1, REFERENCE, expected, decision(decision), List.of(items));
    }

    static RevisionScope scope(int schemaVersion) {
        return new RevisionScope(1, REFERENCE.quizRevisionId(), schemaVersion, Set.copyOf(REVISION.sourceItemIds()));
    }
}
