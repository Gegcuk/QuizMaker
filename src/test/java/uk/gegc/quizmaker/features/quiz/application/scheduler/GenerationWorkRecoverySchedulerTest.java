package uk.gegc.quizmaker.features.quiz.application.scheduler;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationWorkRecoverySchedulerTest {
    @Test void anExpiredRunningWorkerDoesNotBlockAnotherDispatchOnTheSameInstance() throws Exception {
        GenerationWorkService service = mock(GenerationWorkService.class);
        GenerationWorkExecutor worker = mock(GenerationWorkExecutor.class);
        UUID id = UUID.randomUUID();
        when(service.findDue()).thenReturn(List.of(id));
        List<Runnable> queue = new ArrayList<>();
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            running.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(worker).execute(id);
        var scheduler = new GenerationWorkRecoveryScheduler(service, worker, queue::add);
        scheduler.recover();
        Thread oldWorker = new Thread(queue.remove(0));
        oldWorker.start();
        try {
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            // The persisted due scan has now returned the expired running job.
            scheduler.recover();
            assertThat(queue).hasSize(1);
        } finally {
            release.countDown();
            oldWorker.join(5000);
        }
    }

    @Test void queuedIdsAreDeduplicatedUntilTheTaskFinishes() {
        GenerationWorkService service = mock(GenerationWorkService.class);
        GenerationWorkExecutor worker = mock(GenerationWorkExecutor.class);
        UUID id = UUID.randomUUID();
        when(service.findDue()).thenReturn(List.of(id));
        List<Runnable> queue = new ArrayList<>();
        var scheduler = new GenerationWorkRecoveryScheduler(service, worker, queue::add);
        scheduler.recover(); scheduler.recover();
        assertThat(queue).hasSize(1);
        queue.remove(0).run();
        verify(worker).execute(id);
        scheduler.recover();
        assertThat(queue).hasSize(1);
    }

    @Test void rejectedDispatchAndDatabaseOutageCanBeRetriedOnALaterScan() {
        GenerationWorkService service = mock(GenerationWorkService.class);
        GenerationWorkExecutor worker = mock(GenerationWorkExecutor.class);
        Executor executor = mock(Executor.class);
        UUID id = UUID.randomUUID();
        when(service.findDue()).thenThrow(new IllegalStateException("local DB unavailable")).thenReturn(List.of(id));
        doThrow(new RejectedExecutionException()).doAnswer(c -> { ((Runnable)c.getArgument(0)).run(); return null; })
                .when(executor).execute(any());
        var scheduler = new GenerationWorkRecoveryScheduler(service, worker, executor);
        assertThatCode(scheduler::recover).doesNotThrowAnyException();
        scheduler.recover(); scheduler.recover();
        verify(worker, times(1)).execute(id);
    }

    @Test void failedWorkerDoesNotLeaveTheLocalDispatchMarkerStuck() {
        GenerationWorkService service = mock(GenerationWorkService.class);
        GenerationWorkExecutor worker = mock(GenerationWorkExecutor.class);
        UUID id = UUID.randomUUID();
        when(service.findDue()).thenReturn(List.of(id));
        doThrow(new IllegalStateException("local DB unavailable")).doNothing().when(worker).execute(id);
        var scheduler = new GenerationWorkRecoveryScheduler(service, worker, Runnable::run);
        scheduler.recover(); scheduler.recover();
        verify(worker, times(2)).execute(id);
    }
}
