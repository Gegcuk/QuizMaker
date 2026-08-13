package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;

@Schema(
        name = "GenerationTypeCoverage",
        description = "Immutable requested-versus-accepted coverage for one requested question type"
)
public record GenerationTypeCoverage(
        @Schema(description = "Requested question type", example = "MCQ_SINGLE", requiredMode = Schema.RequiredMode.REQUIRED)
        QuestionType questionType,

        @Schema(description = "Questions requested across all selected chunks", example = "10", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer requested,

        @Schema(description = "Runtime-valid questions accepted into this requested type bucket", example = "9", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer accepted,

        @Schema(description = "Requested questions still missing for this type", example = "1", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer missing
) {

    static GenerationTypeCoverage fromSnapshot(GenerationCoverageSnapshot.TypeCoverage snapshot) {
        return new GenerationTypeCoverage(
                snapshot.questionType(),
                snapshot.requested(),
                snapshot.accepted(),
                snapshot.missing()
        );
    }
}
