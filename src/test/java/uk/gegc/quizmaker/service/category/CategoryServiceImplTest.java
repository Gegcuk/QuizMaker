package uk.gegc.quizmaker.service.category;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.category.CategoryDto;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.CategoryMapper;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.service.category.impl.CategoryServiceImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("CategoryServiceImpl Unit Tests")
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private CategoryMapper categoryMapper;
    @InjectMocks
    private CategoryServiceImpl service;

    private Category entity;
    private CategoryDto dto;
    private UUID id;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        entity = new Category();
        entity.setId(id);
        entity.setName("Name");
        entity.setDescription("Desc");
        dto = new CategoryDto(id, "Name", "Desc");
    }

    @Test
    @DisplayName("getCategories: returns mapped page")
    void getCategories_returnsMappedPage() {
        Pageable pageReq = PageRequest.of(0, 10);
        Page<Category> page = new PageImpl<>(List.of(entity), pageReq, 1);
        when(categoryRepository.findAll(pageReq)).thenReturn(page);
        when(categoryMapper.toDto(entity)).thenReturn(dto);

        Page<CategoryDto> result = service.getCategories(pageReq);

        assertEquals(1, result.getTotalElements());
        assertEquals(dto, result.getContent().get(0));
        verify(categoryRepository).findAll(pageReq);
        verify(categoryMapper).toDto(entity);
    }

    @Test
    @DisplayName("createCategory: maps and saves entity")
    void createCategory_mapsAndSaves() {
        CreateCategoryRequest req = new CreateCategoryRequest("Foo", "Bar");
        when(categoryMapper.toEntity(req)).thenReturn(entity);
        when(categoryRepository.save(entity)).thenReturn(entity);

        UUID ret = service.createCategory("adminUser", req);
        assertEquals(id, ret);

        InOrder inOrder = inOrder(categoryMapper, categoryRepository);
        inOrder.verify(categoryMapper).toEntity(req);
        inOrder.verify(categoryRepository).save(entity);
    }

    @Test
    @DisplayName("getCategoryById: returns DTO when found")
    void getCategoryById_found() {
        when(categoryRepository.findById(id)).thenReturn(Optional.of(entity));
        when(categoryMapper.toDto(entity)).thenReturn(dto);

        CategoryDto ret = service.getCategoryById(id);
        assertEquals(dto, ret);
        verify(categoryRepository).findById(id);
        verify(categoryMapper).toDto(entity);
    }

    @Test
    @DisplayName("getCategoryById: throws when not found")
    void getCategoryById_notFound() {
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getCategoryById(id));
        verify(categoryRepository).findById(id);
        verifyNoMoreInteractions(categoryMapper);
    }

    @Test
    @DisplayName("updateCategoryById: updates and returns DTO when found")
    void updateCategoryById_found() {
        UpdateCategoryRequest req = new UpdateCategoryRequest("New", "NewDesc");
        when(categoryRepository.findById(id)).thenReturn(Optional.of(entity));
        when(categoryRepository.save(entity)).thenReturn(entity);
        when(categoryMapper.toDto(entity)).thenReturn(dto);

        CategoryDto ret = service.updateCategoryById("adminUser", id, req);
        assertEquals(dto, ret);

        InOrder ord = inOrder(categoryRepository, categoryMapper);
        ord.verify(categoryRepository).findById(id);
        ord.verify(categoryMapper).updateCategory(entity, req);
        ord.verify(categoryRepository).save(entity);
        ord.verify(categoryMapper).toDto(entity);
    }

    @Test
    @DisplayName("updateCategoryById: throws when not found")
    void updateCategoryById_notFound() {
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());
        UpdateCategoryRequest req = new UpdateCategoryRequest("X", "Y");
        assertThrows(ResourceNotFoundException.class,
                () -> service.updateCategoryById("adminUser", id, req));
        verify(categoryRepository).findById(id);
        verifyNoMoreInteractions(categoryMapper);
    }

    @Test
    @DisplayName("deleteCategoryById: deletes when exists")
    void deleteCategoryById_exists() {
        when(categoryRepository.existsById(id)).thenReturn(true);
        assertDoesNotThrow(() -> service.deleteCategoryById("adminUser", id));
        verify(categoryRepository).existsById(id);
        verify(categoryRepository).deleteById(id);
    }

    @Test
    @DisplayName("deleteCategoryById: throws when not exists")
    void deleteCategoryById_notExists() {
        when(categoryRepository.existsById(id)).thenReturn(false);
        assertThrows(ResourceNotFoundException.class,
                () -> service.deleteCategoryById("adminUser", id));
        verify(categoryRepository).existsById(id);
        verify(categoryRepository, never()).deleteById(any());
    }
}