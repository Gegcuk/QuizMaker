package uk.gegc.quizmaker.features.documentProcess.application;

/**
 * Result of text normalization containing the normalized text and character count.
 */
public record NormalizationResult(String text, int charCount) {
}
