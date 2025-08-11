package uk.gegc.quizmaker.dto.quiz;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.model.quiz.ModerationAction;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "QuizModerationAuditDto", description = "Audit record for quiz moderation")
public record QuizModerationAuditDto(
        @Schema(description = "Audit UUID") UUID id,
        @Schema(description = "Quiz UUID") UUID quizId,
        @Schema(description = "Moderator UUID") UUID moderatorId,
        @Schema(description = "Action") ModerationAction action,
        @Schema(description = "Reason") String reason,
        @Schema(description = "Created at") Instant createdAt,
        @JsonIgnore @Schema(hidden = true) String correlationId
) {
}


