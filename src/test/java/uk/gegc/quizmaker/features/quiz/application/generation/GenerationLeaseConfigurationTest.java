package uk.gegc.quizmaker.features.quiz.application.generation;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import uk.gegc.quizmaker.features.quiz.config.GenerationLeaseSchedulingConfig;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import static org.assertj.core.api.Assertions.*;

class GenerationLeaseConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling { }

    @Test void heartbeatSchedulerDoesNotReplaceOrChangeTheDefaultScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Scheduling.class, GenerationLeaseSchedulingConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var normal = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
                    var leases = context.getBean("generationLeaseScheduler", ThreadPoolTaskScheduler.class);
                    assertThat(normal.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
                    assertThat(leases.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
                    assertThat(context.getBean(TaskScheduler.class)).isSameAs(normal);
                });
    }

    @Test void approvedDefaultsAreValidAndInvalidLeaseIntervalsAreRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var properties = new QuizJobProperties();
            assertThat(properties.getExecution().getLeaseSeconds()).isEqualTo(120);
            assertThat(properties.getExecution().getHeartbeatSeconds()).isEqualTo(30);
            assertThat(properties.getExecution().getScanSeconds()).isEqualTo(10);
            assertThat(factory.getValidator().validate(properties)).isEmpty();
            properties.getExecution().setLeaseSeconds(30);
            properties.getExecution().setScanSeconds(0);
            assertThat(factory.getValidator().validate(properties)).hasSize(2);
        }
    }
}
