package uk.gegc.quizmaker.service.repetition;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.repetition.SpacedRepetitionEntry;
import uk.gegc.quizmaker.repository.repetition.SpacedRepetitionEntryRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class RepetitionService {

    private final SpacedRepetitionEntryRepository repo;

    @Transactional
    public void scheduleForAttempt(Attempt attempt) {
        Instant now = Instant.now();
        for (Answer answer : attempt.getAnswers()) {
            SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
            entry.setUser(answer.getAttempt().getUser());
            entry.setQuestion(answer.getQuestion());  // ← fixed
            entry.setNextReviewAt(now.plus(1, ChronoUnit.DAYS));
            entry.setIntervalDays(1);
            entry.setRepetitionCount(1);
            entry.setEaseFactor(2.5);
            repo.save(entry);
        }
    }
}
