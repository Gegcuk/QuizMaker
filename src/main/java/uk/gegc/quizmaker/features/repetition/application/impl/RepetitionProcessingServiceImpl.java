package uk.gegc.quizmaker.features.repetition.application.impl;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.repetition.application.*;
import uk.gegc.quizmaker.features.repetition.domain.model.ContentKey;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewLog;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;


@Service
public class RepetitionProcessingServiceImpl implements RepetitionProcessingService {

    private static final Logger log = LoggerFactory.getLogger(RepetitionProcessingServiceImpl.class);

    private final AttemptRepository attemptRepository;
    private final RepetitionReviewLogRepository logRepository;
    private final RepetitionStrategyRegistry strategyRegistry;
    private final AttemptContentResolver contentResolver;
    private final RepetitionReviewLogFactory logFactory;
    private final SrsAlgorithm srsAlgorithm;
    private final java.time.Clock clock;

    private final RepetitionProcessingService self;

    public RepetitionProcessingServiceImpl(
            AttemptRepository attemptRepository,
            RepetitionReviewLogRepository logRepository,
            RepetitionStrategyRegistry strategyRegistry,
            AttemptContentResolver contentResolver,
            RepetitionReviewLogFactory logFactory,
            SrsAlgorithm srsAlgorithm,
            java.time.Clock clock,
            @Lazy RepetitionProcessingService self
    ) {
        this.attemptRepository = attemptRepository;
        this.logRepository = logRepository;
        this.strategyRegistry = strategyRegistry;
        this.contentResolver = contentResolver;
        this.logFactory = logFactory;
        this.srsAlgorithm = srsAlgorithm;
        this.clock = clock;
        this.self = self;
    }

    @Override
    public void processAttempt(java.util.UUID attemptId) {
        Attempt attempt = loadAttempt(attemptId);
        if (!isRepetitionEnabled(attempt)) return;
        AtomicInteger scheduledCount = new AtomicInteger();
        AtomicInteger dedupedCount = new AtomicInteger();
        AtomicInteger optimisticLockConflicts = new AtomicInteger();

        attempt.getAnswers().forEach(answer -> {
            RetryOutcome outcome = withRetry(
                    () -> self.processAnswerTx(answer),
                    attempt.getId(),
                    answer.getId(),
                    optimisticLockConflicts
            );
            if (outcome == RetryOutcome.SUCCESS) {
                scheduledCount.incrementAndGet();
            } else {
                dedupedCount.incrementAndGet();
            }
        });

        log.info("Repetition attempt processed: attemptId={}, scheduledCount={}, dedupedCount={}, optimisticLockConflicts={}",
                attempt.getId(),
                scheduledCount.get(),
                dedupedCount.get(),
                optimisticLockConflicts.get());
    }

    private Attempt loadAttempt(java.util.UUID attemptId) {
        return attemptRepository.findFullyLoadedById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attempt " + attemptId + " not found"));
    }

    private boolean isRepetitionEnabled(Attempt attempt) {
        return Boolean.TRUE.equals(attempt.getQuiz().getIsRepetitionEnabled());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAnswerTx(Answer answer) {
        Instant reviewedAt = answer.getAttempt().getCompletedAt();
        if (reviewedAt == null) {
            reviewedAt = Instant.now(clock);
        }

        ContentKey key = contentResolver.resolve(answer);
        RepetitionContentStrategy strategy = strategyRegistry.get(key.type());

        SpacedRepetitionEntry entry = strategy.findOrCreateEntry(answer.getAttempt().getUser(), key);

        RepetitionEntryGrade grade = mapAnswerToGrade(answer);
        SrsAlgorithm.SchedulingResult result = applySchedule(entry, grade, reviewedAt);

        strategy.applySchedule(entry, result);
        strategy.save(entry);

        RepetitionReviewLog log = logFactory.fromAttempt(answer, entry, key, result);
        logRepository.save(log);
    }

    private RepetitionEntryGrade mapAnswerToGrade(Answer answer) {
        return Boolean.TRUE.equals(answer.getIsCorrect())
                ? RepetitionEntryGrade.GOOD
                : RepetitionEntryGrade.AGAIN;
    }

    private SrsAlgorithm.SchedulingResult applySchedule(
            SpacedRepetitionEntry entry,
            RepetitionEntryGrade grade,
            java.time.Instant now
    ) {
        if (entry.getRepetitionCount() == 0 && entry.getIntervalDays() == 0) {
            return srsAlgorithm.initialSchedule(grade, now);
        }
        return srsAlgorithm.applyReview(
                entry.getRepetitionCount(),
                entry.getIntervalDays(),
                entry.getEaseFactor(),
                grade,
                now
        );
    }

    private RetryOutcome withRetry(
            Runnable action,
            java.util.UUID attemptId,
            java.util.UUID answerId,
            AtomicInteger optimisticLockConflicts
    ) {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                action.run();
                return RetryOutcome.SUCCESS;
            } catch (DataIntegrityViolationException e) {
                if (isDuplicateKey(e)) {
                    log.info("Repetition deduped attempt answer: attemptId={}, answerId={}", attemptId, answerId);
                    return RetryOutcome.DEDUPED;
                }
                if (attempt == maxRetries - 1) throw e;
                sleepBackoff(attempt + 1);
            } catch (OptimisticLockingFailureException e) {
                optimisticLockConflicts.incrementAndGet();
                log.warn("Repetition optimistic lock conflict: attemptId={}, answerId={}, attempt={}",
                        attemptId, answerId, attempt + 1);
                if (attempt == maxRetries - 1) throw e;
                sleepBackoff(attempt + 1);
            }
        }
        return RetryOutcome.DEDUPED;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isDuplicateKey(DataIntegrityViolationException e) {
        return e.getMessage() != null && e.getMessage().contains("Duplicate");
    }

    private enum RetryOutcome {
        SUCCESS,
        DEDUPED
    }
}
