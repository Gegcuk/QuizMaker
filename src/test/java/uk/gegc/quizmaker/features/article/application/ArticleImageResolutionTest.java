package uk.gegc.quizmaker.features.article.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import uk.gegc.quizmaker.features.article.api.dto.ArticleDto;
import uk.gegc.quizmaker.features.article.application.impl.ArticleServiceImpl;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleRepository;
import uk.gegc.quizmaker.features.article.infra.mapping.ArticleMapper;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleImageResolutionTest {
    @Mock ArticleRepository articleRepository;
    @Mock TagRepository tagRepository;
    @Mock MediaAssetService mediaAssetService;
    @Mock AppPermissionEvaluator permissionEvaluator;

    private ArticleService service;

    @BeforeEach
    void setUp() {
        service = new ArticleServiceImpl(articleRepository, tagRepository, new ArticleMapper(),
                permissionEvaluator, mediaAssetService);
    }

    @Test
    void slugReadReturnsPublicRenditionAndPreservesEditorialMetadataAndExplicitOgImage() {
        Article article = article(UUID.randomUUID());
        article.setOgImage("https://example.com/editorial-social.png");
        when(articleRepository.findBySlugAndStatus("article", ArticleStatus.PUBLISHED))
                .thenReturn(Optional.of(article));
        when(mediaAssetService.getByIdsForResolution(List.of(article.getHeroImageAssetId())))
                .thenReturn(Map.of(article.getHeroImageAssetId(), image(article.getHeroImageAssetId())));

        ArticleDto result = service.getArticleBySlug(" article ", false);

        assertThat(result.heroImage().assetId()).isEqualTo(article.getHeroImageAssetId());
        assertThat(result.heroImage().alt()).isEqualTo("Article-specific alt text");
        assertThat(result.heroImage().caption()).isEqualTo("Article-specific caption");
        assertThat(result.heroImage().rendition().url()).isEqualTo("https://cdn.example.com/hero.webp");
        assertThat(result.heroImage().rendition().width()).isEqualTo(1200);
        assertThat(result.heroImage().rendition().height()).isEqualTo(630);
        assertThat(result.heroImage().rendition().mimeType()).isEqualTo("image/webp");
        assertThat(result.ogImage()).isEqualTo(article.getOgImage());
    }

    @Test
    void listResolvesDistinctHeroAssetsTogetherAndLeavesAbsentOgImageUnchanged() {
        UUID assetId = UUID.randomUUID();
        Article first = article(assetId);
        Article second = article(assetId);
        Article noHero = article(null);
        when(articleRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second, noHero)));
        when(mediaAssetService.getByIdsForResolution(List.of(assetId)))
                .thenReturn(Map.of(assetId, image(assetId)));

        var results = service.searchArticles(null, PageRequest.of(0, 10)).getContent();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).heroImage().rendition()).isEqualTo(results.get(1).heroImage().rendition());
        assertThat(results.get(2).heroImage()).isNull();
        assertThat(results).allSatisfy(result -> assertThat(result.ogImage()).isNull());
        verify(mediaAssetService).getByIdsForResolution(List.of(assetId));
        verifyNoMoreInteractions(mediaAssetService);
    }

    @Test
    void unavailableImageDoesNotHideArticleOrItsExistingReference() {
        Article article = article(UUID.randomUUID());
        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));
        when(mediaAssetService.getByIdsForResolution(List.of(article.getHeroImageAssetId())))
                .thenReturn(Map.of());

        ArticleDto result = service.getArticle(article.getId(), false);

        assertThat(result.id()).isEqualTo(article.getId());
        assertThat(result.heroImage().assetId()).isEqualTo(article.getHeroImageAssetId());
        assertThat(result.heroImage().rendition()).isNull();
    }

    @Test
    void articleWithoutHeroDoesNotQueryMedia() {
        Article article = article(null);
        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));

        assertThat(service.getArticle(article.getId(), false).heroImage()).isNull();

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    void draftRemainsHiddenAndPermissionsAreCheckedBeforeResolvingImages() {
        Article draft = article(UUID.randomUUID());
        draft.setStatus(ArticleStatus.DRAFT);
        when(articleRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.getArticle(draft.getId(), false))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getArticle(draft.getId(), true))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(mediaAssetService);
    }

    private Article article(UUID assetId) {
        Article article = new Article();
        article.setId(UUID.randomUUID());
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setHeroImageAssetId(assetId);
        article.setHeroImageAlt("Article-specific alt text");
        article.setHeroImageCaption("Article-specific caption");
        return article;
    }

    private MediaRefDto image(UUID assetId) {
        return new MediaRefDto(assetId, "https://cdn.example.com/hero.webp", "Media alt", "Media caption",
                1200, 630, "image/webp");
    }
}
