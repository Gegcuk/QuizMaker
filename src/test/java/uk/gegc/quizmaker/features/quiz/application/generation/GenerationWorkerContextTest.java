package uk.gegc.quizmaker.features.quiz.application.generation;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationWorkerContextTest {
    @Test void explicitPropagationRestoresPooledThreadAndCallerContextEvenOnFailure() throws Exception {
        GenerationWorkService service = mock(GenerationWorkService.class);
        GenerationWorkClaim claim = new GenerationWorkClaim(UUID.randomUUID(), 1, null, "{}");
        doAnswer(c -> ((Supplier<?>) c.getArgument(1)).get()).when(service).protect(eq(claim), any());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            GenerationWorkerContext.run(claim, service, () -> {
                var task = GenerationWorkerContext.propagate(() -> {
                    GenerationWorkerContext.check(claim.jobId());
                    throw new IllegalStateException("synthetic task failure");
                });
                assertThatThrownBy(() -> pool.submit(task::get).get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
            });
            verify(service, times(1)).protect(eq(claim), any());
            clearInvocations(service);
            pool.submit(() -> GenerationWorkerContext.check(UUID.randomUUID())).get(5, TimeUnit.SECONDS);
            GenerationWorkerContext.check(UUID.randomUUID());
            verifyNoInteractions(service);
        } finally { pool.shutdownNow(); }
    }

    @Test void propagatedStaleClaimRejectsTheActionAndReachesTheWaitingThread() throws Exception {
        GenerationWorkService service = mock(GenerationWorkService.class);
        GenerationWorkClaim claim = new GenerationWorkClaim(UUID.randomUUID(), 1, null, "{}");
        var lost = new GenerationLeaseLostException();
        AtomicBoolean performed = new AtomicBoolean();
        Supplier<String> action = () -> {
            performed.set(true);
            return "unexpected execution";
        };
        doThrow(lost).when(service).protect(eq(claim), any());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            GenerationWorkerContext.run(claim, service, () -> {
                var task = GenerationWorkerContext.propagate(
                        () -> GenerationWorkerContext.write(claim.jobId(), action));
                assertThatThrownBy(() -> pool.submit(task::get).get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCause(lost);
            });
            verify(service, times(1)).protect(eq(claim), any());
            assertThat(performed).isFalse();
            clearInvocations(service);
            pool.submit(() -> GenerationWorkerContext.check(claim.jobId())).get(5, TimeUnit.SECONDS);
            GenerationWorkerContext.check(claim.jobId());
            verifyNoInteractions(service);
        } finally { pool.shutdownNow(); }
    }

    @Test void nestedContextsRestoreAuthorityAndRejectDifferentJobs() {
        GenerationWorkService service = mock(GenerationWorkService.class);
        GenerationWorkClaim first = new GenerationWorkClaim(UUID.randomUUID(), 1, null, "{}");
        GenerationWorkClaim second = new GenerationWorkClaim(UUID.randomUUID(), 2, null, "{}");
        doAnswer(c -> ((Supplier<?>) c.getArgument(1)).get()).when(service).protect(any(), any());
        GenerationWorkerContext.run(first, service, () -> {
            GenerationWorkerContext.run(second, service, () -> GenerationWorkerContext.check(second.jobId()));
            GenerationWorkerContext.check(first.jobId());
            assertThatThrownBy(() -> GenerationWorkerContext.check(second.jobId())).isInstanceOf(GenerationLeaseLostException.class);
        });
        verify(service).protect(eq(first), any());
        verify(service).protect(eq(second), any());
    }

    @Test void legacyActionsHaveNoFenceAndWrappedLeaseLossKeepsItsIdentity() {
        assertThat(GenerationWorkerContext.propagate(() -> "legacy").get()).isEqualTo("legacy");
        assertThat(GenerationWorkerContext.write(UUID.randomUUID(), () -> 7)).isEqualTo(7);
        GenerationWorkerContext.rethrowIfLost(new IllegalStateException("ordinary failure"));
        var lost = new GenerationLeaseLostException();
        assertThatThrownBy(() -> GenerationWorkerContext.rethrowIfLost(new CompletionException(lost))).isSameAs(lost);
    }
}
