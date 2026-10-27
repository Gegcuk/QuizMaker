package uk.gegc.quizmaker.features.attempt.domain.model.lifecycle;

public sealed interface LifecycleRead permits LifecycleState, LifecycleRead.Legacy {
    enum Legacy implements LifecycleRead { NO_TARGET_STATE }
}
