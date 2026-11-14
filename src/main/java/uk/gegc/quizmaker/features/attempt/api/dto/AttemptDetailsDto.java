package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "AttemptDetailsDto", description = "Detailed information about an attempt, including answers")
public record AttemptDetailsDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UUID of the attempt", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID attemptId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UUID of the quiz", example = "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f")
        UUID quizId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UUID of the user", example = "9f8e7d6c-5b4a-3c2d-1b0a-9f8e7d6c5b4a")
        UUID userId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Timestamp when the attempt was started", example = "2025-05-20T14:30:00Z")
        Instant startedAt,

        @Schema(types = {"string", "null"}, format = "date-time", nullable = true, description = "Timestamp when the attempt was completed; null while incomplete", example = "2025-05-20T14:45:00Z")
        Instant completedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Final status of the attempt", example = "COMPLETED")
        AttemptStatus status,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Mode of the attempt", example = "ALL_AT_ONCE")
        AttemptMode mode,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "List of submitted answers")
        List<AnswerSubmissionDto> answers
) {
}
