package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft;

@Schema(name = "DraftCommandAcknowledgement", description = "Original committed command metadata, including on replay. No response content or grading.")
public record DraftCommandAcknowledgement(UUID attemptId, UUID formItemId, long stateVersion,
                                           long draftVersion, AnswerDraft.Status status, boolean replayed) { }
