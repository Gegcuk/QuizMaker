package uk.gegc.quizmaker.service.tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.TagDto;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;

import java.util.UUID;

public interface TagService {
    Page<TagDto> getTags(Pageable pageable);

    UUID createTag(String username, CreateTagRequest request);

    TagDto getTagById(UUID tagId);

    TagDto updateTagById(String username, UUID tagId, UpdateTagRequest request);

    void deleteTagById(String username, UUID tagId);
}
