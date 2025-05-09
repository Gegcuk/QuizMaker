package uk.gegc.quizmaker.service.category.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.category.CategoryDto;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.CategoryMapper;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.service.category.CategoryService;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {
    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Override
    public Page<CategoryDto> getCategories(Pageable pageable) {
        return categoryRepository.findAll(pageable).map(categoryMapper::toDto);
    }

    @Override
    public UUID createCategory(CreateCategoryRequest request) {
        var category = categoryMapper.toEntity(request);
        return categoryRepository.save(category).getId();

    }

    @Override
    @Transactional(readOnly = true)
    public CategoryDto getCategoryById(UUID categoryId) {
        var category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category " + categoryId + " not found"));
        return categoryMapper.toDto(category);
    }

    @Override
    public CategoryDto updateCategoryById(UUID categoryId, UpdateCategoryRequest request) {
        var existingCategory = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category " + categoryId + " not found"));
        categoryMapper.updateCategory(existingCategory, request);
        return categoryMapper.toDto(categoryRepository.save(existingCategory));
    }

    @Override
    public void deleteCategoryById(UUID categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category " + categoryId + " not found");
        }
        categoryRepository.deleteById(categoryId);
    }
}
