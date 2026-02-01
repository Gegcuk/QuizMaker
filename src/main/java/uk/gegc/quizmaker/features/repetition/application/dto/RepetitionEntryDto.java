package uk.gegc.quizmaker.features.repetition.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "RepetitionEntryDto", description = "A repetition entry with scheduling details")
public record RepetitionEntryDto (
        @Schema(description = "Repetition entry ID")
        UUID entryId,
        @Schema(description = "Question ID")
        UUID questionId,
        @Schema(description = "Next scheduled review time (UTC)")
        Instant nextReviewAt,
        @Schema(description = "Last review time (UTC)")
        Instant lastReviewedAt,
        @Schema(description = "Last review grade")
        RepetitionEntryGrade lastGrade,
        @Schema(description = "Interval in days")
        Integer intervalDays,
        @Schema(description = "Repetition count")
        Integer repetitionCount,
        @Schema(description = "Ease factor (SM-2)")
        Double easeFactor,
        @Schema(description = "Reminder enabled for this entry")
        Boolean reminderEnabled,
        @Schema(description = "Computed priority score for display")
        int priorityScore
){}
