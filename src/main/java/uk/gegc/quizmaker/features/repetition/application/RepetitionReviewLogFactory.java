package uk.gegc.quizmaker.features.repetition.application;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.repetition.domain.model.*;

@Component
public class RepetitionReviewLogFactory {

    public RepetitionReviewLog fromAttempt(
            Answer answer,
            SpacedRepetitionEntry entry,
            ContentKey key,
            SrsAlgorithm.SchedulingResult result
    ) {
        RepetitionReviewLog log = new RepetitionReviewLog();
        log.setUser(entry.getUser());
        log.setEntry(entry);
        log.setContentType(key.type());
        log.setContentId(key.id());
        log.setGrade(result.lastGrade());
        log.setReviewedAt(result.lastReviewedAt());
        log.setIntervalDays(result.intervalDays());
        log.setEaseFactor(result.easeFactor());
        log.setRepetitionCount(result.repetitionCount());
        log.setSourceType(RepetitionReviewSourceType.ATTEMPT_ANSWER);
        log.setSourceId(answer.getId());
        log.setAttemptId(answer.getAttempt().getId());
        return log;
    }

}
