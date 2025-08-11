package uk.gegc.quizmaker.service.quiz;

import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.tag.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Calculates deterministic hashes for quiz content.
 * Note: Current QuizDto does not include questions/answers payloads yet.
 * This method normalizes and hashes the quiz's core content fields and tag IDs in a stable order.
 */
public class QuizHashCalculator {

    public String calculateContentHash(QuizDto quizDto) {
        if (quizDto == null) {
            return emptyHash();
        }

        String normalizedTitle = normalizeText(quizDto.title());
        String normalizedDescription = normalizeText(quizDto.description());
        String normalizedDifficulty = quizDto.difficulty() != null ? quizDto.difficulty().name() : "";

        // Normalize tag IDs order to be stable regardless of insertion order
        String normalizedTags = normalizeUuidList(quizDto.tagIds());

        // Build a canonical string representation. Exclude volatile fields like status/visibility/timestamps.
        String canonical = new StringBuilder(256)
                .append("t=").append(normalizedTitle).append('|')
                .append("d=").append(normalizedDescription).append('|')
                .append("df=").append(normalizedDifficulty).append('|')
                .append("tags=").append(normalizedTags)
                .toString();

        return sha256Hex(canonical);
    }

    /**
     * Presentation hash: derived from fields that affect how the quiz is presented to users.
     * Per plan: title, description, images, layout. Currently, QuizDto exposes title/description only.
     * Images/layout will be incorporated when available in DTOs.
     */
    public String calculatePresentationHash(QuizDto quizDto) {
        if (quizDto == null) {
            return emptyHash();
        }

        String normalizedTitle = normalizeText(quizDto.title());
        String normalizedDescription = normalizeText(quizDto.description());

        // Build canonical representation limited to presentation-related fields
        String canonical = new StringBuilder(128)
                .append("t=").append(normalizedTitle).append('|')
                .append("d=").append(normalizedDescription)
                .toString();

        return sha256Hex(canonical);
    }

    /**
     * Compares presentation aspects (title, description; later images/layout) between entity and DTO.
     * Prefers stored presentationHash if available on the entity.
     */
    public boolean hasPresentationChanged(Quiz original, QuizDto updated) {
        String updatedHash = calculatePresentationHash(updated);

        String originalStored = original != null ? original.getPresentationHash() : null;
        if (originalStored != null && !originalStored.isBlank()) {
            return !originalStored.equalsIgnoreCase(updatedHash);
        }

        String originalComputed = calculatePresentationHashFromEntity(original);
        return !originalComputed.equalsIgnoreCase(updatedHash);
    }

    private String calculatePresentationHashFromEntity(Quiz quiz) {
        if (quiz == null) {
            return emptyHash();
        }
        String normalizedTitle = normalizeText(quiz.getTitle());
        String normalizedDescription = normalizeText(quiz.getDescription());
        String canonical = new StringBuilder(128)
                .append("t=").append(normalizedTitle).append('|')
                .append("d=").append(normalizedDescription)
                .toString();
        return sha256Hex(canonical);
    }

    /**
     * Compare original entity content with updated DTO using the same canonical normalization.
     * If the original already has a contentHash, we prefer comparing against that to avoid
     * any potential mapping drift; otherwise we compute from entity fields.
     */
    public boolean hasContentChanged(Quiz original, QuizDto updated) {
        String updatedHash = calculateContentHash(updated);

        String originalStoredHash = original != null ? original.getContentHash() : null;
        if (originalStoredHash != null && !originalStoredHash.isBlank()) {
            return !originalStoredHash.equalsIgnoreCase(updatedHash);
        }

        String originalComputed = calculateContentHashFromEntity(original);
        return !originalComputed.equalsIgnoreCase(updatedHash);
    }

    private String calculateContentHashFromEntity(Quiz quiz) {
        if (quiz == null) {
            return emptyHash();
        }
        String normalizedTitle = normalizeText(quiz.getTitle());
        String normalizedDescription = normalizeText(quiz.getDescription());
        String normalizedDifficulty = quiz.getDifficulty() != null ? quiz.getDifficulty().name() : "";

        List<UUID> tagIds = extractTagIds(quiz.getTags());
        String normalizedTags = normalizeUuidList(tagIds);

        String canonical = new StringBuilder(256)
                .append("t=").append(normalizedTitle).append('|')
                .append("d=").append(normalizedDescription).append('|')
                .append("df=").append(normalizedDifficulty).append('|')
                .append("tags=").append(normalizedTags)
                .toString();

        return sha256Hex(canonical);
    }

    private static List<UUID> extractTagIds(Set<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(Tag::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static String normalizeText(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private static String normalizeUuidList(List<java.util.UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return "[]";
        }
        return uuids.stream()
                .filter(Objects::nonNull)
                .map(java.util.UUID::toString)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String emptyHash() {
        // SHA-256 of empty string
        return "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855";
    }
}


