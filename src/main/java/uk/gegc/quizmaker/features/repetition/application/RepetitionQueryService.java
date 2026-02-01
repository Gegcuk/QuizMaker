package uk.gegc.quizmaker.features.repetition.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionEntryDto;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionHistoryDto;

import java.util.UUID;

public interface RepetitionQueryService {
    Page<RepetitionEntryDto> getDueEntries(UUID userId, Pageable pageable);
    Page<RepetitionEntryDto> getPriorityQueue(UUID userId, Pageable pageable);
    Page<RepetitionHistoryDto> getHistory(UUID userId, Pageable pageable);
}
