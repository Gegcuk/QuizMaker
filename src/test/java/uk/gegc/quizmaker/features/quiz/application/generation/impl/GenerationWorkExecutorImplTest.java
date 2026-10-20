package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationWorkExecutorImplTest {
    @Test void aDelayedFormerClaimCannotReplaceTheCurrentHeartbeatOwner() throws Exception {
        GenerationWorkService service = mock(GenerationWorkService.class);
        AiQuizGenerationService ai = mock(AiQuizGenerationService.class);
        UUID id = UUID.randomUUID();
        var former = new GenerationWorkClaim(id, 1, null, "{}");
        var current = new GenerationWorkClaim(id, 2, null, "{}");
        CountDownLatch formerClaimed = new CountDownLatch(1), returnFormer = new CountDownLatch(1);
        CountDownLatch currentRunning = new CountDownLatch(1), bothRunning = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(service.claim(id)).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                formerClaimed.countDown();
                assertThat(returnFormer.await(5, TimeUnit.SECONDS)).isTrue();
                return Optional.of(former);
            }
            return Optional.of(current);
        });
        doAnswer(call -> {
            currentRunning.countDown();
            bothRunning.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(ai).generateQuizFromDocumentAsync(eq(id), any());
        var worker = new GenerationWorkExecutorImpl(service, ai, new ObjectMapper());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> old = pool.submit(() -> worker.execute(id));
            assertThat(formerClaimed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> replacement = pool.submit(() -> worker.execute(id));
            assertThat(currentRunning.await(5, TimeUnit.SECONDS)).isTrue();
            returnFormer.countDown();
            assertThat(bothRunning.await(5, TimeUnit.SECONDS)).isTrue();
            worker.heartbeat();
            verify(service).heartbeat(current);
            verify(service, never()).heartbeat(former);
            finish.countDown();
            old.get(5, TimeUnit.SECONDS);
            replacement.get(5, TimeUnit.SECONDS);
        } finally {
            returnFormer.countDown();
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test void malformedPersistedRequestFailsVisiblyWithoutCallingProvider() {
        GenerationWorkService service = mock(GenerationWorkService.class);
        AiQuizGenerationService ai = mock(AiQuizGenerationService.class);
        var claim = new GenerationWorkClaim(UUID.randomUUID(), 1, null, "not-json");
        when(service.claim(claim.jobId())).thenReturn(Optional.of(claim));
        var worker = new GenerationWorkExecutorImpl(service, ai, new ObjectMapper());
        worker.execute(claim.jobId()); worker.heartbeat();
        verify(service).failed(claim);
        verify(service, never()).heartbeat(any());
        verifyNoInteractions(ai);
    }

    @Test void heartbeatFailureDoesNotGrantAuthorityAndLeaseLossDoesNotFailTheCurrentJob() {
        GenerationWorkService service = mock(GenerationWorkService.class);
        AiQuizGenerationService ai = mock(AiQuizGenerationService.class);
        var claim = new GenerationWorkClaim(UUID.randomUUID(), 1, null, "{}");
        when(service.claim(claim.jobId())).thenReturn(Optional.of(claim));
        var worker = new GenerationWorkExecutorImpl(service, ai, new ObjectMapper());
        doThrow(new IllegalStateException("local DB unavailable")).when(service).heartbeat(claim);
        doAnswer(c -> {
            assertThatCode(worker::heartbeat).doesNotThrowAnyException();
            throw new GenerationLeaseLostException();
        }).when(ai).generateQuizFromDocumentAsync(eq(claim.jobId()), any());
        worker.execute(claim.jobId());
        verify(service).heartbeat(claim);
        verify(service, never()).failed(any());
        verify(service, never()).finished(any());
    }

    @Test void aMissingClaimIsASkipAndTheSuccessfulOwnerFinishesOnce() {
        GenerationWorkService service = mock(GenerationWorkService.class);
        AiQuizGenerationService ai = mock(AiQuizGenerationService.class);
        var claim = new GenerationWorkClaim(UUID.randomUUID(), 1, null, "{}");
        when(service.claim(claim.jobId())).thenReturn(Optional.empty()).thenReturn(Optional.of(claim));
        var worker = new GenerationWorkExecutorImpl(service, ai, new ObjectMapper());
        worker.execute(claim.jobId()); worker.execute(claim.jobId());
        verify(ai, times(1)).generateQuizFromDocumentAsync(eq(claim.jobId()), any());
        verify(service).finished(claim);
    }
}
