package uk.gegc.quizmaker.features.ai.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Provider attempt budget")
class ProviderAttemptBudgetTest {

    @Test
    @DisplayName("Rejects a non-positive provider dispatch limit")
    void rejectsNonPositiveLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderAttemptBudget(0))
                .withMessage("Provider attempt budget must be greater than zero");
    }

    @Test
    @DisplayName("Tracks acquired permits without allowing an overrun")
    void tracksAcquiredPermitsWithoutOverrun() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(2);

        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();

        assertThat(budget.maxAttempts()).isEqualTo(2);
        assertThat(budget.consumedAttempts()).isEqualTo(2);
        assertThat(budget.remainingAttempts()).isZero();
        assertThat(budget.isExhausted()).isTrue();
    }

    @Test
    @DisplayName("Concurrent acquisition never exceeds the configured provider dispatch limit")
    void concurrentAcquisitionNeverExceedsLimit() throws Exception {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(7);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> acquisitions = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                acquisitions.add(executor.submit(() -> {
                    start.await();
                    return budget.tryAcquire();
                }));
            }

            start.countDown();
            long acquired = 0;
            for (Future<Boolean> acquisition : acquisitions) {
                if (acquisition.get()) {
                    acquired++;
                }
            }

            assertThat(acquired).isEqualTo(7);
            assertThat(budget.consumedAttempts()).isEqualTo(7);
            assertThat(budget.remainingAttempts()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }
}
