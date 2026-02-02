package uk.gegc.quizmaker.features.repetition.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionEntryDto;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionHistoryDto;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewLog;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;

import java.util.UUID;

@Component
public class RepetitionDtoMapper {
    public RepetitionEntryDto toEntryDto(
            SpacedRepetitionEntry entry,
            int priorityScore
    ){
        return new RepetitionEntryDto(
                entry.getId(),
                entry.getQuestion().getId(),
                entry.getNextReviewAt(),
                entry.getLastReviewedAt(),
                entry.getLastGrade(),
                entry.getIntervalDays(),
                entry.getRepetitionCount(),
                entry.getEaseFactor(),
                entry.getReminderEnabled(),
                priorityScore
        );
    }

    public RepetitionHistoryDto toHistoryDto(RepetitionReviewLog log){
        return new RepetitionHistoryDto(
                log.getId(),
                log.getEntry().getId(),
                log.getContentType(),
                log.getContentId(),
                log.getGrade(),
                log.getReviewedAt(),
                log.getIntervalDays(),
                log.getEaseFactor(),
                log.getRepetitionCount(),
                log.getSourceType(),
                log.getSourceId(),
                log.getAttemptId()
        );
    }
}
