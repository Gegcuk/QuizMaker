package uk.gegc.quizmaker.features.quiz.api.dto.imports;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ImportSummaryDto", description = "Summary of bulk import results")
public record ImportSummaryDto(
    int total,
    int created,
    int updated,
    int skipped,
    int failed,
    List<ImportErrorDto> errors
) {
    public ImportSummaryDto {
        if (errors == null) {
            errors = List.of();
        }
    }
}
