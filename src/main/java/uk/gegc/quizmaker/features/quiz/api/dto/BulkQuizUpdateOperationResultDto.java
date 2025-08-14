package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(name = "BulkQuizUpdateOperationResultDto", description = "Outcome of a bulk operation")
public record BulkQuizUpdateOperationResultDto(
        @Schema(description = "IDs of quizzes successfully updated")
        List<UUID> successfulIds,

        @Schema(description = "Mapping of quiz IDs to failure reason")
        Map<UUID, String> failures
) {
}
