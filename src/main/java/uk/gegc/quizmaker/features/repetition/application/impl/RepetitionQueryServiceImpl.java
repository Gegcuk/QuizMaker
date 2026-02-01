package uk.gegc.quizmaker.features.repetition.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.repetition.application.RepetitionPriorityCalculator;
import uk.gegc.quizmaker.features.repetition.application.RepetitionQueryService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionEntryDto;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionHistoryDto;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.features.repetition.infra.mapping.RepetitionDtoMapper;
import uk.gegc.quizmaker.shared.config.ClockConfig;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepetitionQueryServiceImpl implements RepetitionQueryService {
    private final SpacedRepetitionEntryRepository spacedRepetitionEntryRepository;
    private final RepetitionReviewLogRepository logRepository;
    private final RepetitionPriorityCalculator calculator;
    private final RepetitionDtoMapper repetitionDtoMapper;
    private final Clock clock;


    @Override
    public Page<RepetitionEntryDto> getDueEntries(UUID userId, Pageable pageable) {
        Instant now = Instant.now(clock);
        return spacedRepetitionEntryRepository.findDueEntries(userId, now, pageable)
                .map(entry -> repetitionDtoMapper.toEntryDto(entry, calculator.compute(entry, now)));
    }

    @Override
    public Page<RepetitionEntryDto> getPriorityQueue(UUID userId, Pageable pageable) {
        Instant now = Instant.now(clock);
        return spacedRepetitionEntryRepository.findPriorityQueue(userId, pageable)
                .map(entry -> repetitionDtoMapper.toEntryDto(entry, calculator.compute(entry, now)));
    }

    @Override
    public Page<RepetitionHistoryDto> getHistory(UUID userId, Pageable pageable) {
        return logRepository.findByUser_IdOrderByReviewedAtDesc(userId, pageable)
                .map(repetitionDtoMapper::toHistoryDto);
    }
}
