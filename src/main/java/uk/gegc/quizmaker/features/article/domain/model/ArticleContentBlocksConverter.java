package uk.gegc.quizmaker.features.article.domain.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Converter
public class ArticleContentBlocksConverter implements AttributeConverter<List<ArticleContentBlock>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<ArticleContentBlock>> TYPE_REF = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<ArticleContentBlock> attribute) {
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize content blocks", e);
        }
    }

    @Override
    public List<ArticleContentBlock> convertToEntityAttribute(String dbData) {
        if (!StringUtils.hasText(dbData)) {
            return new ArrayList<>();
        }
        try {
            List<ArticleContentBlock> parsed = OBJECT_MAPPER.readValue(dbData, TYPE_REF);
            return parsed != null ? parsed : new ArrayList<>();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize content blocks", e);
        }
    }
}
