package uk.gegc.quizmaker.features.repetition.application.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.repetition.application.RepetitionContentStrategy;
import uk.gegc.quizmaker.features.repetition.application.SrsAlgorithm;
import uk.gegc.quizmaker.features.repetition.domain.model.ContentKey;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

@Service
@RequiredArgsConstructor
public class QuestionRepetitionStrategy implements RepetitionContentStrategy {

    private final SpacedRepetitionEntryRepository entryRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public RepetitionContentType supportedType() {
        return RepetitionContentType.QUESTION;
    }

    @Override
    public SpacedRepetitionEntry findOrCreateEntry(User user, ContentKey key) {
        return entryRepository.findByUser_IdAndQuestion_Id(user.getId(), key.id())
                .orElseGet(() -> {
                    SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
                    entry.setUser(user);
                    entry.setQuestion(entityManager.getReference(Question.class, key.id()));
                    entry.setIntervalDays(0);
                    entry.setRepetitionCount(0);
                    entry.setEaseFactor(2.5);
                    entry.setReminderEnabled(true);
                    return entry;
                });
    }

    @Override
    public void applySchedule(SpacedRepetitionEntry entry, SrsAlgorithm.SchedulingResult result) {
       entry.setIntervalDays(result.intervalDays());
       entry.setRepetitionCount(result.repetitionCount());
       entry.setEaseFactor(result.easeFactor());
       entry.setNextReviewAt(result.nextReviewAt());
       entry.setLastReviewedAt(result.lastReviewedAt());
       entry.setLastGrade(result.lastGrade());
    }

    @Override
    public SpacedRepetitionEntry save(SpacedRepetitionEntry entry) {
        return entryRepository.save(entry);
    }
}
