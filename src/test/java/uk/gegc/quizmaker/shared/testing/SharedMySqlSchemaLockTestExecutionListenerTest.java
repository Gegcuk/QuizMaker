package uk.gegc.quizmaker.shared.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

@Tag("db-serial")
class SharedMySqlSchemaLockTestExecutionListenerTest {

    private final SharedMySqlSchemaLockTestExecutionListener listener =
            new SharedMySqlSchemaLockTestExecutionListener();

    @Test
    void isRegisteredForEverySpringTestContext() {
        List<TestExecutionListener> listeners = SpringFactoriesLoader.loadFactories(
                TestExecutionListener.class, getClass().getClassLoader());

        assertThat(listeners)
                .extracting(TestExecutionListener::getClass)
                .contains(SharedMySqlSchemaLockTestExecutionListener.class);
    }

    @Test
    void blocksAnotherSpringContextUntilTheCurrentLifecycleCompletes() throws Exception {
        TestContext firstContext = testContext(FirstContextTest.class);
        TestContext secondContext = testContext(SecondContextTest.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch secondContextAcquiredLock = new CountDownLatch(1);
        CountDownLatch releaseSecondContext = new CountDownLatch(1);

        boolean firstContextLockHeld = false;
        try {
            listener.prepareTestInstance(firstContext);
            listener.prepareTestInstance(firstContext);
            firstContextLockHeld = true;

            Future<?> secondLifecycle = executor.submit(() -> {
                listener.prepareTestInstance(secondContext);
                try {
                    secondContextAcquiredLock.countDown();
                    try {
                        releaseSecondContext.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting to release test context", exception);
                    }
                } finally {
                    listener.afterTestClass(secondContext);
                }
            });

            assertThat(secondContextAcquiredLock.await(150, TimeUnit.MILLISECONDS)).isFalse();

            listener.afterTestClass(firstContext);
            firstContextLockHeld = false;

            assertThat(secondContextAcquiredLock.await(2, TimeUnit.SECONDS)).isTrue();
            releaseSecondContext.countDown();
            secondLifecycle.get(2, TimeUnit.SECONDS);
        } finally {
            if (firstContextLockHeld) {
                listener.afterTestClass(firstContext);
            }
            releaseSecondContext.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TestContext testContext(Class<?> testClass) {
        TestContext testContext = mock(TestContext.class);
        doReturn(testClass).when(testContext).getTestClass();
        return testContext;
    }

    private static final class FirstContextTest {
    }

    private static final class SecondContextTest {
    }
}
