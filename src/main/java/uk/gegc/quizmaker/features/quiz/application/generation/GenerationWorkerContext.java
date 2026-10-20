package uk.gegc.quizmaker.features.quiz.application.generation;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicitly propagated execution authority; never inherited by pooled threads. */
public final class GenerationWorkerContext {
    private record Scope(GenerationWorkClaim claim, GenerationWorkService service) { }
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private GenerationWorkerContext() { }

    public static void run(GenerationWorkClaim claim, GenerationWorkService service, Runnable action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(Objects.requireNonNull(claim), Objects.requireNonNull(service)));
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static <T> Supplier<T> propagate(Supplier<T> action) {
        Scope captured = CURRENT.get();
        if (captured == null) return action;
        return () -> {
            Scope previous = CURRENT.get();
            CURRENT.set(captured);
            try {
                return action.get();
            } finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        };
    }

    public static <T> T write(UUID jobId, Supplier<T> action) {
        Scope scope = CURRENT.get();
        if (scope == null) return action.get();
        if (!scope.claim().jobId().equals(jobId)) throw new GenerationLeaseLostException();
        return scope.service().protect(scope.claim(), action);
    }

    public static void check(UUID jobId) {
        write(jobId, () -> null);
    }

    public static void runWrite(UUID jobId, Runnable action) {
        write(jobId, () -> {
            action.run();
            return null;
        });
    }

    public static void rethrowIfLost(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof GenerationLeaseLostException lost) throw lost;
        }
    }
}
