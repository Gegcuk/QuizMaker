package uk.gegc.quizmaker.features.attempt.application;

import java.util.Optional;

/** Routes participant HTTP commands to an existing scenario attempt. Admission remains internal. */
public interface BranchParticipantService {
    Optional<ResponseAcceptanceService.Result> accept(ResponseAcceptanceService.Command command);
    Optional<AttemptSubmissionService.Result> submit(AttemptSubmissionService.Command command);
}
