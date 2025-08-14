package uk.gegc.quizmaker.mapper;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.TagDto;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;
import uk.gegc.quizmaker.model.tag.Tag;

@Component
public class TagMapper {

    public Tag toEntity(CreateTagRequest request) {
        Tag tag = new Tag();
        tag.setName(request.name());
        tag.setDescription(request.description());
        return tag;
    }

    public void updateTag(Tag tag, UpdateTagRequest request) {
        tag.setName(request.name());
        tag.setDescription(request.description());
    }

    public TagDto toDto(Tag tag) {
        return new TagDto(
                tag.getId(),
                tag.getName(),
                tag.getDescription()
        );
    }

}
