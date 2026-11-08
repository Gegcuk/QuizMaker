package uk.gegc.quizmaker.features.attempt.domain.model.draft;

import java.util.UUID;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.DraftCommandException.*;

/** Immutable receipt metadata; contains no saved response. */
public record DraftCommandResult(UUID commandId, UUID attemptId, UUID formItemId, long draftVersion, AnswerDraft.Status status) {
    public DraftCommandResult {
        require(commandId != null && attemptId != null && formItemId != null && draftVersion > 0 && status != null, Reason.CORRUPT);
    }
    @Override public String toString() { return "DraftCommandResult[redacted]"; }
}
