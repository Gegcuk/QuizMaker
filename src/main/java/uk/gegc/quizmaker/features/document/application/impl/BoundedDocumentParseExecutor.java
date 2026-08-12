package uk.gegc.quizmaker.features.document.application.impl;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentParseExecutor;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorker;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerException;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerFactory;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerMetrics;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingCapacityExceededException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies global/per-owner admission before running each untrusted converter in
 * a killable process. Capacity is returned only after that process has exited.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BoundedDocumentParseExecutor implements DocumentParseExecutor {

    private static final Duration REAPER_POLL_INTERVAL = Duration.ofSeconds(1);

    private final DocumentProcessingLimits limits;
    private final DocumentParserWorkerFactory workerFactory;
    private final DocumentParserWorkerMetrics metrics;

    private final Object lifecycleMonitor = new Object();
    private final ConcurrentMap<String, UserParsePermit> userPermits = new ConcurrentHashMap<>();
    private final Set<WorkerRegistration> activeWorkers = ConcurrentHashMap.newKeySet();

    private Semaphore permits;
    private volatile boolean accepting;

    @PostConstruct
    void initialize() {
        permits = new Semaphore(limits.getMaxConcurrentParses(), true);
        accepting = true;
    }

    @Override
    public ConvertedDocument execute(String ownerKey, DocumentParseRequest request) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey);
        Objects.requireNonNull(request, "Document parse request is required");
        boolean terminationAttempted = false;

        if (!accepting || !permits.tryAcquire()) {
            record(DocumentParserWorkerMetrics.Outcome.CAPACITY_REJECTED);
            throw new DocumentProcessingCapacityExceededException();
        }

        UserParsePermit userPermit = retainUserPermit(normalizedOwnerKey);
        if (!userPermit.permits().tryAcquire()) {
            releaseUserPermit(normalizedOwnerKey, userPermit);
            permits.release();
            record(DocumentParserWorkerMetrics.Outcome.CAPACITY_REJECTED);
            throw new DocumentProcessingCapacityExceededException();
        }

        CapacityLease capacityLease = new CapacityLease(normalizedOwnerKey, userPermit);
        WorkerRegistration registration = null;
        try {
            synchronized (lifecycleMonitor) {
                if (!accepting) {
                    record(DocumentParserWorkerMetrics.Outcome.CAPACITY_REJECTED);
                    throw new DocumentProcessingCapacityExceededException();
                }
                DocumentParserWorker worker;
                try {
                    worker = workerFactory.start(request);
                } catch (RuntimeException spawnFailure) {
                    record(DocumentParserWorkerMetrics.Outcome.SPAWN_FAILED);
                    throw spawnFailure;
                }
                registration = new WorkerRegistration(worker, capacityLease);
                activeWorkers.add(registration);
                workerStarted();
            }

            if (!registration.worker().await(limits.getParseTimeout())) {
                record(DocumentParserWorkerMetrics.Outcome.TIMED_OUT);
                terminationAttempted = true;
                if (!terminateAndConfirm(registration.worker())) {
                    throw new DocumentProcessingException("Document parser could not be terminated safely");
                }
                throw new DocumentResourceLimitException(
                        "Document processing exceeded the configured time limit");
            }
            ConvertedDocument result = registration.worker().readResult();
            record(DocumentParserWorkerMetrics.Outcome.SUCCEEDED);
            return result;
        } catch (InterruptedException interrupted) {
            record(DocumentParserWorkerMetrics.Outcome.INTERRUPTED);
            if (registration != null) {
                terminateAndConfirmUninterruptibly(registration.worker());
            }
            Thread.currentThread().interrupt();
            throw new DocumentProcessingException("Document processing was interrupted", interrupted);
        } catch (RuntimeException failure) {
            if (registration != null) {
                recordFailure(failure);
            }
            if (registration != null && registration.worker().isAlive() && !terminationAttempted) {
                terminateAndConfirmUninterruptibly(registration.worker());
            }
            throw failure;
        } finally {
            if (registration == null) {
                capacityLease.release();
            } else if (!completeIfTerminated(registration)) {
                startExitReaper(registration);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        List<WorkerRegistration> workers;
        synchronized (lifecycleMonitor) {
            accepting = false;
            workers = new ArrayList<>(activeWorkers);
            workers.forEach(worker -> worker.worker().requestTermination());
        }

        long shutdownDeadline = deadlineAfter(limits.getParserShutdownTimeout());
        long gracefulDeadline = Math.min(
                shutdownDeadline,
                deadlineAfter(limits.getParserTerminationGrace())
        );
        waitForWorkers(workers, gracefulDeadline);
        workers.stream()
                .filter(worker -> worker.worker().isAlive())
                .forEach(worker -> {
                    worker.worker().forceTermination();
                    record(DocumentParserWorkerMetrics.Outcome.FORCED_KILL);
                });
        waitForWorkers(workers, shutdownDeadline);
        workers.forEach(this::completeIfTerminated);

        long unreclaimed = workers.stream().filter(worker -> worker.worker().isAlive()).count();
        if (unreclaimed > 0) {
            workers.stream()
                    .filter(worker -> worker.worker().isAlive())
                    .forEach(worker -> record(DocumentParserWorkerMetrics.Outcome.KILL_FAILED));
            log.warn("Could not confirm termination of {} document parser worker(s) during shutdown", unreclaimed);
        }
    }

    private boolean terminateAndConfirm(DocumentParserWorker worker) throws InterruptedException {
        worker.requestTermination();
        if (worker.await(limits.getParserTerminationGrace())) {
            return true;
        }
        worker.forceTermination();
        record(DocumentParserWorkerMetrics.Outcome.FORCED_KILL);
        boolean terminated = worker.await(limits.getParserForceKillTimeout());
        if (!terminated) {
            record(DocumentParserWorkerMetrics.Outcome.KILL_FAILED);
        }
        return terminated;
    }

    private boolean terminateAndConfirmUninterruptibly(DocumentParserWorker worker) {
        boolean interrupted = false;
        try {
            worker.requestTermination();
            AwaitResult graceful = awaitUninterruptibly(worker, limits.getParserTerminationGrace());
            interrupted = graceful.interrupted();
            if (graceful.exited()) {
                return true;
            }
            worker.forceTermination();
            record(DocumentParserWorkerMetrics.Outcome.FORCED_KILL);
            AwaitResult forced = awaitUninterruptibly(worker, limits.getParserForceKillTimeout());
            interrupted = interrupted || forced.interrupted();
            if (!forced.exited()) {
                record(DocumentParserWorkerMetrics.Outcome.KILL_FAILED);
            }
            return forced.exited();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private AwaitResult awaitUninterruptibly(DocumentParserWorker worker, Duration timeout) {
        long deadline = deadlineAfter(timeout);
        boolean interrupted = false;
        while (worker.isAlive()) {
            Duration remaining = remaining(deadline);
            if (remaining.isZero()) {
                return new AwaitResult(false, interrupted);
            }
            try {
                return new AwaitResult(worker.await(remaining), interrupted);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return new AwaitResult(true, interrupted);
    }

    private void startExitReaper(WorkerRegistration registration) {
        if (!registration.reaperStarted().compareAndSet(false, true)) {
            return;
        }
        Thread reaper = new Thread(() -> {
            while (registration.worker().isAlive() && accepting) {
                try {
                    registration.worker().await(REAPER_POLL_INTERVAL);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            completeIfTerminated(registration);
        }, "document-parser-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    private boolean completeIfTerminated(WorkerRegistration registration) {
        if (registration.worker().isAlive()) {
            return false;
        }
        if (registration.completed().compareAndSet(false, true)) {
            activeWorkers.remove(registration);
            try {
                registration.worker().close();
            } finally {
                workerStopped();
                registration.capacityLease().release();
            }
        }
        return true;
    }

    private void waitForWorkers(List<WorkerRegistration> workers, long deadline) {
        for (WorkerRegistration registration : workers) {
            if (!registration.worker().isAlive()) {
                continue;
            }
            Duration remaining = remaining(deadline);
            if (remaining.isZero()) {
                return;
            }
            try {
                registration.worker().await(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private long deadlineAfter(Duration timeout) {
        long timeoutNanos = timeout.toNanos();
        long now = System.nanoTime();
        return timeoutNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private Duration remaining(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    private String normalizeOwnerKey(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("Document parse owner is required");
        }
        return ownerKey;
    }

    private void recordFailure(RuntimeException failure) {
        if (failure instanceof DocumentProcessingCapacityExceededException
                || failure instanceof DocumentResourceLimitException) {
            return;
        }
        if (failure instanceof DocumentParserWorkerException workerFailure) {
            DocumentParserWorkerMetrics.Outcome outcome = switch (workerFailure.getReason()) {
                case PROCESS_CRASH -> DocumentParserWorkerMetrics.Outcome.PROCESS_CRASHED;
                case INVALID_OUTPUT -> DocumentParserWorkerMetrics.Outcome.INVALID_OUTPUT;
                case INCOMPATIBLE_PROTOCOL -> DocumentParserWorkerMetrics.Outcome.INCOMPATIBLE_PROTOCOL;
            };
            record(outcome);
            return;
        }
        record(DocumentParserWorkerMetrics.Outcome.PROCESSING_FAILED);
    }

    private void record(DocumentParserWorkerMetrics.Outcome outcome) {
        try {
            metrics.record(outcome);
        } catch (RuntimeException telemetryFailure) {
            log.warn("Could not record a document parser worker metric");
        }
    }

    private void workerStarted() {
        try {
            metrics.workerStarted();
        } catch (RuntimeException telemetryFailure) {
            log.warn("Could not update the active document parser worker metric");
        }
    }

    private void workerStopped() {
        try {
            metrics.workerStopped();
        } catch (RuntimeException telemetryFailure) {
            log.warn("Could not update the active document parser worker metric");
        }
    }

    private UserParsePermit retainUserPermit(String ownerKey) {
        return userPermits.compute(ownerKey, (key, current) -> {
            UserParsePermit permit = current == null
                    ? new UserParsePermit(new Semaphore(limits.getMaxConcurrentParsesPerUser(), true), 0)
                    : current;
            return new UserParsePermit(permit.permits(), permit.activeOperations() + 1);
        });
    }

    private void releaseUserPermit(String ownerKey, UserParsePermit permit) {
        userPermits.computeIfPresent(ownerKey, (key, current) -> {
            if (current.permits() != permit.permits()) {
                return current;
            }
            int activeOperations = current.activeOperations() - 1;
            return activeOperations == 0 ? null : new UserParsePermit(current.permits(), activeOperations);
        });
    }

    private record UserParsePermit(Semaphore permits, int activeOperations) {
    }

    private final class CapacityLease {

        private final String ownerKey;
        private final UserParsePermit userPermit;
        private final AtomicBoolean released = new AtomicBoolean();

        private CapacityLease(String ownerKey, UserParsePermit userPermit) {
            this.ownerKey = ownerKey;
            this.userPermit = userPermit;
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                userPermit.permits().release();
                releaseUserPermit(ownerKey, userPermit);
                permits.release();
            }
        }
    }

    private record WorkerRegistration(
            DocumentParserWorker worker,
            CapacityLease capacityLease,
            AtomicBoolean completed,
            AtomicBoolean reaperStarted
    ) {

        private WorkerRegistration(DocumentParserWorker worker, CapacityLease capacityLease) {
            this(
                    Objects.requireNonNull(worker, "Document parser worker is required"),
                    capacityLease,
                    new AtomicBoolean(),
                    new AtomicBoolean()
            );
        }
    }

    private record AwaitResult(boolean exited, boolean interrupted) {
    }
}
