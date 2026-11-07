package uk.gegc.quizmaker.features.result.application;

import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

/** Trusted internal consumer of committed submission intents. No HTTP/participant disclosure.
 * Re-drive pending IDs after restart or an unknown outcome; identical processing converges.
 * Scheduling, automatic retry budgets, release and target activation belong to their owners.
 */
public interface AutomaticResultService {
    AutomaticResult finalizeSubmission(UUID submissionId);
    /** Bounded recovery discovery in committed creation/ID order; does not claim or mutate work. */
    List<UUID> pending(int limit);
}
