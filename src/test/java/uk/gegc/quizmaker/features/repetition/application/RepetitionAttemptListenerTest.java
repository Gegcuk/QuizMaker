package uk.gegc.quizmaker.features.repetition.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.attempt.domain.event.AttemptCompletedEvent;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@DisplayName("RepetitionAttemptListener Tests")
class RepetitionAttemptListenerTest extends BaseUnitTest {

    @Mock private RepetitionProcessingService repetitionProcessingService;

    private RepetitionAttemptListener listener;

    @BeforeEach
    void setUp() {
        listener = new RepetitionAttemptListener(repetitionProcessingService);
    }

    @Test
    @DisplayName("Should delegate to processAttempt with attemptId")
    void shouldDelegateToProcessAttempt() {
        UUID attemptId = UUID.randomUUID();
        AttemptCompletedEvent event = new AttemptCompletedEvent(
                this, attemptId, UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        listener.onAttemptCompleted(event);

        verify(repetitionProcessingService).processAttempt(eq(attemptId));
    }

    @Test
    @DisplayName("Should be AFTER_COMMIT and use generalTaskExecutor")
    void shouldHaveCorrectAnnotations() throws NoSuchMethodException {
        var method = RepetitionAttemptListener.class.getMethod("onAttemptCompleted", AttemptCompletedEvent.class);

        TransactionalEventListener txn = method.getAnnotation(TransactionalEventListener.class);
        assertNotNull(txn, "Missing @TransactionalEventListener");
        assertEquals(TransactionPhase.AFTER_COMMIT, txn.phase(), "Expected phase AFTER_COMMIT");

        Async async = method.getAnnotation(Async.class);
        assertNotNull(async, "Missing @Async");
        assertEquals("generalTaskExecutor", async.value(), "Expected generalTaskExecutor");
    }
}