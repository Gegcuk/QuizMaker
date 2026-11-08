package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Reference;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Internal transactional port. AnswerDraftStore owns validation, failure translation and transaction scope. */
public interface AnswerDraftRepository {
    boolean lockSubject(UUID trustedUserId, UUID attemptId);
    Scope scope(Reference reference);
    Optional<AnswerDraft> read(Reference reference);
    boolean accepted(Reference reference);
    void insert(AnswerDraft draft);
    boolean compareAndSet(long expectedVersion, AnswerDraft draft);

    record Scope(QuestionRevision question, QuestionContractBinding binding) {
        @Override public String toString() { return "AnswerDraft.Scope[redacted]"; }
    }
}
