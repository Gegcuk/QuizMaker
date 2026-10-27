package uk.gegc.quizmaker.features.attempt.domain.model.lifecycle;

import java.util.UUID;

/** Trusted engine binding, never inferred from absence of target data. */
public sealed interface LifecycleReference permits LifecycleReference.Target, LifecycleReference.Legacy {
    record Target(UUID attemptId) implements LifecycleReference {
        public Target {
            LifecycleStateException.require(attemptId != null, LifecycleStateException.Reason.INVALID_INPUT);
        }
    }

    enum Legacy implements LifecycleReference { NO_TARGET_STATE }
}
