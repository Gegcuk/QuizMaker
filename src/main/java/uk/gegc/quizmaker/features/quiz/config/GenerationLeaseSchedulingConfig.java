package uk.gegc.quizmaker.features.quiz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class GenerationLeaseSchedulingConfig {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler generationLeaseScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // A saturated orchestration executor can run a dispatch inline. Heartbeats
        // must still have their own scheduler thread during that execution.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("generation-lease-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
