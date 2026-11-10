package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTiming;

/** Internal timing evidence. Callers retain the verified access/start transaction through use. */
public interface AttemptTimingService {
    /** Lifecycle's injected Clock supplies the authoritative start; never sample a second start. */
    void capture(PolicySnapshot snapshot, LifecycleState started);
    /** Null only for a verified NONE snapshot. Never pass client-built access or snapshot facts. */
    AttemptTiming read(AttemptAccessService.Access access, PolicySnapshot snapshot);
}
