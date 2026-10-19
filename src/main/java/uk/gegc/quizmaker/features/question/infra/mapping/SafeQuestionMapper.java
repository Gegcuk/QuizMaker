package uk.gegc.quizmaker.features.question.infra.mapping;

import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentAvailability;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.api.dto.QuestionForAttemptDto;
import uk.gegc.quizmaker.features.question.application.SafeQuestionContentBuilder;
import uk.gegc.quizmaker.features.question.domain.model.Question;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SafeQuestionMapper {

    private final SafeQuestionContentBuilder contentBuilder;
    private final QuestionMediaResolver questionMediaResolver;

    public QuestionForAttemptDto toSafeDto(Question question) {
        QuestionForAttemptDto dto = mapContent(question);
        dto.setAttachment(questionMediaResolver.resolveAttachment(question.getAttachmentAssetId()));
        dto.setSafeContent(questionMediaResolver.resolveMediaInContent(dto.getSafeContent()));
        validateResolved(dto);
        return dto;
    }

    private QuestionForAttemptDto mapContent(Question question) {
        QuestionForAttemptDto dto = new QuestionForAttemptDto();
        dto.setId(question.getId());
        dto.setType(question.getType());
        dto.setDifficulty(question.getDifficulty());
        dto.setQuestionText(question.getQuestionText());
        dto.setHint(question.getHint());
        dto.setAttachmentUrl(question.getAttachmentUrl());

        try {
            dto.setSafeContent(contentBuilder.buildSafeContent(question.getType(), question.getContent()));
        } catch (QuestionContractException ex) {
            dto.setContentAvailability(QuestionContentAvailability.UNAVAILABLE);
            dto.setContentProblem(QuestionContracts.unavailableProblem());
        }
        return dto;
    }

    public List<QuestionForAttemptDto> toSafeDtoList(List<Question> questions) {
        var media = questionMediaResolver.resolveQuestions(questions);
        return questions.stream().map(question -> {
            var dto = mapContent(question);
            dto.setAttachment(question.getAttachmentAssetId() == null ? null : media.get(question.getAttachmentAssetId()));
            dto.setSafeContent(questionMediaResolver.resolveMediaInContent(dto.getSafeContent(), media));
            validateResolved(dto);
            return dto;
        }).collect(Collectors.toList());
    }
    private void validateResolved(QuestionForAttemptDto dto) {
        if (dto.getSafeContent() == null) return;
        try {
            QuestionContracts.validate(dto.getType(),
                    QuestionContracts.View.SAFE, dto.getSafeContent());
        } catch (QuestionContractException ex) {
            dto.setSafeContent(null);
            dto.setContentAvailability(QuestionContentAvailability.UNAVAILABLE);
            dto.setContentProblem(QuestionContracts.unavailableProblem());
        }
    }

} 
