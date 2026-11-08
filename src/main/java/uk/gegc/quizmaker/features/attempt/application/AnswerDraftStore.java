package uk.gegc.quizmaker.features.attempt.application;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Reference;

/**
 * Internal persistence boundary for trusted server callers, not a draft command or authorization grant.
 * #542/#834 own principal/permission, current content access, policy, section and finalization guards.
 * Values use the exact saved response contract's normalized vocabulary, not display indexes.
 * Expected version 0 means never saved; deletion retains a versioned tombstone with no response value.
 * Methods join an existing transaction or create a short one; later outer rollback still rolls back drafts.
 */
public interface AnswerDraftStore {
    Optional<AnswerDraft> read(UUID trustedUserId, Reference reference);
    AnswerDraft save(UUID trustedUserId, Reference reference, long expectedVersion, String normalizedResponse);
    AnswerDraft delete(UUID trustedUserId, Reference reference, long expectedVersion);
}
