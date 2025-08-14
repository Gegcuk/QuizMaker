package uk.gegc.quizmaker.service.question;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionDto;
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;

import java.util.UUID;

public interface QuestionService {
    UUID createQuestion(String username, CreateQuestionRequest questionDto);

    Page<QuestionDto> listQuestions(UUID quizId, Pageable pageable);

    QuestionDto getQuestion(UUID questionId);

    QuestionDto updateQuestion(String username, UUID questionId, UpdateQuestionRequest updateQuestionRequest);

    void deleteQuestion(String username, UUID questionId);
}
