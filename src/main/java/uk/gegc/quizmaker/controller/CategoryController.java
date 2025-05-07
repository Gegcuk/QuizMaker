package uk.gegc.quizmaker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.category.CategoryDto;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.service.category.CategoryService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<Page<CategoryDto>> getCategories(
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort =  "name", direction = Sort.Direction.ASC)
            Pageable pageable
    ){
        Page<CategoryDto> categoryDtosPage = categoryService.getCategories(pageable);
        return ResponseEntity.ok(categoryDtosPage);
    }

    @PostMapping
    public ResponseEntity<Map<String, UUID>> createCategory(@RequestBody @Valid CreateCategoryRequest request){
        UUID categoryId = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("categoryId", categoryId));
    }

    @GetMapping("/{categoryId}")
    public ResponseEntity<CategoryDto> getCategoryById(@PathVariable UUID categoryId){
        return ResponseEntity.ok(categoryService.getCategoryById(categoryId));
    }

    @PatchMapping("/{categoryId}")
    public ResponseEntity<CategoryDto> updateCategory(
            @PathVariable UUID categoryId,
            @RequestBody @Valid UpdateCategoryRequest request
            ){
        return ResponseEntity.ok(categoryService.updateCategoryById(categoryId, request));
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID categoryId){
        categoryService.deleteCategoryById(categoryId);
    }

}
