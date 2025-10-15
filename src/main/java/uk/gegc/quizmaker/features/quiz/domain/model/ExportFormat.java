package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Enumeration of supported export formats for quizzes.
 * Round-trip formats allow re-importing edited data.
 * Print formats are designed for human-readable outputs.
 */
public enum ExportFormat {
    /**
     * JSON format suitable for editing and re-importing
     */
    JSON_EDITABLE,
    
    /**
     * Excel/XLSX format suitable for editing and re-importing
     */
    XLSX_EDITABLE,
    
    /**
     * HTML format optimized for printing with answer key on separate pages
     */
    HTML_PRINT,
    
    /**
     * PDF format optimized for printing with answer key on separate pages
     */
    PDF_PRINT
}

