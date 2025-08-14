package uk.gegc.quizmaker.features.tag.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.tag.api.dto.CreateTagRequest;
import uk.gegc.quizmaker.features.tag.api.dto.TagDto;
import uk.gegc.quizmaker.features.tag.api.dto.UpdateTagRequest;

import java.util.UUID;

public interface TagService {
    Page<TagDto> getTags(Pageable pageable);

    UUID createTag(String username, CreateTagRequest request);

    TagDto getTagById(UUID tagId);

    TagDto updateTagById(String username, UUID tagId, UpdateTagRequest request);

    void deleteTagById(String username, UUID tagId);
}
