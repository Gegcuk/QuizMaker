package uk.gegc.quizmaker.service.tag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.TagDto;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.TagMapper;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.service.tag.impl.TagServiceImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagServiceImplTest {

    @Mock
    TagRepository tagRepository;
    @Mock
    TagMapper tagMapper;
    @InjectMocks
    TagServiceImpl service;

    private Tag entity;
    private TagDto dto;
    private UUID id;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        entity = new Tag();
        entity.setId(id);
        entity.setName("Name");
        entity.setDescription("Desc");
        dto = new TagDto(id, "Name", "Desc");
    }

    @Test
    void getTags_returnsMappedPage() {
        Pageable pageReq = PageRequest.of(0, 10);
        List<Tag> list = List.of(entity);
        Page<Tag> page = new PageImpl<>(list, pageReq, 1);
        when(tagRepository.findAll(pageReq)).thenReturn(page);
        when(tagMapper.toDto(entity)).thenReturn(dto);

        Page<TagDto> result = service.getTags(pageReq);

        assertEquals(1, result.getTotalElements());
        assertEquals(dto, result.getContent().get(0));
        verify(tagRepository).findAll(pageReq);
        verify(tagMapper).toDto(entity);
    }

    @Test
    void createCategory_mapsAndSaves() {
        CreateTagRequest req = new CreateTagRequest("Foo", "Bar");
        when(tagMapper.toEntity(req)).thenReturn(entity);
        when(tagRepository.save(entity)).thenReturn(entity);

        UUID ret = service.createTag(req);
        assertEquals(id, ret);

        InOrder inOrder = inOrder(tagMapper, tagRepository);
        inOrder.verify(tagMapper).toEntity(req);
        inOrder.verify(tagRepository).save(entity);
    }

    @Test
    void getTagById_found() {
        when(tagRepository.findById(id)).thenReturn(Optional.of(entity));
        when(tagMapper.toDto(entity)).thenReturn(dto);

        TagDto ret = service.getTagById(id);
        assertEquals(dto, ret);
        verify(tagRepository).findById(id);
        verify(tagMapper).toDto(entity);
    }

    @Test
    void getTagById_notFound() {
        when(tagRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getTagById(id));
        verify(tagRepository).findById(id);
        verifyNoMoreInteractions(tagMapper);
    }

    @Test
    void updateTagById_found() {
        UpdateTagRequest req = new UpdateTagRequest("New", "NewDesc");
        when(tagRepository.findById(id)).thenReturn(Optional.of(entity));
        when(tagRepository.save(entity)).thenReturn(entity);
        when(tagMapper.toDto(entity)).thenReturn(dto);

        TagDto ret = service.updateTagById(id, req);
        assertEquals(dto, ret);

        InOrder ord = inOrder(tagRepository, tagMapper);
        ord.verify(tagRepository).findById(id);
        ord.verify(tagMapper).updateTag(entity, req);
        ord.verify(tagRepository).save(entity);
        ord.verify(tagMapper).toDto(entity);
    }

    @Test
    void updateTagById_notFound() {
        when(tagRepository.findById(id)).thenReturn(Optional.empty());
        UpdateTagRequest req = new UpdateTagRequest("X", "Y");
        assertThrows(ResourceNotFoundException.class,
                () -> service.updateTagById(id, req));
        verify(tagRepository).findById(id);
        verifyNoMoreInteractions(tagMapper);
    }

    @Test
    void deleteTagById_exists() {
        when(tagRepository.existsById(id)).thenReturn(true);
        // no exception
        service.deleteTagById(id);
        verify(tagRepository).existsById(id);
        verify(tagRepository).deleteById(id);
    }

    @Test
    void deleteTagById_notExists() {
        when(tagRepository.existsById(id)).thenReturn(false);
        assertThrows(ResourceNotFoundException.class,
                () -> service.deleteTagById(id));
        verify(tagRepository).existsById(id);
        verify(tagRepository, never()).deleteById(any());
    }
}
