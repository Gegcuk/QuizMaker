package uk.gegc.quizmaker.features.attempt.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.features.attempt.api.dto.AcceptResponseBatchRequest;
import uk.gegc.quizmaker.features.attempt.application.ResponseBatchAcceptanceService;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException;

/** Bounded, strict parser for the new DTO only. Never changes the legacy batch parser. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quizmaker.attempts.response-batch-enabled", havingValue = "true")
public class ResponseBatchJsonConfig implements WebMvcConfigurer {
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : List.copyOf(converters)) if (converter instanceof MappingJackson2HttpMessageConverter existing) {
            var mapper = existing.getObjectMapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            mapper.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule().addDeserializer(java.util.UUID.class,
                    new JsonDeserializer<java.util.UUID>() {
                        @Override public java.util.UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                            String value = parser.getValueAsString();
                            try {
                                if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING) || value == null || value.length() != 36)
                                    throw new IllegalArgumentException();
                                var id = java.util.UUID.fromString(value);
                                if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
                                return id;
                            } catch (IllegalArgumentException invalid) { throw JsonMappingException.from(parser, "Invalid response identifier"); }
                        }
                    }));
            var text = mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
            for (var shape : List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                    com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
                text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
            converters.add(0, new MappingJackson2HttpMessageConverter(mapper) {
                @Override public boolean canRead(Class<?> type, MediaType media) { return type == AcceptResponseBatchRequest.class && super.canRead(type, media); }
                @Override public boolean canRead(Type type, Class<?> context, MediaType media) { return type == AcceptResponseBatchRequest.class && super.canRead(type, context, media); }
                @Override public boolean canWrite(Class<?> type, MediaType media) { return false; }
                private Object bounded(HttpInputMessage input) throws IOException {
                    if (input.getHeaders().getContentLength() > ResponseBatchAcceptanceService.MAX_BODY_BYTES)
                        throw new ResponseBatchException(ResponseBatchException.Reason.LIMIT);
                    byte[] bytes = input.getBody().readNBytes(ResponseBatchAcceptanceService.MAX_BODY_BYTES + 1);
                    if (bytes.length > ResponseBatchAcceptanceService.MAX_BODY_BYTES)
                        throw new ResponseBatchException(ResponseBatchException.Reason.LIMIT);
                    try { return mapper.readValue(bytes, AcceptResponseBatchRequest.class); }
                    catch (com.fasterxml.jackson.databind.exc.InvalidTypeIdException invalid) {
                        throw new ResponseBatchException(ResponseBatchException.Reason.INVALID_ITEMS);
                    } catch (IOException invalid) { throw new ResponseBatchException(ResponseBatchException.Reason.MALFORMED); }
                }
                @Override public Object read(Type type, Class<?> context, HttpInputMessage input) throws IOException { return bounded(input); }
                @Override protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException { return bounded(input); }
            });
            break;
        }
    }
}
