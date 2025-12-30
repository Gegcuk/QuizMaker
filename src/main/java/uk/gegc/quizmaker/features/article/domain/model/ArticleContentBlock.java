package uk.gegc.quizmaker.features.article.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ArticleContentBlock {
    private ArticleBlockType type;
    private String text;
    private UUID assetId;
    private String alt;
    private String caption;
    private String align;
}
