package uk.gegc.quizmaker.features.quizgroup.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.quizgroup.api.dto.CreateQuizGroupRequest;
import uk.gegc.quizmaker.features.quizgroup.api.dto.QuizGroupDto;
import uk.gegc.quizmaker.features.quizgroup.api.dto.UpdateQuizGroupRequest;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroup;
import uk.gegc.quizmaker.features.user.domain.model.User;

@Component
public class QuizGroupMapper {

    public QuizGroup toEntity(CreateQuizGroupRequest req, User owner, Document document) {
        QuizGroup group = new QuizGroup();
        group.setOwner(owner);
        group.setName(req.name());
        group.setDescription(req.description());
        group.setColor(req.color());
        group.setIcon(req.icon());
        group.setDocument(document);
        return group;
    }

    public void updateEntity(QuizGroup group, UpdateQuizGroupRequest req) {
        if (req.name() != null) {
            group.setName(req.name());
        }
        if (req.description() != null) {
            group.setDescription(req.description());
        }
        if (req.color() != null) {
            group.setColor(req.color());
        }
        if (req.icon() != null) {
            group.setIcon(req.icon());
        }
    }

    public QuizGroupDto toDto(QuizGroup group, long quizCount) {
        return new QuizGroupDto(
                group.getId(),
                group.getOwner() != null ? group.getOwner().getId() : null,
                group.getName(),
                group.getDescription(),
                group.getColor(),
                group.getIcon(),
                group.getDocument() != null ? group.getDocument().getId() : null,
                quizCount,
                group.getCreatedAt(),
                group.getUpdatedAt()
        );
    }
}
