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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.TagDto;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;
import uk.gegc.quizmaker.service.tag.TagService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public ResponseEntity<Page<TagDto>> getTags(
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable
    ) {
        Page<TagDto> tagDtosPage = tagService.getTags(pageable);
        return ResponseEntity.ok(tagDtosPage);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Map<String, UUID>> createTag(@RequestBody @Valid CreateTagRequest request, Authentication authentication) {
        UUID tagId = tagService.createTag(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("tagId", tagId));
    }

    @GetMapping("/{tagId}")
    public ResponseEntity<TagDto> getTagById(@PathVariable UUID tagId) {
        return ResponseEntity.ok(tagService.getTagById(tagId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{tagId}")
    public ResponseEntity<TagDto> updateTag(
            @PathVariable UUID tagId,
            @RequestBody @Valid UpdateTagRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(tagService.updateTagById(authentication.getName(), tagId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTag(@PathVariable UUID tagId, Authentication authentication) {
        tagService.deleteTagById(authentication.getName(), tagId);
    }

}

