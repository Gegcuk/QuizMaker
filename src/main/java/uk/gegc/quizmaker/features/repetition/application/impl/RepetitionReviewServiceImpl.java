package uk.gegc.quizmaker.features.repetition.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.repetition.application.SrsAlgorithm;
import uk.gegc.quizmaker.features.repetition.application.exception.RepetitionAlreadyProcessedException;
import uk.gegc.quizmaker.features.repetition.domain.model.*;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepetitionReviewServiceImpl implements RepetitionReviewService {

    private final Clock clock;
    private final SpacedRepetitionEntryRepository entryRepository;
    private final RepetitionReviewLogRepository repetitionReviewLogRepository;
    private final SrsAlgorithm srsAlgorithm;

    @Override
    public SpacedRepetitionEntry reviewEntry(UUID entryId, UUID userId, RepetitionEntryGrade grade, UUID idempotencyKey) {
        SpacedRepetitionEntry entry = entryRepository.findByIdAndUser_Id(entryId, userId).orElseThrow(
                () -> new ResourceNotFoundException("Entry " + entryId + " not found for user " + userId));

        Instant reviewedAt = Instant.now(clock);

        SrsAlgorithm.SchedulingResult result = srsAlgorithm.applyReview(
                entry.getRepetitionCount(),
                entry.getIntervalDays(),
                entry.getEaseFactor(),
                grade,
                reviewedAt);

        applyResults(entry, result);
        entryRepository.save(entry);

        RepetitionReviewLog log = buildLog(entry, result, RepetitionReviewSourceType.MANUAL_REVIEW, idempotencyKey);

        try {
            repetitionReviewLogRepository.save(log);
        } catch (DataIntegrityViolationException e){
            if(idempotencyKey != null && isDuplicateKey(e)){
                throw new RepetitionAlreadyProcessedException("Manual review already processed for key " + idempotencyKey);
            }
            throw e;
        }
        return entry;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SpacedRepetitionEntry reviewEntryTx(UUID entryId, UUID userId, RepetitionEntryGrade grade, UUID idempotencyKey) {
        SpacedRepetitionEntry entry = entryRepository.findByIdAndUser_Id(entryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Entry " + entryId + " not found for user " + userId));

        Instant reviewedAt = Instant.now(clock);

        SrsAlgorithm.SchedulingResult result = srsAlgorithm.applyReview(
                entry.getRepetitionCount(),
                entry.getIntervalDays(),
                entry.getEaseFactor(),
                grade,
                reviewedAt
        );

        applyResults(entry, result);
        entryRepository.save(entry);

        RepetitionReviewLog log = buildLog(entry, result, RepetitionReviewSourceType.MANUAL_REVIEW, idempotencyKey);
        repetitionReviewLogRepository.save(log);

        return entry;
    }

    private boolean isDuplicateKey(DataIntegrityViolationException e) {
        return e.getMessage() != null && e.getMessage().contains("Duplicate");
    }

    private RepetitionReviewLog buildLog(SpacedRepetitionEntry entry,
                                         SrsAlgorithm.SchedulingResult result,
                                         RepetitionReviewSourceType repetitionReviewSourceType,
                                         UUID idempotencyKey) {
        RepetitionReviewLog log = new RepetitionReviewLog();
        log.setUser(entry.getUser());
        log.setEntry(entry);
        log.setContentType(RepetitionContentType.QUESTION);
        log.setContentId(entry.getQuestion().getId());
        log.setGrade(result.lastGrade());
        log.setReviewedAt(result.lastReviewedAt());
        log.setIntervalDays(result.intervalDays());
        log.setEaseFactor(result.easeFactor());
        log.setRepetitionCount(result.repetitionCount());
        log.setSourceType(repetitionReviewSourceType);
        log.setSourceId(idempotencyKey);
        return log;
    }

    private void applyResults(SpacedRepetitionEntry entry, SrsAlgorithm.SchedulingResult result) {
        entry.setIntervalDays(result.intervalDays());
        entry.setRepetitionCount(result.repetitionCount());
        entry.setEaseFactor(result.easeFactor());
        entry.setNextReviewAt(result.nextReviewAt());
        entry.setLastReviewedAt(result.lastReviewedAt());
        entry.setLastGrade(result.lastGrade());
    }
}


