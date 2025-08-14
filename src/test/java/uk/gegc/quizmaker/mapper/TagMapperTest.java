package uk.gegc.quizmaker.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.tag.api.dto.CreateTagRequest;
import uk.gegc.quizmaker.features.tag.api.dto.TagDto;
import uk.gegc.quizmaker.features.tag.api.dto.UpdateTagRequest;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.infra.mapping.TagMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
public class TagMapperTest {

    private TagMapper tagMapper;

    @BeforeEach
    void setUp() {
        tagMapper = new TagMapper();
    }

    @Test
    void toEntity_mapsNameAndDescription() {
        CreateTagRequest createTagRequest = new CreateTagRequest("Tag", "Description");
        Tag tag = tagMapper.toEntity(createTagRequest);
        assertNull(tag.getId());
        assertNotNull(tag.getDescription());
        assertNotNull(tag.getName());
    }

    @Test
    void updateTag_overwritesFields() {
        Tag tag = new Tag(UUID.randomUUID(), "OldName", "OldDesc");
        UpdateTagRequest updateTagRequest = new UpdateTagRequest("NewName", "NewDesc");
        tagMapper.updateTag(tag, updateTagRequest);
        assertNotNull(tag.getId());
        assertEquals("NewName", tag.getName());
        assertEquals("NewDesc", tag.getDescription());
    }

    @Test
    void toDto_mapsAllFields() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag(id, "Name", "Desc");
        TagDto tagDto = tagMapper.toDto(tag);
        assertEquals(id, tagDto.id());
        assertEquals("Name", tagDto.name());
        assertEquals("Desc", tagDto.description());
    }

}
