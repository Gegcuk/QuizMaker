package uk.gegc.quizmaker.features.repetition.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.repetition.application.SrsAlgorithm;
import uk.gegc.quizmaker.features.repetition.domain.model.ContentKey;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

import jakarta.persistence.EntityManager;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("QuestionRepetitionStrategy Tests")
class QuestionRepetitionStrategyTest extends BaseUnitTest {

    @Mock private SpacedRepetitionEntryRepository entryRepository;
    @Mock private EntityManager entityManager;

    @InjectMocks private QuestionRepetitionStrategy strategy;

    private User user;
    private ContentKey key;
    private UUID userId;
    private UUID questionId;

    @BeforeEach
    void setUp() throws Exception {
        userId = UUID.randomUUID();
        questionId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        key = new ContentKey(RepetitionContentType.QUESTION, questionId);
        // Strategy has EntityManager via @PersistenceContext; inject mock for unit test
        Field em = QuestionRepetitionStrategy.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(strategy, entityManager);
    }

    @Test
    @DisplayName("Should return existing entry when present")
    void shouldReturnExistingEntry() {
        SpacedRepetitionEntry existing = new SpacedRepetitionEntry();
        existing.setUser(user);
        when(entryRepository.findByUser_IdAndQuestion_Id(userId, questionId)).thenReturn(Optional.of(existing));

        SpacedRepetitionEntry result = strategy.findOrCreateEntry(user, key);

        assertSame(existing, result);
    }

    @Test
    @DisplayName("Should initialize new entry with defaults")
    void shouldInitializeDefaults() {
        Question questionRef = new Question();
        when(entryRepository.findByUser_IdAndQuestion_Id(userId, questionId)).thenReturn(Optional.empty());
        when(entityManager.getReference(Question.class, questionId)).thenReturn(questionRef);

        SpacedRepetitionEntry result = strategy.findOrCreateEntry(user, key);

        assertSame(user, result.getUser());
        assertSame(questionRef, result.getQuestion());
        assertEquals(0, result.getIntervalDays());
        assertEquals(0, result.getRepetitionCount());
        assertEquals(2.5, result.getEaseFactor());
        assertEquals(true, result.getReminderEnabled());
    }

    @Test
    @DisplayName("Should apply schedule fields")
    void shouldApplyScheduleFields() {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        Instant nextReview = Instant.parse("2025-02-10T12:00:00Z");
        Instant lastReviewed = Instant.parse("2025-02-01T12:00:00Z");
        SrsAlgorithm.SchedulingResult result = new SrsAlgorithm.SchedulingResult(
                6, 1, 2.5, nextReview, lastReviewed, RepetitionEntryGrade.GOOD);

        strategy.applySchedule(entry, result);

        assertEquals(6, entry.getIntervalDays());
        assertEquals(1, entry.getRepetitionCount());
        assertEquals(2.5, entry.getEaseFactor());
        assertEquals(nextReview, entry.getNextReviewAt());
        assertEquals(lastReviewed, entry.getLastReviewedAt());
        assertEquals(RepetitionEntryGrade.GOOD, entry.getLastGrade());
    }

    @Test
    @DisplayName("Should delegate save to repository")
    void shouldDelegateSave() {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        SpacedRepetitionEntry saved = new SpacedRepetitionEntry();
        when(entryRepository.save(entry)).thenReturn(saved);

        SpacedRepetitionEntry result = strategy.save(entry);

        assertSame(saved, result);
        verify(entryRepository).save(entry);
    }
}