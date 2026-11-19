package uk.gegc.quizmaker.features.learning.infra.scheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.INVALID_FORMAT;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

/** Strict project-owned internal JSON; no upstream class names/JSON or global mapper changes. */
public final class FsrsStateFormat {
    private static final int MAX_JSON_CHARACTERS = 16_384;
    private final JsonMapper mapper = JsonMapper.builder().addModule(timeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

    private static JavaTimeModule timeModule() {
        var module = new JavaTimeModule();
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override public Instant deserialize(JsonParser input, DeserializationContext context) throws IOException {
                require(input.currentToken() == JsonToken.VALUE_STRING, INVALID_FORMAT);
                try { return Instant.parse(input.getText()); }
                catch (DateTimeParseException failure) { throw new LearningScheduleException(INVALID_FORMAT); }
            }
        });
        return module;
    }

    public String encodeManifest(FsrsManifest value) { return encode(value); }
    public FsrsManifest decodeManifest(String json) { return decode(json, FsrsManifest.class); }
    public String encodeState(State value) { return encode(value); }
    public State decodeState(String json) { return decode(json, State.class); }
    public String encodeTransition(Transition value) { return encode(value); }
    public Transition decodeTransition(String json) { return decode(json, Transition.class); }

    private String encode(Object value) {
        require(value != null, INVALID_FORMAT);
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new LearningScheduleException(INVALID_FORMAT); }
    }

    private <T> T decode(String json, Class<T> type) {
        require(json != null && !json.isBlank() && json.length() <= MAX_JSON_CHARACTERS, INVALID_FORMAT);
        try {
            T value = mapper.readValue(json, type);
            require(value != null, INVALID_FORMAT);
            return value;
        } catch (JsonProcessingException failure) {
            // Preserve only a bounded domain reason, never Jackson's input-bearing exception chain.
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof LearningScheduleException domain) throw new LearningScheduleException(domain.reason());
            }
            throw new LearningScheduleException(INVALID_FORMAT);
        }
    }
}
