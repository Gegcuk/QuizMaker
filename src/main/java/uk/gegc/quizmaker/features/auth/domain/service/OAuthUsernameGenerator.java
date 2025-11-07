package uk.gegc.quizmaker.features.auth.domain.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Generates unique, URL-safe usernames for OAuth-created accounts.
 */
@Component
@RequiredArgsConstructor
public class OAuthUsernameGenerator {

    private static final int MAX_USERNAME_LENGTH = 20;

    private final UserRepository userRepository;

    /**
     * Generate a unique username derived from the provided name and email.
     *
     * @param email email address returned by the OAuth provider (may be {@code null})
     * @param name  human-friendly name returned by the OAuth provider (may be {@code null})
     * @return a unique username that satisfies database constraints
     */
    public String generate(String email, String name) {
        String candidate = sanitize(name);
        if (candidate.isEmpty()) {
            candidate = sanitize(extractEmailPrefix(email));
        }
        if (candidate.isEmpty()) {
            candidate = "user";
        }

        candidate = trimToLength(candidate);
        String uniqueCandidate = candidate;
        int suffix = 1;
        while (userRepository.existsByUsername(uniqueCandidate)) {
            String suffixValue = String.valueOf(suffix);
            int maxBaseLength = MAX_USERNAME_LENGTH - suffixValue.length();
            String base = trimToLength(candidate, Math.max(maxBaseLength, 1));
            uniqueCandidate = base + suffixValue;
            suffix++;
        }

        return uniqueCandidate;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        String alphanumericOnly = normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return trimToLength(alphanumericOnly);
    }

    private static String extractEmailPrefix(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return email;
        }
        return email.substring(0, atIndex);
    }

    private static String trimToLength(String value) {
        return trimToLength(value, MAX_USERNAME_LENGTH);
    }

    private static String trimToLength(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
