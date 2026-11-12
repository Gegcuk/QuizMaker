package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Immutable structure only: item identities, never question content, answers or case stimulus. */
public record RevisionSection(UUID id, int schemaVersion, int authoredPosition, String title,
                              String instructions, List<UUID> itemIds) {
    public RevisionSection {
        RevisionValues.version(schemaVersion);
        RevisionValues.require(id != null && authoredPosition >= 0);
        title = RevisionValues.text(title, 100, true);
        instructions = RevisionValues.text(instructions, 1000, false);
        RevisionValues.require(itemIds != null && itemIds.stream().allMatch(idValue -> idValue != null)
                && new HashSet<>(itemIds).size() == itemIds.size());
        itemIds = List.copyOf(itemIds);
    }

    @Override public String toString() {
        return "RevisionSection[version=" + schemaVersion + ",itemCount=" + itemIds.size() + "]";
    }
}
