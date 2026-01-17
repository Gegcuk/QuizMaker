package uk.gegc.quizmaker.features.quiz.application.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ContentHashUtil {

    private static final Set<String> SORTED_ARRAY_FIELDS = Set.of(
            "options",
            "statements",
            "right",
            "left",
            "items",
            "gaps"
    );

    private final ObjectMapper objectMapper;

    public String calculateImportContentHash(QuizImportDto quiz) {
        if (quiz == null) {
            return emptyHash();
        }

        String normalizedTitle = normalizeText(quiz.title());
        String normalizedDescription = normalizeText(quiz.description());
        String normalizedDifficulty = quiz.difficulty() != null ? quiz.difficulty().name() : "";
        String normalizedCategory = normalizeText(quiz.category());
        String normalizedTags = normalizeStringList(quiz.tags());
        String normalizedEstimatedTime = quiz.estimatedTime() != null ? quiz.estimatedTime().toString() : "";

        List<String> questionHashes = new ArrayList<>();
        if (quiz.questions() != null) {
            for (QuestionImportDto question : quiz.questions()) {
                if (question != null) {
                    questionHashes.add(calculateQuestionHash(question));
                }
            }
        }
        questionHashes.sort(Comparator.naturalOrder());

        String canonical = new StringBuilder(512)
                .append("t=").append(normalizedTitle).append('|')
                .append("d=").append(normalizedDescription).append('|')
                .append("df=").append(normalizedDifficulty).append('|')
                .append("cat=").append(normalizedCategory).append('|')
                .append("tags=").append(normalizedTags).append('|')
                .append("et=").append(normalizedEstimatedTime).append('|')
                .append("qs=").append(String.join(",", questionHashes))
                .toString();

        return sha256Hex(canonical);
    }

    private String calculateQuestionHash(QuestionImportDto question) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", question.type() != null ? question.type().name() : "");
        root.put("difficulty", question.difficulty() != null ? question.difficulty().name() : "");
        root.put("text", normalizeText(question.questionText()));
        root.put("hint", normalizeText(question.hint()));
        root.put("explanation", normalizeText(question.explanation()));

        applyAttachment(root, question);

        JsonNode canonicalContent = canonicalize(question.content(), null);
        root.set("content", canonicalContent);

        String canonicalJson = writeCanonicalJson(root);
        return sha256Hex(canonicalJson);
    }

    private void applyAttachment(ObjectNode root, QuestionImportDto question) {
        MediaRefDto attachment = question.attachment();
        String assetId = attachment != null && attachment.assetId() != null ? attachment.assetId().toString() : "";
        String attachmentUrl = assetId.isEmpty() ? normalizeUrl(question.attachmentUrl()) : "";

        root.put("attachmentAssetId", assetId);
        root.put("attachmentUrl", attachmentUrl);
        root.put("attachmentAlt", normalizeText(attachment != null ? attachment.alt() : null));
        root.put("attachmentCaption", normalizeText(attachment != null ? attachment.caption() : null));
    }

    private JsonNode canonicalize(JsonNode node, String fieldName) {
        if (node == null) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(Comparator.naturalOrder());
            boolean hasCorrectOrder = node.has("correctOrder");
            for (String name : fieldNames) {
                JsonNode child = node.get(name);
                if ("items".equals(name) && !hasCorrectOrder && child != null && child.isArray()) {
                    sorted.set(name, canonicalizeArray(child, false));
                } else {
                    sorted.set(name, canonicalize(child, name));
                }
            }
            return sorted;
        }
        if (node.isArray()) {
            boolean sort = fieldName != null && SORTED_ARRAY_FIELDS.contains(fieldName);
            return canonicalizeArray(node, sort);
        }
        return node;
    }

    private ArrayNode canonicalizeArray(JsonNode node, boolean sort) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);

        if (sort) {
            items = sortArrayItems(items);
            for (JsonNode item : items) {
                arrayNode.add(item);
            }
            return arrayNode;
        }

        for (JsonNode item : items) {
            arrayNode.add(canonicalize(item, null));
        }
        return arrayNode;
    }

    private List<JsonNode> sortArrayItems(List<JsonNode> items) {
        List<SortableNode> sortable = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode canonical = canonicalize(item, null);
            SortKey key = sortKey(item, canonical);
            sortable.add(new SortableNode(key, canonical));
        }
        sortable.sort(Comparator.comparing(SortableNode::key));
        return sortable.stream().map(SortableNode::node).collect(Collectors.toList());
    }

    private SortKey sortKey(JsonNode original, JsonNode canonical) {
        if (original != null && original.isObject()) {
            JsonNode idNode = original.get("id");
            if (idNode != null) {
                if (idNode.canConvertToLong()) {
                    return SortKey.forNumber(idNode.asLong());
                }
                if (idNode.isTextual()) {
                    return SortKey.forText(idNode.asText());
                }
            }
        }
        return SortKey.forText(writeCanonicalJson(canonical));
    }

    private String writeCanonicalJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize canonical JSON", ex);
        }
    }

    private String normalizeText(String input) {
        if (input == null) {
            return "";
        }
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String normalizeUrl(String input) {
        if (input == null) {
            return "";
        }
        return input.trim();
    }

    private String normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = normalizeText(value);
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized.stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String emptyHash() {
        return "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855";
    }

    private record SortKey(int kind, long numberValue, String textValue) implements Comparable<SortKey> {
        static SortKey forNumber(long value) {
            return new SortKey(0, value, null);
        }

        static SortKey forText(String value) {
            String normalized = value == null ? "" : value;
            return new SortKey(1, 0, normalized);
        }

        @Override
        public int compareTo(SortKey other) {
            int kindCompare = Integer.compare(this.kind, other.kind);
            if (kindCompare != 0) {
                return kindCompare;
            }
            if (this.kind == 0) {
                return Long.compare(this.numberValue, other.numberValue);
            }
            return Objects.compare(this.textValue, other.textValue, Comparator.naturalOrder());
        }
    }

    private record SortableNode(SortKey key, JsonNode node) {}
}
