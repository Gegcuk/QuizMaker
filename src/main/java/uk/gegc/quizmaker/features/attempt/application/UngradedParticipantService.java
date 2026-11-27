package uk.gegc.quizmaker.features.attempt.application;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.api.dto.UngradedParticipantResponse.*;

/** Dispatch only when the authenticated subject owns an explicitly ungraded binding.
 * Empty delegates to the ordinary graded operation. Failures never fall back. */
public interface UngradedParticipantService {
    Optional<View> read(UUID attemptId, boolean hasQueryParameters);
    Optional<Participation> accept(ResponseAcceptanceService.Command command);
    Optional<Completion> submit(AttemptSubmissionService.Command command);
}
