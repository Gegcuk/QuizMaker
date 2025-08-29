package uk.gegc.quizmaker.features.documentProcess.application;

/**
 * Utility methods for document processing operations.
 */
public final class DocumentUtils {

    private DocumentUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Clamps a value between min and max bounds.
     * 
     * @param value the value to clamp
     * @param min the minimum bound
     * @param max the maximum bound
     * @return the clamped value
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
