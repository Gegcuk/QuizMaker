package uk.gegc.quizmaker.features.quiz.infra.publication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.media.application.RetainedImageService.ImageReference;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.Reason.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.require;

/** Publication-format/v1 only; this does not bind response/scoring adapters or activate a question family. */
@Component
public class PublicationContent {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
    private final QuestionHandlerFactory handlers;
    private final MediaStorageProperties media;
    public PublicationContent(QuestionHandlerFactory handlers, MediaStorageProperties media) {
        this.handlers = handlers; this.media = media;
    }

    public Map<UUID, Set<UUID>> references(List<Question> questions) {
        Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
        for (var question : questions) {
            var content = validate(question);
            Set<UUID> ids = new HashSet<>();
            if (question.attachmentAssetId() != null) ids.add(question.attachmentAssetId());
            else require(question.attachmentUrl() == null || question.attachmentUrl().isBlank(), IMAGE_UNAVAILABLE);
            collect(content, ids);
            result.put(question.id(), Set.copyOf(ids));
        }
        return Map.copyOf(result);
    }

    private JsonNode validate(Question question) {
        try {
            handlers.readValidatedContent(question.type(), question.content());
            return JSON.readTree(question.content()); // Preserve the stored decimal precision after validation.
        } catch (Exception failure) { throw new PublicationException(INVALID_CONTENT); }
    }

    private void collect(JsonNode node, Set<UUID> ids) {
        if (node.isObject() && node.hasNonNull("media")) {
            var id = node.path("media").path("assetId");
            require(id.isTextual(), IMAGE_UNAVAILABLE);
            try { ids.add(UUID.fromString(id.textValue())); }
            catch (IllegalArgumentException failure) { throw new PublicationException(IMAGE_UNAVAILABLE); }
        }
        if (node.isContainerNode()) node.elements().forEachRemaining(child -> collect(child, ids));
    }

    public Materialized materialize(Draft draft, List<Question> ordered,
                                    Map<UUID, Map<UUID, ImageReference>> references, Instant now) {
        require(!ordered.isEmpty() && draft.estimatedMinutes() >= 1, INVALID_CONTENT);
        var canonical = JSON.createObjectNode().put("format", 1).put("quizId", draft.id().toString())
                .put("ownerId", draft.ownerId().toString()).put("title", draft.title()).put("description", draft.description());
        var contents = canonical.putArray("items");
        List<QuizRevision.Item> items = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        for (var question : ordered) {
            UUID questionId = UUID.randomUUID();
            var retained = references.getOrDefault(question.id(), Map.of());
            var content = validate(question).deepCopy();
            bindContent(content, retained, question.attachmentAssetId());
            String attachment = question.attachmentAssetId() == null ? null : url(required(retained, question.attachmentAssetId()));
            var keys = optionKeys(question, content);
            List<QuestionRevision.Option> options = new ArrayList<>();
            for (String key : keys) options.add(new QuestionRevision.Option(UUID.randomUUID(), key, options.size()));
            var snapshot = new QuestionRevision(questionId, question.id(), 1, question.type(), question.difficulty(),
                    question.text(), content.toString(), question.hint(), question.explanation(), attachment,
                    question.attachmentAssetId(), now, options);
            items.add(new QuizRevision.Item(UUID.randomUUID(), items.size(), snapshot));
            var item = contents.addObject().put("sourceId", question.id().toString()).put("type", question.type().name())
                    .put("difficulty", question.difficulty().name()).put("text", question.text())
                    .put("hint", question.hint()).put("explanation", question.explanation()).put("attachmentUrl", attachment)
                    .put("attachmentAssetId", question.attachmentAssetId() == null ? null : question.attachmentAssetId().toString());
            item.set("content", content); item.set("optionKeys", JSON.valueToTree(keys));
            var imageNodes = item.putArray("images");
            retained.values().stream().sorted(Comparator.comparing(r -> r.assetId().toString())).forEach(r -> {
                imageNodes.addObject().put("assetId", r.assetId().toString()).put("bindingId", r.bindingId().toString())
                        .put("versionId", r.versionId().toString()).put("key", r.objectKey()).put("mime", r.mimeType())
                        .put("size", r.sizeBytes()).put("width", r.width()).put("height", r.height());
                images.add(new Image(questionId, r.assetId(), r.bindingId(), r.versionId(), r.objectKey(),
                        r.mimeType(), r.sizeBytes(), r.width(), r.height()));
            });
        }
        return new Materialized(hash(sorted(canonical).toString()), new QuizRevision(UUID.randomUUID(), draft.id(),
                draft.ownerId(), 1, draft.title(), draft.description(), now, items), List.copyOf(images));
    }

    private void bindContent(JsonNode node, Map<UUID, ImageReference> images, UUID attachment) {
        if (node instanceof ObjectNode object) {
            UUID associated = attachment;
            if (object.hasNonNull("media")) {
                associated = UUID.fromString(object.path("media").path("assetId").textValue());
                var image = required(images, associated);
                var value = (ObjectNode) object.get("media");
                value.put("cdnUrl", url(image)).put("width", image.width()).put("height", image.height()).put("mimeType", image.mimeType());
            }
            if (object.hasNonNull("imageUrl")) {
                require(associated != null, IMAGE_UNAVAILABLE);
                object.put("imageUrl", url(required(images, associated)));
            }
        }
        // Only a root imageUrl may use the question's attachment; nested media must identify its own asset.
        if (node.isContainerNode()) node.elements().forEachRemaining(child -> bindContent(child, images, null));
    }

    private static ImageReference required(Map<UUID, ImageReference> images, UUID id) {
        var image = images.get(id); require(image != null, IMAGE_UNAVAILABLE); return image;
    }
    private String url(ImageReference image) { return media.getCdnBaseUrl().replaceAll("/+$", "") + "/" + image.objectKey(); }

    private static List<String> optionKeys(Question question, JsonNode content) {
        List<String> keys = new ArrayList<>();
        switch (question.type()) {
            case MCQ_SINGLE, MCQ_MULTI -> content.path("options").forEach(n -> keys.add(n.path("id").asText()));
            case ORDERING -> content.path("items").forEach(n -> keys.add(n.path("id").asText()));
            case COMPLIANCE -> content.path("statements").forEach(n -> keys.add(n.path("id").asText()));
            case HOTSPOT -> content.path("regions").forEach(n -> keys.add(n.path("id").asText()));
            case MATCHING -> {
                content.path("left").forEach(n -> keys.add("left/" + n.path("id").asText()));
                content.path("right").forEach(n -> keys.add("right/" + n.path("id").asText()));
            }
            case FILL_GAP -> { for (int i = 0; i < content.path("options").size(); i++) keys.add("options/" + i); }
            case TRUE_FALSE, OPEN -> { /* No authored option identities in these source schemas. */ }
        }
        return List.copyOf(keys);
    }

    public static String requestHash(Command command) {
        var value = JSON.createObjectNode().put("version", command.version()).put("quiz", command.quizId().toString())
                .put("expectedVersion", command.expectedVersion());
        var order = value.putArray("order"); command.questionOrder().forEach(id -> order.add(id.toString()));
        return hash(value.toString());
    }
    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = JSON.createObjectNode(); var fields = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, value) -> result.set(key, sorted(value))); return result;
        }
        if (node.isArray()) { var result = JSON.createArrayNode(); node.forEach(n -> result.add(sorted(n))); return result; }
        return node;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
