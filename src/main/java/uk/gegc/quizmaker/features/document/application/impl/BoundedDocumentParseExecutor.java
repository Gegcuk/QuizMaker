package uk.gegc.quizmaker.features.document.application.impl;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.document.application.DocumentParseExecutor;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingCapacityExceededException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Keeps untrusted document parsers off request threads and rejects overload
 * instead of queueing an unbounded number of memory-intensive parses.
 */
@Service
@RequiredArgsConstructor
public class BoundedDocumentParseExecutor implements DocumentParseExecutor {

    private final DocumentProcessingLimits limits;

    private Semaphore permits;
    private ExecutorService executor;
    private final ConcurrentMap<String, UserParsePermit> userPermits = new ConcurrentHashMap<>();

    @PostConstruct
    void initialize() {
        permits = new Semaphore(limits.getMaxConcurrentParses(), true);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "document-parse");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(limits.getMaxConcurrentParses(), factory);
    }

    @Override
    public <T> T execute(String ownerKey, Callable<T> parseOperation) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey);
        Objects.requireNonNull(parseOperation, "Document parse operation is required");

        if (!permits.tryAcquire()) {
            throw new DocumentProcessingCapacityExceededException();
        }

        UserParsePermit userPermit = retainUserPermit(normalizedOwnerKey);
        if (!userPermit.permits().tryAcquire()) {
            releaseUserPermit(normalizedOwnerKey, userPermit);
            permits.release();
            throw new DocumentProcessingCapacityExceededException();
        }

        Future<T> future;
        try {
            future = executor.submit(() -> {
                try {
                    return parseOperation.call();
                } finally {
                    userPermit.permits().release();
                    releaseUserPermit(normalizedOwnerKey, userPermit);
                    permits.release();
                }
            });
        } catch (RuntimeException e) {
            userPermit.permits().release();
            releaseUserPermit(normalizedOwnerKey, userPermit);
            permits.release();
            throw e;
        }
        try {
            return future.get(limits.getParseTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DocumentResourceLimitException("Document processing exceeded the configured time limit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new DocumentProcessingException("Document processing was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new DocumentProcessingException("Document processing failed", cause);
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private String normalizeOwnerKey(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("Document parse owner is required");
        }
        return ownerKey;
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
}
