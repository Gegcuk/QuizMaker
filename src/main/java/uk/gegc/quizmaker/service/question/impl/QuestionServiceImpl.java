package uk.gegc.quizmaker.service.question.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionDto;
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.QuestionMapper;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.service.question.QuestionService;
import uk.gegc.quizmaker.service.question.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.service.question.handler.QuestionHandler;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {
    private final QuestionRepository questionRepository;
    private final QuizRepository quizRepository;
    private final TagRepository tagRepository;
    private final QuestionHandlerFactory handlerFactory;

    @Override
    public UUID createQuestion(String username, CreateQuestionRequest questionDto) {

        QuestionHandler questionHandler = handlerFactory.getHandler(questionDto.getType());
        questionHandler.validateContent(questionDto);

        List<Quiz> quizzes = Optional.ofNullable(questionDto.getQuizIds())
                .orElse(Collections.emptyList())
                .stream()
                .map(id -> quizRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Quiz " + id + " not found")))
                .toList();
        List<Tag> tags = Optional.ofNullable(questionDto.getTagIds())
                .orElse(Collections.emptyList())
                .stream()
                .map(id -> tagRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " not found")))
                .toList();

        Question question = QuestionMapper.toEntity(questionDto, quizzes, tags);
        questionRepository.save(question);

        return question.getId();
    }

    @Override
    public Page<QuestionDto> listQuestions(UUID quizId, Pageable page) {
        Page<Question> retrievedPage = (quizId != null)
                ? questionRepository.findAllByQuizId_Id(quizId, page)
                : questionRepository.findAll(page);

        return retrievedPage.map(QuestionMapper::toDto);
    }

    @Override
    public QuestionDto getQuestion(UUID questionId) {
        Question q = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));
        return QuestionMapper.toDto(q);
    }

    @Override
    public QuestionDto updateQuestion(String username, UUID questionId, UpdateQuestionRequest request) {
        QuestionHandler questionHandler = handlerFactory.getHandler(request.getType());
        questionHandler.validateContent(request);

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));

        List<Quiz> quizzes = Optional.ofNullable(request.getQuizIds())
                .orElse(Collections.emptyList())
                .stream()
                .map(id -> quizRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Quiz " + id + " not found")))
                .toList();
        List<Tag> tags = Optional.ofNullable(request.getTagIds())
                .orElse(Collections.emptyList())
                .stream()
                .map(id -> tagRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " not found")))
                .toList();

        QuestionMapper.updateEntity(question, request, quizzes, tags);
        Question updatedQuestion = questionRepository.save(question);

        return QuestionMapper.toDto(updatedQuestion);
    }

    @Override
    public void deleteQuestion(String username, UUID questionId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));
        questionRepository.delete(question);
    }
}
