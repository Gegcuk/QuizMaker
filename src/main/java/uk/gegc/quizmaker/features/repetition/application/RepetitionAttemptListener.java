package uk.gegc.quizmaker.features.repetition.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uk.gegc.quizmaker.features.attempt.domain.event.AttemptCompletedEvent;

@Service
@RequiredArgsConstructor
public class RepetitionAttemptListener {

    private final RepetitionProcessingService repetitionProcessingService;

    @Async("generalTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttemptCompleted(AttemptCompletedEvent event){
        repetitionProcessingService.processAttempt(event.getAttemptId());
    }

}
