package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "QuizSeenAcknowledgement", description = "Confirmed owner acknowledgement; repeated requests return the same seen state")
public record QuizSeenAcknowledgement(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "d290f1ee-6c54-4b01-90e6-d701748f0851") UUID quizId,
        @Schema(description = "Always false after committed acknowledgement", requiredMode = Schema.RequiredMode.REQUIRED, example = "false") boolean isNew
) {
}
