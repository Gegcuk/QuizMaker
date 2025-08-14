package uk.gegc.quizmaker.features.question.infra.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionForAttemptDto;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.application.SafeQuestionContentBuilder;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SafeQuestionMapper {

    private final SafeQuestionContentBuilder contentBuilder;

    public QuestionForAttemptDto toSafeDto(Question question) {
        QuestionForAttemptDto dto = new QuestionForAttemptDto();
        dto.setId(question.getId());
        dto.setType(question.getType());
        dto.setDifficulty(question.getDifficulty());
        dto.setQuestionText(question.getQuestionText());
        dto.setHint(question.getHint());
        dto.setAttachmentUrl(question.getAttachmentUrl());

        // 🔒 Build safe content without answers
        dto.setSafeContent(contentBuilder.buildSafeContent(
                question.getType(),
                question.getContent()
        ));

        return dto;
    }

    public List<QuestionForAttemptDto> toSafeDtoList(List<Question> questions) {
        return questions.stream()
                .map(this::toSafeDto)
                .collect(Collectors.toList());
    }
} 