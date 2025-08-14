package uk.gegc.quizmaker.features.category.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.category.api.dto.CategoryDto;
import uk.gegc.quizmaker.features.category.api.dto.CreateCategoryRequest;
import uk.gegc.quizmaker.features.category.api.dto.UpdateCategoryRequest;

import java.util.UUID;

public interface CategoryService {
    Page<CategoryDto> getCategories(Pageable pageable);

    UUID createCategory(String username, CreateCategoryRequest request);

    CategoryDto getCategoryById(UUID categoryId);

    CategoryDto updateCategoryById(String username, UUID categoryId, UpdateCategoryRequest request);

    void deleteCategoryById(String username, UUID categoryId);
}
