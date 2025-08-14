package uk.gegc.quizmaker.features.tag.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.tag.api.dto.CreateTagRequest;
import uk.gegc.quizmaker.features.tag.api.dto.TagDto;
import uk.gegc.quizmaker.features.tag.api.dto.UpdateTagRequest;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

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
