package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.application.BranchingAttemptService.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

public interface BranchingRepository {
    record Head(UUID currentNode, long formVersion, boolean terminalReady) { }
    record Saved(Command command, Result result, UUID node, UUID evidenceId) { }
    record Answer(UUID id, UUID question, ContractVersion contract, String normalized) {
        @Override public String toString() { return "BranchAnswer"; }
    }
    /** Nonlocking routing probe. The selected participant operation must still authorize. */
    boolean isOwnedPath(UUID attemptId, UUID userId);
    void bind(Admission admission);
    Head head(UUID attempt, UUID form, UUID revision, UUID policy);
    Optional<Saved> decision(UUID attempt, UUID form, UUID decision);
    Optional<Answer> answer(UUID attempt, UUID form, UUID item);
    void appendItem(FixedForm prefix, FormOptionOrder order, FixedForm.Item item, FormOptionOrder.Item options);
    void record(UUID form, UUID revision, Head before, Saved saved, List<UUID> edges, byte[] fingerprint, UUID trace);
}
