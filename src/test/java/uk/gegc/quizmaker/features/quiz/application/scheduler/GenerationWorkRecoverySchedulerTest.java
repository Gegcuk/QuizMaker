package uk.gegc.quizmaker.features.quiz.application.scheduler;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationWorkRecoverySchedulerTest {
    @Test void dueWorkIsRetriedAfterDatabaseOrDispatcherFailure() {
        var service = mock(GenerationWorkService.class);
        var dispatcher = mock(GenerationWorkDispatcher.class);
        when(service.findDue()).thenThrow(new IllegalStateException("local DB unavailable"))
                .thenReturn(List.of(UUID.randomUUID()));
        when(dispatcher.dispatch()).thenThrow(new IllegalStateException("executor stopping"))
                .thenReturn(GenerationWorkDispatcher.Admission.DISPATCHED);
        var scheduler = new GenerationWorkRecoveryScheduler(service, dispatcher);
        assertThatCode(scheduler::recover).doesNotThrowAnyException();
        assertThatCode(scheduler::recover).doesNotThrowAnyException();
        scheduler.recover();
        verify(dispatcher, times(2)).dispatch();
    }

    @Test void emptyQueueDoesNotOccupyExecutionSlots() {
        var service = mock(GenerationWorkService.class);
        var dispatcher = mock(GenerationWorkDispatcher.class);
        when(service.findDue()).thenReturn(List.of());
        new GenerationWorkRecoveryScheduler(service, dispatcher).recover();
        verifyNoInteractions(dispatcher);
    }
}
