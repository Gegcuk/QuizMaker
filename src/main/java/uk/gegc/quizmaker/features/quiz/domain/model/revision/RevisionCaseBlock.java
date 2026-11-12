package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Author-side immutable reference, never a stimulus payload or a participant access grant. */
public record RevisionCaseBlock(UUID id, int schemaVersion, UUID sectionId, int authoredPosition,
                                UUID stimulusRevisionId, boolean atomic, List<UUID> itemIds) {
    public RevisionCaseBlock {
        RevisionValues.version(schemaVersion);
        RevisionValues.require(id != null && sectionId != null && stimulusRevisionId != null && authoredPosition >= 0);
        RevisionValues.require(itemIds != null && !itemIds.isEmpty() && itemIds.stream().allMatch(item -> item != null)
                && new HashSet<>(itemIds).size() == itemIds.size());
        itemIds = List.copyOf(itemIds);
    }

    @Override public String toString() {
        return "RevisionCaseBlock[version=" + schemaVersion + ",atomic=" + atomic + ",itemCount=" + itemIds.size() + "]";
    }
}
