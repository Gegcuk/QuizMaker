package uk.gegc.quizmaker.features.document.infra.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document parser parent-process monitor")
class ParentProcessMonitorTest {

    @Test
    @DisplayName("Invokes the worker halt action when the owning parent disappears")
    void stopsWorkerWhenParentDisappears() throws InterruptedException {
        AtomicBoolean parentAlive = new AtomicBoolean(true);
        CountDownLatch parentGone = new CountDownLatch(1);
        Thread monitor = ParentProcessMonitor.start(
                parentAlive::get,
                parentGone::countDown,
                Duration.ofMillis(5)
        );

        parentAlive.set(false);

        assertThat(parentGone.await(1, TimeUnit.SECONDS)).isTrue();
        monitor.join(1_000);
        assertThat(monitor.isAlive()).isFalse();
    }

    @Test
    @DisplayName("Detects disappearance through the real operating-system process handle")
    void detectsRealProcessExit() throws Exception {
        Process observedProcess = new ProcessBuilder("/bin/sh", "-c", "while :; do sleep 1; done").start();
        CountDownLatch parentGone = new CountDownLatch(1);
        try {
            Thread monitor = ParentProcessMonitor.start(observedProcess.pid(), parentGone::countDown);

            observedProcess.destroyForcibly();
            assertThat(observedProcess.waitFor(2, TimeUnit.SECONDS)).isTrue();

            assertThat(parentGone.await(2, TimeUnit.SECONDS)).isTrue();
            monitor.join(1_000);
            assertThat(monitor.isAlive()).isFalse();
        } finally {
            if (observedProcess.isAlive()) {
                observedProcess.destroyForcibly();
            }
        }
    }
}
