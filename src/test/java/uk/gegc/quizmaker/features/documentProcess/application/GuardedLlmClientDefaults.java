package uk.gegc.quizmaker.features.documentProcess.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

/** Runs only port default guard methods; the actual provider operations remain test doubles. */
final class GuardedLlmClientDefaults {
    static void enable(LlmClient client) {
        lenient().doCallRealMethod().when(client).generateStructure(anyString(), any(), any(Runnable.class));
        lenient().doCallRealMethod().when(client).generateStructureWithContext(
                anyString(), any(), anyList(), anyInt(), anyInt(), any(Runnable.class));
    }
}
