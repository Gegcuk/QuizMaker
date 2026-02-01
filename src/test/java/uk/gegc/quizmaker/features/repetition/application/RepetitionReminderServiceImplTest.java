package uk.gegc.quizmaker.features.repetition.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RepetitionReminderServiceImpl Tests")
class RepetitionReminderServiceImplTest extends BaseUnitTest {

    @Mock private SpacedRepetitionEntryRepository entryRepository;

    private RepetitionReminderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RepetitionReminderServiceImpl(entryRepository);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when entry is missing")
    void shouldThrowWhenEntryMissing() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.setReminderEnabled(entryId, userId, true));

        verify(entryRepository).findByIdAndUser_Id(entryId, userId);
    }

    @Test
    @DisplayName("Should toggle reminderEnabled and save")
    void shouldToggleReminderEnabled() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setReminderEnabled(true);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(entryRepository.save(entry)).thenReturn(entry);

        SpacedRepetitionEntry result = service.setReminderEnabled(entryId, userId, false);

        assertSame(entry, result);
        assertFalse(entry.getReminderEnabled());
        verify(entryRepository).save(entry);

        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        result = service.setReminderEnabled(entryId, userId, true);
        assertSame(entry, result);
        assertTrue(entry.getReminderEnabled());
        verify(entryRepository, times(2)).save(entry);
    }

    @Test
    @DisplayName("Should only change reminderEnabled")
    void shouldOnlyChangeReminderEnabled() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setReminderEnabled(true);
        entry.setIntervalDays(6);
        entry.setRepetitionCount(2);
        entry.setEaseFactor(2.5);
        Instant nextReviewAt = Instant.parse("2025-01-15T00:00:00Z");
        entry.setNextReviewAt(nextReviewAt);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(entryRepository.save(entry)).thenReturn(entry);

        service.setReminderEnabled(entryId, userId, false);

        assertEquals(Boolean.FALSE, entry.getReminderEnabled());
        assertEquals(6, entry.getIntervalDays());
        assertEquals(2, entry.getRepetitionCount());
        assertEquals(2.5, entry.getEaseFactor());
        assertEquals(nextReviewAt, entry.getNextReviewAt());
    }
}
