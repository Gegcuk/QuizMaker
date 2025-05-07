package uk.gegc.quizmaker.mapper;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.category.CategoryDto;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.model.quiz.Category;

@Component
public class CategoryMapper {

    public Category toEntity(CreateCategoryRequest request){
        Category category = new Category();
        category.setName(request.name());
        category.setDescription(request.description());
        return category;
    }

    public void updateCategory(Category category, UpdateCategoryRequest request) {
        category.setName(request.name());
        category.setDescription(request.description());
    }

    public CategoryDto toDto(Category category){
        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getDescription()
        );
    }
}
