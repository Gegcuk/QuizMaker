package uk.gegc.quizmaker.features.attempt.application;

import java.util.List;
import java.util.UUID;

/** Durable scenario execution. A participant command cannot grant admission or choose a node, edge, score or form item. */
public interface BranchingAttemptService {
    /** Called inside the trusted launcher's writable RC transaction, after exact revision/source admission,
     * subject binding, policy/timing capture and lifecycle start. Creates the trace and materializes
     * the first question. The launcher's command receipt and this result must commit together.
     * The launcher owns exact launch replay; an existing attempt cannot be rebound. */
    Result bind(Admission admission);
    Result advance(Command command);
    Path resume(UUID attemptId);

    record Admission(UUID attemptId, UUID participantId, UUID quizId, UUID ownerId,
                     UUID revisionId, UUID policySnapshotId, UUID formId, UUID launchCommandId) {
        @Override public String toString() { return "ScenarioAdmission"; }
    }
    record Command(UUID attemptId, UUID decisionId, long expectedStateVersion, long expectedFormVersion) { }
    enum Outcome { APPENDED, WAITING_FOR_ANSWER, TERMINAL_READY }
    record Result(Outcome outcome, UUID formItemId, long formVersion) { }
    record Path(List<UUID> deliveredFormItems, long formVersion, boolean terminalReady) {
        public Path { deliveredFormItems = List.copyOf(deliveredFormItems); }
    }
}
