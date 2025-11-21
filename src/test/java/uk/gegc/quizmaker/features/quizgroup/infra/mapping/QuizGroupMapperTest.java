package uk.gegc.quizmaker.features.quizgroup.infra.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.quizgroup.api.dto.CreateQuizGroupRequest;
import uk.gegc.quizmaker.features.quizgroup.api.dto.QuizGroupDto;
import uk.gegc.quizmaker.features.quizgroup.api.dto.UpdateQuizGroupRequest;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroup;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for QuizGroupMapper.
 * 
 * <p>Tests verify:
 * - Entity creation from request DTOs
 * - DTO mapping from entities
 * - Partial updates from update request
 */
@DisplayName("QuizGroupMapper Tests")
class QuizGroupMapperTest {

    private QuizGroupMapper mapper;

    private User owner;
    private Document document;
    private QuizGroup group;

    @BeforeEach
    void setUp() {
        mapper = new QuizGroupMapper();

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("owner");

        document = new Document();
        document.setId(UUID.randomUUID());

        group = new QuizGroup();
        group.setId(UUID.randomUUID());
        group.setOwner(owner);
        group.setName("Test Group");
        group.setDescription("Description");
        group.setColor("#FF5733");
        group.setIcon("book");
        group.setDocument(document);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
    }

    @Test
    @DisplayName("toEntity maps CreateQuizGroupRequest correctly")
    void toEntity_MapsCorrectly() {
        // Given
        CreateQuizGroupRequest request = new CreateQuizGroupRequest(
                "My Group", "Description", "#33FF57", "folder", document.getId()
        );

        // When
        QuizGroup entity = mapper.toEntity(request, owner, document);

        // Then
        assertThat(entity.getOwner()).isEqualTo(owner);
        assertThat(entity.getName()).isEqualTo("My Group");
        assertThat(entity.getDescription()).isEqualTo("Description");
        assertThat(entity.getColor()).isEqualTo("#33FF57");
        assertThat(entity.getIcon()).isEqualTo("folder");
        assertThat(entity.getDocument()).isEqualTo(document);
    }

    @Test
    @DisplayName("toEntity handles null document correctly")
    void toEntity_NullDocument_Success() {
        // Given
        CreateQuizGroupRequest request = new CreateQuizGroupRequest(
                "My Group", null, null, null, null
        );

        // When
        QuizGroup entity = mapper.toEntity(request, owner, null);

        // Then
        assertThat(entity.getDocument()).isNull();
        assertThat(entity.getName()).isEqualTo("My Group");
    }

    @Test
    @DisplayName("toDto maps entity correctly")
    void toDto_MapsCorrectly() {
        // Given
        long quizCount = 5L;

        // When
        QuizGroupDto dto = mapper.toDto(group, quizCount);

        // Then
        assertThat(dto.id()).isEqualTo(group.getId());
        assertThat(dto.ownerId()).isEqualTo(owner.getId());
        assertThat(dto.name()).isEqualTo("Test Group");
        assertThat(dto.description()).isEqualTo("Description");
        assertThat(dto.color()).isEqualTo("#FF5733");
        assertThat(dto.icon()).isEqualTo("book");
        assertThat(dto.documentId()).isEqualTo(document.getId());
        assertThat(dto.quizCount()).isEqualTo(5L);
        assertThat(dto.createdAt()).isEqualTo(group.getCreatedAt());
        assertThat(dto.updatedAt()).isEqualTo(group.getUpdatedAt());
    }

    @Test
    @DisplayName("updateEntity updates only provided fields")
    void updateEntity_PartialUpdate_Success() {
        // Given
        UpdateQuizGroupRequest request = new UpdateQuizGroupRequest(
                "Updated Name", "Updated Desc", "#00FF00", "folder"
        );

        // When
        mapper.updateEntity(group, request);

        // Then
        assertThat(group.getName()).isEqualTo("Updated Name");
        assertThat(group.getDescription()).isEqualTo("Updated Desc");
        assertThat(group.getColor()).isEqualTo("#00FF00");
        assertThat(group.getIcon()).isEqualTo("folder");
    }

    @Test
    @DisplayName("updateEntity ignores null fields")
    void updateEntity_NullFields_Ignored() {
        // Given
        String originalName = group.getName();
        UpdateQuizGroupRequest request = new UpdateQuizGroupRequest(
                null, null, "#00FF00", null
        );

        // When
        mapper.updateEntity(group, request);

        // Then
        assertThat(group.getName()).isEqualTo(originalName); // Unchanged
        assertThat(group.getColor()).isEqualTo("#00FF00"); // Updated
    }
}

