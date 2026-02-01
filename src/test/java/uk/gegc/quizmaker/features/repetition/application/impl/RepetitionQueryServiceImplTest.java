package uk.gegc.quizmaker.features.repetition.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.repetition.application.RepetitionPriorityCalculator;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionEntryDto;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionHistoryDto;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewLog;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.features.repetition.infra.mapping.RepetitionDtoMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RepetitionQueryServiceImpl Tests")
class RepetitionQueryServiceImplTest extends BaseUnitTest {

    @Mock private SpacedRepetitionEntryRepository entryRepository;
    @Mock private RepetitionReviewLogRepository logRepository;
    @Mock private RepetitionPriorityCalculator calculator;
    @Mock private RepetitionDtoMapper mapper;
    @Mock private java.time.Clock clock;

    @InjectMocks private RepetitionQueryServiceImpl service;

    private UUID userId;
    private Pageable pageable;
    private static final Instant FIXED_NOW = Instant.parse("2025-02-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        pageable = PageRequest.of(0, 20);
    }

    @Test
    @DisplayName("Should pass same now to due query and priorityScore")
    void shouldUseSameNowForDueAndScore() {
        when(clock.instant()).thenReturn(FIXED_NOW);
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        when(entryRepository.findDueEntries(eq(userId), eq(FIXED_NOW), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entry), pageable, 1));
        when(calculator.compute(eq(entry), eq(FIXED_NOW))).thenReturn(42);

        service.getDueEntries(userId, pageable);

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(entryRepository).findDueEntries(eq(userId), nowCaptor.capture(), eq(pageable));
        verify(calculator).compute(eq(entry), nowCaptor.capture());
        List<Instant> captured = nowCaptor.getAllValues();
        assertSame(captured.get(0), captured.get(1), "Same now passed to due query and priorityScore");
    }

    @Test
    @DisplayName("Should preserve repository order for priority queue")
    void shouldNotReorderPriorityQueue() {
        when(clock.instant()).thenReturn(FIXED_NOW);
        SpacedRepetitionEntry entry1 = new SpacedRepetitionEntry();
        SpacedRepetitionEntry entry2 = new SpacedRepetitionEntry();
        Page<SpacedRepetitionEntry> repoPage = new PageImpl<>(List.of(entry1, entry2), pageable, 2);
        when(entryRepository.findPriorityQueue(userId, pageable)).thenReturn(repoPage);
        when(calculator.compute(any(SpacedRepetitionEntry.class), eq(FIXED_NOW))).thenReturn(10);
        RepetitionEntryDto dto1 = new RepetitionEntryDto(null, null, null, null, null, 1, 0, 2.5, true, 10);
        RepetitionEntryDto dto2 = new RepetitionEntryDto(null, null, null, null, null, 2, 0, 2.5, true, 10);
        when(mapper.toEntryDto(eq(entry1), eq(10))).thenReturn(dto1);
        when(mapper.toEntryDto(eq(entry2), eq(10))).thenReturn(dto2);

        Page<RepetitionEntryDto> result = service.getPriorityQueue(userId, pageable);

        assertEquals(List.of(dto1, dto2), result.getContent());
    }

    @Test
    @DisplayName("Should map history logs to DTOs and preserve order")
    void shouldMapHistoryAndPreserveOrder() {
        RepetitionReviewLog log1 = new RepetitionReviewLog();
        RepetitionReviewLog log2 = new RepetitionReviewLog();
        Page<RepetitionReviewLog> repoPage = new PageImpl<>(List.of(log1, log2), pageable, 2);
        when(logRepository.findByUser_IdOrderByReviewedAtDesc(userId, pageable)).thenReturn(repoPage);
        RepetitionHistoryDto historyDto1 = new RepetitionHistoryDto(null, null, null, null, null, null, 1, null, 0, null, null, null);
        RepetitionHistoryDto historyDto2 = new RepetitionHistoryDto(null, null, null, null, null, null, 2, null, 0, null, null, null);
        when(mapper.toHistoryDto(log1)).thenReturn(historyDto1);
        when(mapper.toHistoryDto(log2)).thenReturn(historyDto2);

        Page<RepetitionHistoryDto> result = service.getHistory(userId, pageable);

        assertEquals(List.of(historyDto1, historyDto2), result.getContent());
    }
}