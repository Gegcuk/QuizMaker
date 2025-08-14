package uk.gegc.quizmaker.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration for asynchronous processing in the QuizMaker application.
 * 
 * This configuration provides:
 * - Custom thread pool for AI operations with appropriate sizing
 * - Separate thread pool for general async operations
 * - Proper exception handling for async operations
 * - Configurable timeouts and queue sizes
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Value("${async.ai.core-pool-size:4}")
    private int aiCorePoolSize;

    @Value("${async.ai.max-pool-size:8}")
    private int aiMaxPoolSize;

    @Value("${async.ai.queue-capacity:50}")
    private int aiQueueCapacity;

    @Value("${async.ai.keep-alive-seconds:60}")
    private int aiKeepAliveSeconds;

    @Value("${async.general.core-pool-size:2}")
    private int generalCorePoolSize;

    @Value("${async.general.max-pool-size:4}")
    private int generalMaxPoolSize;

    @Value("${async.general.queue-capacity:25}")
    private int generalQueueCapacity;

    @Value("${async.general.keep-alive-seconds:60}")
    private int generalKeepAliveSeconds;

    /**
     * Custom executor for AI operations (quiz generation, question generation)
     * Optimized for CPU-intensive AI API calls with appropriate thread pool sizing
     */
    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core pool size - number of threads to keep alive
        executor.setCorePoolSize(aiCorePoolSize);
        
        // Maximum pool size - maximum number of threads to create
        executor.setMaxPoolSize(aiMaxPoolSize);
        
        // Queue capacity - how many tasks can be queued before creating new threads
        executor.setQueueCapacity(aiQueueCapacity);
        
        // Keep alive time for threads beyond core pool size
        executor.setKeepAliveSeconds(aiKeepAliveSeconds);
        
        // Thread name prefix for easier debugging
        executor.setThreadNamePrefix("ai-");
        
        // Rejection policy - caller runs the task if queue is full
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        // Initialize the executor
        executor.initialize();
        
        log.info("AI Task Executor configured - Core: {}, Max: {}, Queue: {}, KeepAlive: {}s",
                aiCorePoolSize, aiMaxPoolSize, aiQueueCapacity, aiKeepAliveSeconds);
        
        return executor;
    }

    /**
     * General purpose executor for other async operations
     * Used for document processing, file operations, etc.
     */
    @Bean(name = "generalTaskExecutor")
    public Executor generalTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        executor.setCorePoolSize(generalCorePoolSize);
        executor.setMaxPoolSize(generalMaxPoolSize);
        executor.setQueueCapacity(generalQueueCapacity);
        executor.setKeepAliveSeconds(generalKeepAliveSeconds);
        executor.setThreadNamePrefix("general-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();
        
        log.info("General Task Executor configured - Core: {}, Max: {}, Queue: {}, KeepAlive: {}s",
                generalCorePoolSize, generalMaxPoolSize, generalQueueCapacity, generalKeepAliveSeconds);
        
        return executor;
    }

    /**
     * Default executor for @Async methods without specific executor name
     * Uses the AI executor as default since most async operations are AI-related
     */
    @Override
    public Executor getAsyncExecutor() {
        return aiTaskExecutor();
    }

    /**
     * Exception handler for async operations
     * Logs exceptions and provides proper error handling
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, java.lang.reflect.Method method, Object... params) {
                log.error("Uncaught exception in async method: {}.{}() with parameters: {}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        java.util.Arrays.toString(params), ex);
                
                // Call parent handler for default behavior
                super.handleUncaughtException(ex, method, params);
            }
        };
    }
} 