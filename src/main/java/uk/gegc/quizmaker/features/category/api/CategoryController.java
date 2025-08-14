package uk.gegc.quizmaker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.category.CategoryDto;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.service.category.CategoryService;

import java.util.Map;
import java.util.UUID;

@Tag(
        name = "Categories",
        description = "Operations for managing quiz categories"
)
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(
            summary = "List categories",
            description = "Get a paginated list of categories, sorted by name ascending",
            tags = {"Categories"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of categories returned")
    })
    @GetMapping
    public ResponseEntity<Page<CategoryDto>> getCategories(
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(categoryService.getCategories(pageable));
    }

    @Operation(
            summary = "Create a category",
            description = "Create a new category. Requires ADMIN role.",
            tags = {"Categories"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Category created"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @SecurityRequirement(name = "bearerAuth")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Category to create",
            required = true,
            content = @Content(schema = @Schema(implementation = CreateCategoryRequest.class))
    )
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, UUID>> createCategory(
            Authentication authentication,
            @RequestBody @Valid CreateCategoryRequest request
    ) {
        UUID categoryId = categoryService.createCategory(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("categoryId", categoryId));
    }

    @Operation(
            summary = "Get category by ID",
            description = "Retrieve a category by its UUID",
            tags = {"Categories"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category returned"),
            @ApiResponse(responseCode = "404", description = "Category not found")
    })
    @GetMapping("/{categoryId}")
    public ResponseEntity<CategoryDto> getCategoryById(
            @Parameter(description = "UUID of the category", required = true)
            @PathVariable UUID categoryId
    ) {
        return ResponseEntity.ok(categoryService.getCategoryById(categoryId));
    }

    @Operation(
            summary = "Update a category",
            description = "Update name and/or description of an existing category. Requires ADMIN role.",
            tags = {"Categories"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category updated"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Category not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Updated category data",
            required = true,
            content = @Content(schema = @Schema(implementation = UpdateCategoryRequest.class))
    )
    @PatchMapping("/{categoryId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CategoryDto> updateCategory(
            @Parameter(description = "UUID of the category", required = true)
            @PathVariable UUID categoryId,
            Authentication authentication,
            @RequestBody @Valid UpdateCategoryRequest request
    ) {
        return ResponseEntity.ok(
                categoryService.updateCategoryById(authentication.getName(), categoryId, request)
        );
    }

    @Operation(
            summary = "Delete a category",
            description = "Delete a category by its UUID. Requires ADMIN role.",
            tags = {"Categories"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Category deleted"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Category not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteCategory(
            @Parameter(description = "UUID of the category", required = true)
            @PathVariable UUID categoryId,
            Authentication authentication
    ) {
        categoryService.deleteCategoryById(authentication.getName(), categoryId);
    }
}