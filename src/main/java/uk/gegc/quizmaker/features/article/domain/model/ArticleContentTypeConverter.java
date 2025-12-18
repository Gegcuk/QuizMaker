package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ArticleContentTypeConverter implements AttributeConverter<ArticleContentType, String> {

    @Override
    public String convertToDatabaseColumn(ArticleContentType attribute) {
        return attribute != null ? attribute.getValue() : null;
    }

    @Override
    public ArticleContentType convertToEntityAttribute(String dbData) {
        return ArticleContentType.fromValue(dbData);
    }
}
