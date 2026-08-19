package uk.gegc.quizmaker.shared.validation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed policy for client-controlled quiz-generation language metadata.
 */
public final class GenerationLanguagePolicy {

    public static final String DEFAULT_LANGUAGE = "en";
    public static final String INVALID_LANGUAGE_MESSAGE =
            "Language must be an exact supported lowercase ISO 639-1 code";

    private static final Pattern LOWERCASE_ISO_CODE = Pattern.compile("[a-z]{2}");
    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.copyOf(Arrays.asList(Locale.getISOLanguages()));

    private GenerationLanguagePolicy() {
    }

    public static String defaultIfAbsent(String language) {
        return language == null ? DEFAULT_LANGUAGE : language;
    }

    public static String requireSupportedOrDefault(String language) {
        String candidate = defaultIfAbsent(language);
        if (!LOWERCASE_ISO_CODE.matcher(candidate).matches()
                || !SUPPORTED_LANGUAGES.contains(candidate)) {
            throw new IllegalArgumentException(INVALID_LANGUAGE_MESSAGE);
        }
        return candidate;
    }
}
