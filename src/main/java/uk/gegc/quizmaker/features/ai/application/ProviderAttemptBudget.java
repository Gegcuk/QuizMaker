package uk.gegc.quizmaker.features.ai.application;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory provider-dispatch budget shared by one logical generation task.
 */
public final class ProviderAttemptBudget {

    private final int maxAttempts;
    private final AtomicInteger remainingAttempts;

    public ProviderAttemptBudget(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("Provider attempt budget must be greater than zero");
        }
        this.maxAttempts = maxAttempts;
        this.remainingAttempts = new AtomicInteger(maxAttempts);
    }

    /**
     * Atomically reserves one provider dispatch without allowing the count below zero.
     */
    public boolean tryAcquire() {
        return remainingAttempts.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0;
    }

    public boolean isExhausted() {
        return remainingAttempts.get() == 0;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int consumedAttempts() {
        return maxAttempts - remainingAttempts.get();
    }

    public int remainingAttempts() {
        return remainingAttempts.get();
    }
}
