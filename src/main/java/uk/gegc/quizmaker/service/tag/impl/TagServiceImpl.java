package uk.gegc.quizmaker.service.tag.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.TagDto;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.TagMapper;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.service.tag.TagService;

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

