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
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.TagDto;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;
import uk.gegc.quizmaker.service.tag.TagService;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Tags", description = "Operations for managing tags")
@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @Operation(
            summary = "List tags with pagination",
            description = "Returns a paged list of tags, sorted by name by default."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of TagDto")
    })
    @GetMapping
    public ResponseEntity<Page<TagDto>> getTags(
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable
    ) {
        Page<TagDto> tagDtosPage = tagService.getTags(pageable);
        return ResponseEntity.ok(tagDtosPage);
    }

    @Operation(
            summary = "Create a new tag",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(responseCode = "201", description = "Tag created, returns its ID"),
                    @ApiResponse(responseCode = "400", description = "Validation error"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "403", description = "Forbidden")
            }
    )
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, UUID>> createTag(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Payload to create a new tag",
                    required = true,
                    content = @Content(schema = @Schema(implementation = CreateTagRequest.class))
            )
            @Valid @RequestBody CreateTagRequest request,
            Authentication authentication
    ) {
        UUID tagId = tagService.createTag(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("tagId", tagId));
    }

    @Operation(
            summary = "Get a tag by ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "TagDto"),
                    @ApiResponse(responseCode = "404", description = "Tag not found")
            }
    )
    @GetMapping("/{tagId}")
    public ResponseEntity<TagDto> getTagById(
            @Parameter(description = "UUID of the tag", required = true)
            @PathVariable UUID tagId
    ) {
        return ResponseEntity.ok(tagService.getTagById(tagId));
    }

    @Operation(
            summary = "Update an existing tag",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Updated TagDto"),
                    @ApiResponse(responseCode = "400", description = "Validation error"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "404", description = "Tag not found")
            }
    )
    @PatchMapping("/{tagId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TagDto> updateTag(
            @Parameter(description = "UUID of the tag to update", required = true)
            @PathVariable UUID tagId,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Fields to update in the tag",
                    required = true,
                    content = @Content(schema = @Schema(implementation = UpdateTagRequest.class))
            )
            @Valid @RequestBody UpdateTagRequest request,

            Authentication authentication
    ) {
        return ResponseEntity.ok(tagService.updateTagById(authentication.getName(), tagId, request));
    }

    @Operation(
            summary = "Delete a tag",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(responseCode = "204", description = "Tag deleted"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "404", description = "Tag not found")
            }
    )
    @DeleteMapping("/{tagId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTag(
            @Parameter(description = "UUID of the tag to delete", required = true)
            @PathVariable UUID tagId,
            Authentication authentication
    ) {
        tagService.deleteTagById(authentication.getName(), tagId);
    }
}