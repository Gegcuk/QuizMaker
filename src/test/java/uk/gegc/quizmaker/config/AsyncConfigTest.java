package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Execution(ExecutionMode.SAME_THREAD)
class AsyncConfigTest {

    @Autowired
    private AsyncConfig asyncConfig;

    @Autowired
    private Executor aiTaskExecutor;

    @Autowired
    private Executor generalTaskExecutor;

    @Test
    void shouldCreateAiTaskExecutor() {
        assertNotNull(aiTaskExecutor);
        assertTrue(aiTaskExecutor instanceof ThreadPoolTaskExecutor);
        
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) aiTaskExecutor;
        assertEquals(8, executor.getCorePoolSize()); // Test profile value
        assertEquals(8, executor.getMaxPoolSize()); // Test profile value
        assertEquals(10, executor.getQueueCapacity()); // Test profile value
        assertEquals(30, executor.getKeepAliveSeconds()); // Test profile value
    }

    @Test
    void shouldCreateGeneralTaskExecutor() {
        assertNotNull(generalTaskExecutor);
        assertTrue(generalTaskExecutor instanceof ThreadPoolTaskExecutor);
        
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) generalTaskExecutor;
        assertEquals(4, executor.getCorePoolSize()); // Test profile value
        assertEquals(4, executor.getMaxPoolSize()); // Test profile value
        assertEquals(10, executor.getQueueCapacity()); // Test profile value
        assertEquals(30, executor.getKeepAliveSeconds()); // Test profile value
    }

    @Test
    void shouldReturnAiExecutorAsDefault() {
        Executor defaultExecutor = asyncConfig.getAsyncExecutor();
        assertNotNull(defaultExecutor);
        assertSame(aiTaskExecutor, defaultExecutor);
    }

    @Test
    void shouldHaveExceptionHandler() {
        assertNotNull(asyncConfig.getAsyncUncaughtExceptionHandler());
    }
} 