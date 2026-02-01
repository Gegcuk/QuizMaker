package uk.gegc.quizmaker.features.repetition.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ReminderToggleRequest", description = "Payload to enable or disable repetition reminders")
public record ReminderToggleRequest(
        @Schema(description = "true → enable reminder, false → disable reminder", example = "true")
        @NotNull Boolean enabled
) {
}
