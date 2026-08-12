package uk.gegc.quizmaker.features.document.infra.isolation;

import java.time.Duration;
import java.util.function.BooleanSupplier;

final class ParentProcessMonitor {

    private static final Duration CHECK_INTERVAL = Duration.ofMillis(250);

    private ParentProcessMonitor() {
    }

    static Thread start(long parentProcessId, Runnable parentGoneAction) {
        return start(
                () -> ProcessHandle.of(parentProcessId).map(ProcessHandle::isAlive).orElse(false),
                parentGoneAction,
                CHECK_INTERVAL
        );
    }

    static Thread start(BooleanSupplier parentAlive, Runnable parentGoneAction, Duration interval) {
        Thread monitor = new Thread(() -> {
            while (parentAlive.getAsBoolean()) {
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            parentGoneAction.run();
        }, "document-parser-parent-monitor");
        monitor.setDaemon(true);
        monitor.start();
        return monitor;
    }
}
