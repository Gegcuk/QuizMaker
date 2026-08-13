package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.ai.application.AiProviderTaskScheduler;
import uk.gegc.quizmaker.features.ai.infra.execution.ExecutorAiProviderTaskScheduler;
import uk.gegc.quizmaker.shared.config.AsyncConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI provider executor configuration")
class AiProviderExecutorConfigTest {

    private ThreadPoolTaskExecutor executor;
    private ThreadPoolTaskExecutor orchestrationExecutor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        if (orchestrationExecutor != null) {
            orchestrationExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Builds a bounded aborting executor separate from orchestration")
    void buildsBoundedProviderExecutor() {
        AsyncConfig config = configured(2, 3, 4, 15, 7);

        Executor configuredExecutor = config.aiProviderTaskExecutor();
        executor = (ThreadPoolTaskExecutor) configuredExecutor;

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(3);
        assertThat(executor.getQueueCapacity()).isEqualTo(4);
        assertThat(executor.getKeepAliveSeconds()).isEqualTo(15);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("ai-provider-");
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        orchestrationExecutor = (ThreadPoolTaskExecutor) config.aiTaskExecutor();
        assertThat(configuredExecutor).isNotSameAs(orchestrationExecutor);
    }

    @Test
    @DisplayName("Fails startup when provider maximum is below its core size")
    void rejectsInvalidPoolBounds() {
        AsyncConfig config = configured(3, 2, 4, 15, 7);

        assertThatThrownBy(config::aiProviderTaskExecutor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-pool-size");
    }

    @Test
    @DisplayName("Fails startup when provider core size is not positive")
    void rejectsNonPositiveCorePoolSize() {
        AsyncConfig config = configured(0, 1, 4, 15, 7);

        assertThatThrownBy(config::aiProviderTaskExecutor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-pool-size");
    }

    @Test
    @DisplayName("Fails startup when provider queue capacity is negative")
    void rejectsNegativeQueueCapacity() {
        AsyncConfig config = configured(1, 1, -1, 15, 7);

        assertThatThrownBy(config::aiProviderTaskExecutor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue-capacity");
    }

    @Test
    @DisplayName("Fails startup when a provider executor timeout is negative")
    void rejectsNegativeTimeout() {
        AsyncConfig config = configured(1, 1, 4, 15, -1);

        assertThatThrownBy(config::aiProviderTaskExecutor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout values");
    }

    @Test
    @DisplayName("Wires the scheduler adapter to the named provider executor")
    void wiresSchedulerToNamedProviderExecutor() {
        new ApplicationContextRunner()
                .withUserConfiguration(AsyncConfig.class, ExecutorAiProviderTaskScheduler.class)
                .withPropertyValues(
                        "async.ai.provider.core-pool-size=1",
                        "async.ai.provider.max-pool-size=1",
                        "async.ai.provider.queue-capacity=1"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AiProviderTaskScheduler.class);
                    assertThat(context).hasBean("aiProviderTaskExecutor");
                    assertThat(context.getBean(AiProviderTaskScheduler.class))
                            .isInstanceOf(ExecutorAiProviderTaskScheduler.class);
                    assertThat(context.getBean("aiProviderTaskExecutor"))
                            .isNotSameAs(context.getBean("aiTaskExecutor"));
                });
    }

    private AsyncConfig configured(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int keepAliveSeconds,
            int awaitTerminationSeconds) {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "aiProviderCorePoolSize", corePoolSize);
        ReflectionTestUtils.setField(config, "aiProviderMaxPoolSize", maxPoolSize);
        ReflectionTestUtils.setField(config, "aiProviderQueueCapacity", queueCapacity);
        ReflectionTestUtils.setField(config, "aiProviderKeepAliveSeconds", keepAliveSeconds);
        ReflectionTestUtils.setField(config, "aiProviderAwaitTerminationSeconds", awaitTerminationSeconds);
        ReflectionTestUtils.setField(config, "aiCorePoolSize", 1);
        ReflectionTestUtils.setField(config, "aiMaxPoolSize", 1);
        ReflectionTestUtils.setField(config, "aiQueueCapacity", 1);
        ReflectionTestUtils.setField(config, "aiKeepAliveSeconds", 1);
        return config;
    }
}
