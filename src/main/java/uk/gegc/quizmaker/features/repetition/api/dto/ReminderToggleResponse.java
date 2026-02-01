package uk.gegc.quizmaker.features.repetition.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "ReminderToggleResponse", description = "Response after toggling reminders")
public record ReminderToggleResponse(
        @Schema(description = "Repetition entry ID")
        UUID entryId,
        @Schema(description = "Current reminder enabled state")
        Boolean reminderEnabled
) {
}
