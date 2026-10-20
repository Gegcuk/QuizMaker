package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationWorkExecutorImplTest {
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
