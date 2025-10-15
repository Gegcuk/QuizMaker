package uk.gegc.quizmaker.features.quiz.application.export;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.application.CorrectAnswerExtractor;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.export.AnswerKeyEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an answer key from exported questions by reusing the CorrectAnswerExtractor.
 */
@Component
@RequiredArgsConstructor
public class AnswerKeyBuilder {

    private final CorrectAnswerExtractor correctAnswerExtractor;

    public List<AnswerKeyEntry> build(List<QuestionExportDto> questions) {
        List<AnswerKeyEntry> entries = new ArrayList<>();
        if (questions == null || questions.isEmpty()) {
            return entries;
        }

        int index = 1;
        for (QuestionExportDto q : questions) {
            // Create a lightweight Question surrogate to reuse extractor logic
            uk.gegc.quizmaker.features.question.domain.model.Question surrogate = new uk.gegc.quizmaker.features.question.domain.model.Question();
            surrogate.setId(q.id());
            surrogate.setType(q.type());
            surrogate.setDifficulty(q.difficulty());
            surrogate.setQuestionText(q.questionText());
            surrogate.setContent(q.content() != null ? q.content().toString() : "{}");
            surrogate.setHint(q.hint());
            surrogate.setExplanation(q.explanation());
            surrogate.setAttachmentUrl(q.attachmentUrl());

            JsonNode normalized = correctAnswerExtractor.extractCorrectAnswer(surrogate);
            entries.add(new AnswerKeyEntry(index++, q.id(), q.type(), normalized));
        }

        return entries;
    }
}


