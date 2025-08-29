package uk.gegc.quizmaker.features.tag.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.tag.api.dto.CreateTagRequest;
import uk.gegc.quizmaker.features.tag.api.dto.TagDto;
import uk.gegc.quizmaker.features.tag.api.dto.UpdateTagRequest;
import uk.gegc.quizmaker.features.tag.application.TagService;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.tag.infra.mapping.TagMapper;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {
    private final TagRepository tagRepository;
    private final TagMapper tagMapper;

    @Override
    public Page<TagDto> getTags(Pageable pageable) {
        return tagRepository.findAll(pageable).map(tagMapper::toDto);
    }

    @Override
    public UUID createTag(String username, CreateTagRequest request) {
        var tag = tagMapper.toEntity(request);
        return tagRepository.save(tag).getId();

    }

    @Override
    @Transactional(readOnly = true)
    public TagDto getTagById(UUID tagId) {
        var tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + tagId + " not found"));
        return tagMapper.toDto(tag);
    }

    @Override
    public TagDto updateTagById(String username, UUID tagId, UpdateTagRequest request) {
        var existingTag = tagRepository.findById(tagId)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + tagId + " not found"));
        tagMapper.updateTag(existingTag, request);
        return tagMapper.toDto(tagRepository.save(existingTag));
    }

    @Override
    public void deleteTagById(String username, UUID tagId) {
        if (!tagRepository.existsById(tagId)) {
            throw new ResourceNotFoundException("Tag " + tagId + " not found");
        }
        tagRepository.deleteById(tagId);
    }
}

