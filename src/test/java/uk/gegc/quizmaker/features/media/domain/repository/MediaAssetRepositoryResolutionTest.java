package uk.gegc.quizmaker.features.media.domain.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.MediaAsset;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.media.infra.mapping.MediaAssetMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("db-serial")
@DataJpaTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        // Other cached test contexts share this schema; fixtures roll back without dropping tables.
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class MediaAssetRepositoryResolutionTest {

    @Autowired
    private MediaAssetRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void batchResolutionLoadsOnlyReadyImagesInOneQueryThroughSerialization() {
        MediaAsset first = asset(MediaAssetType.IMAGE, MediaAssetStatus.READY);
        MediaAsset second = asset(MediaAssetType.IMAGE, MediaAssetStatus.READY);
        MediaAsset third = asset(MediaAssetType.IMAGE, MediaAssetStatus.READY);
        MediaAsset uploading = asset(MediaAssetType.IMAGE, MediaAssetStatus.UPLOADING);
        MediaAsset deleted = asset(MediaAssetType.IMAGE, MediaAssetStatus.DELETED);
        MediaAsset document = asset(MediaAssetType.DOCUMENT, MediaAssetStatus.READY);
        List<MediaAsset> fixtures = List.of(first, second, third, uploading, deleted, document);
        fixtures.forEach(entityManager::persist);
        entityManager.flush();
        entityManager.clear();
        List<UUID> ids = new ArrayList<>(fixtures.stream().map(MediaAsset::getId).toList());
        ids.add(UUID.randomUUID());
        Statistics statistics = entityManager.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<MediaAsset> resolved = repository.findAllByIdInAndStatusAndType(
                ids, MediaAssetStatus.READY, MediaAssetType.IMAGE);
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setCdnBaseUrl("https://cdn.test.com");
        MediaAssetMapper mapper = new MediaAssetMapper(properties);
        JsonNode response = new ObjectMapper().valueToTree(resolved.stream().map(mapper::toMediaRef).toList());

        assertThat(resolved).extracting(MediaAsset::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
        assertThat(response.size()).isEqualTo(3);
        response.forEach(image -> {
            assertThat(image.path("cdnUrl").asText()).startsWith("https://cdn.test.com/library/");
            assertThat(image.path("width").asInt()).isEqualTo(1280);
            assertThat(image.path("height").asInt()).isEqualTo(720);
            assertThat(image.path("mimeType").asText()).isEqualTo("image/png");
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private MediaAsset asset(MediaAssetType type, MediaAssetStatus status) {
        MediaAsset asset = new MediaAsset();
        asset.setId(UUID.randomUUID());
        asset.setType(type);
        asset.setStatus(status);
        asset.setMimeType(type == MediaAssetType.IMAGE ? "image/png" : "application/pdf");
        asset.setKey("library/" + asset.getId() + (type == MediaAssetType.IMAGE ? ".png" : ".pdf"));
        asset.setSizeBytes(1024L);
        asset.setCreatedBy("writer");
        if (type == MediaAssetType.IMAGE) {
            asset.setWidth(1280);
            asset.setHeight(720);
        }
        return asset;
    }
}
