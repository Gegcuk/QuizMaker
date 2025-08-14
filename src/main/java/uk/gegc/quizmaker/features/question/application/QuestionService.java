package uk.gegc.quizmaker.features.question.application;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionDto;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;

import java.util.UUID;

public interface QuestionService {
    UUID createQuestion(String username, CreateQuestionRequest questionDto);

    Page<QuestionDto> listQuestions(UUID quizId, Pageable pageable);

    QuestionDto getQuestion(UUID questionId);

    QuestionDto updateQuestion(String username, UUID questionId, UpdateQuestionRequest updateQuestionRequest);

    void deleteQuestion(String username, UUID questionId);
}
