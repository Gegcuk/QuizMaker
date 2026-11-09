package uk.gegc.quizmaker.features.attempt.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.features.attempt.api.dto.AcceptResponseRequest;
import uk.gegc.quizmaker.features.attempt.api.dto.SaveDraftRequest;
import uk.gegc.quizmaker.features.attempt.api.dto.NavigationRequest;
import uk.gegc.quizmaker.features.attempt.api.dto.NextAttemptItemRequest;

/** Strict parsing for this new contract only; legacy JSON coercion remains unchanged. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${quizmaker.attempts.response-acceptance-enabled:false} or ${quizmaker.attempts.draft-commands-enabled:false} or ${quizmaker.attempts.navigation-commands-enabled:false}")
public class ResponseAcceptanceJsonConfig implements WebMvcConfigurer {
    public static final class InvalidRequest extends RuntimeException {
        public InvalidRequest() { super("Invalid response request"); }
    }

    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : List.copyOf(converters)) if (converter instanceof MappingJackson2HttpMessageConverter json) {
            var ids = new SimpleModule().addDeserializer(UUID.class, new JsonDeserializer<UUID>() {
                @Override public UUID deserialize(JsonParser p, DeserializationContext context) throws IOException {
                    String value = p.getValueAsString();
                    if (!p.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING) || value == null || value.length() != 36)
                        throw JsonMappingException.from(p, "Invalid response identifier");
                    try {
                        UUID id = UUID.fromString(value);
                        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
                        return id;
                    }
                    catch (IllegalArgumentException invalid) { throw JsonMappingException.from(p, "Invalid response identifier"); }
                }
            });
            var strict = json.getObjectMapper().copy().registerModule(ids)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            // ALLOW_COERCION_OF_SCALARS does not cover numeric/boolean input for String fields.
            var text = strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
            for (var shape : List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                    com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
                text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
            // Sanitize at the parser boundary, before HandlerMethod can log argument-resolution failures.
            converters.add(0, new MappingJackson2HttpMessageConverter(strict) {
                @Override public boolean canRead(Class<?> type, MediaType media) {
                    return (type == AcceptResponseRequest.class || type == SaveDraftRequest.class || type == NavigationRequest.class || type == NextAttemptItemRequest.class) && super.canRead(type, media);
                }
                @Override public boolean canRead(Type type, Class<?> context, MediaType media) {
                    return (type == AcceptResponseRequest.class || type == SaveDraftRequest.class || type == NavigationRequest.class || type == NextAttemptItemRequest.class) && super.canRead(type, context, media);
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
