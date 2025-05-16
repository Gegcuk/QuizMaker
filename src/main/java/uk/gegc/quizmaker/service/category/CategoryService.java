package uk.gegc.quizmaker.service.category;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.category.CategoryDto;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;

import java.util.UUID;

public interface CategoryService {
    Page<CategoryDto> getCategories(Pageable pageable);

    UUID createCategory(String username, CreateCategoryRequest request);

    CategoryDto getCategoryById(UUID categoryId);

    CategoryDto updateCategoryById(String username, UUID categoryId, UpdateCategoryRequest request);

    void deleteCategoryById(String username, UUID categoryId);
}
