package uk.gegc.quizmaker.features.ai.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Explicit execution scope, propagated to provider workers without inheritable thread locals. */
public final class ProviderDeadline {
    private static final ThreadLocal<ProviderDeadline> CURRENT = new ThreadLocal<>();
    private final Instant deadline;
    private final Clock clock;
    private final Runnable onExpiry;
    private final java.util.concurrent.atomic.AtomicBoolean observed = new java.util.concurrent.atomic.AtomicBoolean();

    public ProviderDeadline(Instant deadline, Clock clock) {
        this(deadline, clock, () -> { });
    }

    public ProviderDeadline(Instant deadline, Clock clock, Runnable onExpiry) {
        this.onExpiry = Objects.requireNonNull(onExpiry);
        this.deadline = deadline;
        this.clock = Objects.requireNonNull(clock);
    }

    public static <T> T within(ProviderDeadline deadline, Supplier<T> action) {
        ProviderDeadline previous = CURRENT.get();
        CURRENT.set(deadline);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static <T> Supplier<T> propagate(Supplier<T> action) {
        ProviderDeadline captured = CURRENT.get();
        return () -> within(captured, action);
    }

    public static boolean isExpired() {
        ProviderDeadline scope = CURRENT.get();
        boolean expired = scope != null && scope.deadline != null && !scope.clock.instant().isBefore(scope.deadline);
        if (expired && scope.observed.compareAndSet(false, true)) scope.onExpiry.run();
        return expired;
    }

    public static void check() {
        if (isExpired()) throw new GenerationDeadlineExceededException();
    }

    public static boolean isBounded() {
        ProviderDeadline scope = CURRENT.get();
        return scope != null && scope.deadline != null;
    }

    public static long limitMillis(long milliseconds) {
        check();
        ProviderDeadline scope = CURRENT.get();
        if (scope == null || scope.deadline == null) return milliseconds;
        return Math.min(milliseconds, Math.max(1, Duration.between(scope.clock.instant(), scope.deadline).toMillis()));
    }
}
