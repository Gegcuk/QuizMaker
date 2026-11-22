package uk.gegc.quizmaker.features.quiz.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.features.quiz.api.dto.PublishQuizRevisionRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.Reason.INVALID_INPUT;

@Configuration(proxyBeanMethods = false)
public class QuizRevisionPublicationJsonConfig implements WebMvcConfigurer {
    private final Validator validator;

    public QuizRevisionPublicationJsonConfig(Validator validator) {
        this.validator = validator;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        var mapper = converters.stream().filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .filter(json -> json.canRead(PublishQuizRevisionRequest.class, MediaType.APPLICATION_JSON))
                .map(MappingJackson2HttpMessageConverter::getObjectMapper)
                .findFirst().orElseThrow();
        converters.add(0, new PublicationRequestConverter(mapper, validator));
    }

    private static final class PublicationRequestConverter extends MappingJackson2HttpMessageConverter {
        private final Validator validator;

        PublicationRequestConverter(ObjectMapper mapper, Validator validator) {
            super(mapper);
            this.validator = validator;
        }

        @Override
        public boolean canRead(Class<?> type, MediaType media) {
            return type == PublishQuizRevisionRequest.class && super.canRead(type, media);
        }

        @Override
        public boolean canRead(Type type, Class<?> context, MediaType media) {
            return type == PublishQuizRevisionRequest.class && super.canRead(type, context, media);
        }

        @Override
        public boolean canWrite(Class<?> type, MediaType media) {
            return false;
        }

        @Override
        protected ObjectReader customizeReader(ObjectReader reader, JavaType type) {
            return reader.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        }

        @Override
        public Object read(Type type, Class<?> context, HttpInputMessage input) throws IOException {
            try {
                return validate(super.read(type, context, input));
            } catch (HttpMessageNotReadableException failure) {
                throw new PublicationException(INVALID_INPUT);
            }
        }

        @Override
        protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
            try {
                return validate(super.readInternal(type, input));
            } catch (HttpMessageNotReadableException failure) {
                throw new PublicationException(INVALID_INPUT);
            }
        }

        private PublishQuizRevisionRequest validate(Object body) {
            // Spring logs argument-resolution failures before controller advice can sanitize them.
            if (!(body instanceof PublishQuizRevisionRequest command) || !validator.validate(command).isEmpty()) {
                throw new PublicationException(INVALID_INPUT);
            }
            return command;
        }
    }
}
