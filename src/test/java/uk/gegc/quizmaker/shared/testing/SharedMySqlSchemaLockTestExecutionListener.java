package uk.gegc.quizmaker.shared.testing;

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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        SHARED_SCHEMA_LOCK.lock();
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        SHARED_SCHEMA_LOCK.unlock();
    }
}
