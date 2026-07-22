package uk.gegc.quizmaker.shared.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

@Tag("db-serial")
class SharedMySqlSchemaLockTestExecutionListenerTest {

    private final SharedMySqlSchemaLockTestExecutionListener listener =
            new SharedMySqlSchemaLockTestExecutionListener();

    @Test
    void isRegisteredForEverySpringTestContext() throws IOException {
        Properties springFactories = new Properties();
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring.factories")) {
            assertThat(inputStream).isNotNull();
            springFactories.load(inputStream);
        }

        assertThat(springFactories.getProperty(TestExecutionListener.class.getName()))
                .contains(SharedMySqlSchemaLockTestExecutionListener.class.getName());
    }

    @Test
    void blocksAnotherSpringContextUntilTheCurrentLifecycleCompletes() throws Exception {
        TestContext firstContext = mock(TestContext.class);
        TestContext secondContext = mock(TestContext.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch secondContextAcquiredLock = new CountDownLatch(1);
        CountDownLatch releaseSecondContext = new CountDownLatch(1);

        boolean firstContextLockHeld = false;
        try {
            listener.beforeTestClass(firstContext);
            firstContextLockHeld = true;

            Future<?> secondLifecycle = executor.submit(() -> {
                listener.beforeTestClass(secondContext);
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
}
