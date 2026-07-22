package uk.gegc.quizmaker.shared.testing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Serializes Spring test-context lifecycles that share the CI MySQL schemas.
 *
 * <p>Many integration tests intentionally use Hibernate {@code create-drop}. Without a
 * lifecycle lock, one context can drop tables while another is starting or executing. This
 * listener is registered globally for Spring tests only; plain unit tests retain JUnit's
 * parallel execution.</p>
 */
public final class SharedMySqlSchemaLockTestExecutionListener
        extends AbstractTestExecutionListener {

    private static final ReentrantLock SHARED_SCHEMA_LOCK = new ReentrantLock(true);
    private static final ConcurrentMap<Class<?>, ContextLifecycleLock> CONTEXT_LOCKS =
            new ConcurrentHashMap<>();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void prepareTestInstance(TestContext testContext) {
        ContextLifecycleLock lifecycleLock = CONTEXT_LOCKS.computeIfAbsent(
                testContext.getTestClass(), ignored -> new ContextLifecycleLock());

        if (lifecycleLock.claimOwnership()) {
            SHARED_SCHEMA_LOCK.lock();
            lifecycleLock.signalAcquired();
            return;
        }

        lifecycleLock.awaitAcquired();
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        if (CONTEXT_LOCKS.remove(testContext.getTestClass()) != null) {
            SHARED_SCHEMA_LOCK.unlock();
        }
    }

    private static final class ContextLifecycleLock {

        private final AtomicBoolean ownerClaimed = new AtomicBoolean();
        private final CountDownLatch acquired = new CountDownLatch(1);

        private boolean claimOwnership() {
            return ownerClaimed.compareAndSet(false, true);
        }

        private void signalAcquired() {
            acquired.countDown();
        }

        private void awaitAcquired() {
            try {
                acquired.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for the shared MySQL schema lock", exception);
            }
        }
    }
}
