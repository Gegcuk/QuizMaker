package uk.gegc.quizmaker.features.quiz.domain.model.export;

import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;

import java.util.List;
import java.util.UUID;

/**
 * Payload containing quizzes and options for export rendering.
 * Passed to ExportRenderer implementations.
 * 
 * @param quizzes List of quizzes to export
 * @param printOptions Print formatting options (cover, metadata, hints, etc.)
 * @param filenamePrefix Filename prefix without extension
 * @param exportId Unique identifier for this export; used for audit trails and version tracking
 * @param versionCode Human-readable 6-character code (e.g., "3F7A2K") displayed in footers;
 *                    used to match student sheets with correct answer keys
 * @param shuffleSeed Seed for deterministic shuffling of question content (MCQ options, ORDERING items, etc.);
 *                    ensures answer keys align with shuffled display order
 */
public record ExportPayload(
    List<QuizExportDto> quizzes,
    PrintOptions printOptions,
    String filenamePrefix,
    UUID exportId,
    String versionCode,
    Long shuffleSeed
) {
    public ExportPayload {
        if (quizzes == null) {
            throw new IllegalArgumentException("Quizzes list cannot be null");
        }
        if (printOptions == null) {
            printOptions = PrintOptions.defaults();
        }
        if (filenamePrefix == null || filenamePrefix.isBlank()) {
            filenamePrefix = "quizzes_export";
        }
        if (exportId == null) {
            exportId = UUID.randomUUID();
        }
        if (versionCode == null || versionCode.isBlank()) {
            // Default for test/legacy paths; production always sets a real code
            versionCode = "EXPORT";
        }
        if (shuffleSeed == null) {
            shuffleSeed = exportId.getMostSignificantBits() ^ exportId.getLeastSignificantBits();
        }
    }

    /**
     * Factory method for backward compatibility with tests.
     * Creates an ExportPayload with auto-generated version metadata.
     */
    public static ExportPayload of(List<QuizExportDto> quizzes, PrintOptions printOptions, String filenamePrefix) {
        UUID exportId = UUID.randomUUID();
        String versionCode = generateDefaultVersionCode(exportId);
        Long shuffleSeed = exportId.getMostSignificantBits() ^ exportId.getLeastSignificantBits();
        return new ExportPayload(quizzes, printOptions, filenamePrefix, exportId, versionCode, shuffleSeed);
    }

    private static String generateDefaultVersionCode(UUID uuid) {
        // Simple hex-based version code for default/test cases
        return uuid.toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}

