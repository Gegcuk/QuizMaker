package uk.gegc.quizmaker.features.attempt.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptSubmitRequest;

/** Strict parsing for this new contract only; legacy JSON coercion remains unchanged. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quizmaker.attempts.submission-enabled", havingValue = "true")
public class AttemptSubmissionJsonConfig implements WebMvcConfigurer {
    public static final class InvalidRequest extends RuntimeException {
        public InvalidRequest() { super("Invalid submission request"); }
    }

    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : List.copyOf(converters)) if (converter instanceof MappingJackson2HttpMessageConverter json) {
            var strict = json.getObjectMapper().copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            // Sanitize at the parser boundary, before HandlerMethod can log argument-resolution failures.
            converters.add(0, new MappingJackson2HttpMessageConverter(strict) {
                @Override public boolean canRead(Class<?> type, MediaType media) {
                    return type == AttemptSubmitRequest.class && super.canRead(type, media);
                }
                @Override public boolean canRead(Type type, Class<?> context, MediaType media) {
                    return type == AttemptSubmitRequest.class && super.canRead(type, context, media);
                }
                @Override public boolean canWrite(Class<?> type, MediaType media) { return false; }
                @Override public Object read(Type type, Class<?> context, HttpInputMessage input) throws IOException {
                    try { return super.read(type, context, input); }
                    catch (HttpMessageNotReadableException failure) { throw new InvalidRequest(); }
                }
                @Override protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
                    try { return super.readInternal(type, input); }
                    catch (HttpMessageNotReadableException failure) { throw new InvalidRequest(); }
                }
            });
            break;
        }
    }
}
