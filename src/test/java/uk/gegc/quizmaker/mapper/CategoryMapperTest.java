package uk.gegc.quizmaker.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.category.CategoryDto;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.model.quiz.Category;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryMapperTest {

    private CategoryMapper categoryMapper;

    @BeforeEach
    void setUp(){
        categoryMapper = new CategoryMapper();
    }

    @Test
    void toEntity_mapsNameAndDescription(){
        CreateCategoryRequest createCategoryRequest = new CreateCategoryRequest("Category", "Description");
        Category category = categoryMapper.toEntity(createCategoryRequest);
        assertNull(category.getId());
        assertNotNull(category.getDescription());
        assertNotNull(category.getName());
    }

    @Test
    void updateCategory_overwritesFields(){
        Category category = new Category(UUID.randomUUID(), "OldName", "OldDescription");
        UpdateCategoryRequest updateCategoryRequest = new UpdateCategoryRequest("NewName", "NewDescription");
        categoryMapper.updateCategory(category, updateCategoryRequest);
        assertNotNull(category.getId());
        assertEquals("NewName", category.getName());
        assertEquals("NewDescription", category.getDescription());
    }

    @Test
    void toDto_mapsAllFields() {
        UUID id = UUID.randomUUID();
        Category category = new Category(id, "Name","Desc");
        CategoryDto categoryDto = categoryMapper.toDto(category);
        assertEquals(id,            categoryDto.id());
        assertEquals("Name",        categoryDto.name());
        assertEquals("Desc",        categoryDto.description());
    }

}
