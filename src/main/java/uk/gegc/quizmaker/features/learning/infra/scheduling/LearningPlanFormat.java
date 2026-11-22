package uk.gegc.quizmaker.features.learning.infra.scheduling;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Bounded metadata only. Never serializes source documents, questions or answers. */
public final class LearningPlanFormat {
    public static final int MAX_BYTES = 32768;
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    public String encode(Object value) {
        try {
            String text = mapper.writeValueAsString(value);
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new LearningSessionException(INVALID_INPUT);
            return text;
        } catch (JsonProcessingException failure) { throw new LearningSessionException(INVALID_INPUT); }
    }
    public <T> T decode(String text, Class<T> type) {
        try {
            if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new LearningSessionException(CORRUPT);
            T result = mapper.readValue(text, type);
            if (result == null) throw new LearningSessionException(CORRUPT);
            return result;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            Throwable cause=failure;
            for(int i=0;cause!=null && i<8;i++,cause=cause.getCause())
                if(cause instanceof LearningSessionException domain && domain.reason()==UNSUPPORTED_VERSION)
                    throw new LearningSessionException(UNSUPPORTED_VERSION);
            throw new LearningSessionException(CORRUPT);
        }
    }
}
