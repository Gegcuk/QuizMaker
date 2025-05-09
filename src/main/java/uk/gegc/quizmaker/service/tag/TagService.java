package uk.gegc.quizmaker.service.tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.TagDto;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;

import java.util.UUID;

public interface TagService {
    Page<TagDto> getTags(Pageable pageable);

    UUID createTag(CreateTagRequest request);

    TagDto getTagById(UUID tagId);

    TagDto updateTagById(UUID tagId, UpdateTagRequest request);

    void deleteTagById(UUID tagId);
}
