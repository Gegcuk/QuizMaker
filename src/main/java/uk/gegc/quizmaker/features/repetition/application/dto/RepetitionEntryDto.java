package uk.gegc.quizmaker.features.repetition.application.dto;

import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;

import java.time.Instant;
import java.util.UUID;

public record RepetitionEntryDto (
        UUID entryId,
        UUID questionId,
        Instant nextReviewAt,
        Instant lastReviewedAt,
        RepetitionEntryGrade lastGrade,
        Integer intervalDays,
        Integer repetitionCount,
        Double easeFactor,
        Boolean reviewEnabled,
        int priorityScore
){}
