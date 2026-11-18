package uk.gegc.quizmaker.features.result.infra.release;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import uk.gegc.quizmaker.features.result.domain.model.release.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

/** Closed, bounded metadata; no document/question/answer content is accepted here. */
public final class ReleaseConfigurationFormat {
    public static final int MAX_BYTES = 4096;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    public String encode(ReleaseConfiguration value) {
        require(value != null, INVALID_INPUT);
        try {
            String encoded = json.writeValueAsString(value);
            require(encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, INVALID_INPUT);
            return encoded;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new ReleaseConfigurationException(CORRUPT); }
    }
    public ReleaseConfiguration decode(String value, String hash) {
        require(value != null && value.length() <= MAX_BYTES && hash != null && hash.equals(hash(value)), CORRUPT);
        try {
            var decoded = json.readValue(value, ReleaseConfiguration.class);
            require(value.equals(encode(decoded)), CORRUPT);
            return decoded;
        } catch (Exception failure) {
            if (failure instanceof ReleaseConfigurationException known && known.reason() == UNSUPPORTED_VERSION) throw known;
            Throwable cause = failure.getCause();
            if (cause instanceof ReleaseConfigurationException known && known.reason() == UNSUPPORTED_VERSION) throw known;
            throw new ReleaseConfigurationException(CORRUPT);
        }
    }
    public static String hash(String value) {
        require(value != null && value.length() <= MAX_BYTES + 200, INVALID_INPUT);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
