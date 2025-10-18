package uk.gegc.quizmaker.features.quiz.domain.util;

import java.util.UUID;

/**
 * Utility for generating short, human-readable version codes from UUIDs.
 * Used to create unique identifiers for quiz exports that can be easily referenced.
 */
public class VersionCodeGenerator {

    private static final char[] BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int VERSION_CODE_LENGTH = 6;

    private VersionCodeGenerator() {
        // Utility class
    }

    /**
     * Generate a 6-character alphanumeric version code from a UUID.
     * Uses the most significant bits of the UUID and converts to Base36 for compactness.
     * 
     * @param uuid the UUID to generate code from
     * @return 6-character uppercase alphanumeric code
     */
    public static String generateVersionCode(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("UUID cannot be null");
        }

        // Use most significant bits for shorter, still-unique code
        long value = uuid.getMostSignificantBits();
        
        // Convert to Base36 (0-9, A-Z) for readability
        String base36 = toBase36(Math.abs(value));
        
        // Pad with zeros if needed and take first 6 characters
        String padded = padLeft(base36, VERSION_CODE_LENGTH, '0');
        return padded.substring(0, VERSION_CODE_LENGTH);
    }

    /**
     * Convert a long value to Base36 string.
     */
    private static String toBase36(long value) {
        if (value == 0) {
            return "0";
        }

        StringBuilder sb = new StringBuilder();
        long remaining = value;

        while (remaining > 0) {
            int digit = (int) (remaining % 36);
            sb.insert(0, BASE36_CHARS[digit]);
            remaining /= 36;
        }

        return sb.toString();
    }

    /**
     * Pad string on the left to reach desired length.
     */
    private static String padLeft(String str, int length, char padChar) {
        if (str.length() >= length) {
            return str;
        }
        
        StringBuilder sb = new StringBuilder(length);
        for (int i = str.length(); i < length; i++) {
            sb.append(padChar);
        }
        sb.append(str);
        return sb.toString();
    }
}

