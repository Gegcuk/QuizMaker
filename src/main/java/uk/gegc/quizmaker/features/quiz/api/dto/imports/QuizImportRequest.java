package uk.gegc.quizmaker.features.quiz.api.dto.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;

@Schema(name = "QuizImportRequest", description = "Request to import quizzes from a file")
public record QuizImportRequest(
    @Schema(description = "Quiz import file", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Import file is required")
    MultipartFile file,

    @Schema(description = "Import format (JSON_EDITABLE or XLSX_EDITABLE)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Import format is required")
    ExportFormat format,

    @Schema(description = "Upsert strategy (defaults to CREATE_ONLY)")
    UpsertStrategy strategy,

    @Schema(description = "Validate only, do not persist data (defaults to false)")
    Boolean dryRun,

    @Schema(description = "Auto-create missing tags (defaults to false)")
    Boolean autoCreateTags,

    @Schema(description = "Auto-create missing category (defaults to false)")
    Boolean autoCreateCategory
) {
    public QuizImportRequest {
        if (strategy == null) {
            strategy = UpsertStrategy.CREATE_ONLY;
        }
        if (dryRun == null) {
            dryRun = false;
        }
        if (autoCreateTags == null) {
            autoCreateTags = false;
        }
        if (autoCreateCategory == null) {
            autoCreateCategory = false;
        }
    }
}
