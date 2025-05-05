package uk.gegc.quizmaker.service.quiz.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.dto.quiz.QuizSearchCriteria;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.QuizMapper;
import uk.gegc.quizmaker.model.quiz.Tag;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.CategoryRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.quiz.TagRepository;
import uk.gegc.quizmaker.service.quiz.QuizService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class QuizServiceImpl implements QuizService {
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final TagRepository tagRepository;
    private final CategoryRepository categoryRepository;
    private final QuizMapper quizMapper;

    @Override
    public UUID createQuiz(CreateQuizRequest request) {
        var category = Optional.ofNullable(request.categoryId())
                .flatMap(categoryRepository::findById)
                .orElseGet(() -> categoryRepository.findByName("General")
                        .orElseThrow(() -> new ResourceNotFoundException("Default category missing")));

        var tags = request.tagIds().stream()
                .map(id -> tagRepository.findById(id)
                        .orElseThrow(()-> new ResourceNotFoundException("Tag " + id + " not found")))
                .toList();

        var quiz = quizMapper.toEntity(request, category, tags);
        return quizRepository.save(quiz).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria quizSearchCriteria) {
        return quizRepository.findAll(pageable).map(quizMapper::toDto);
    }


    @Override
    @Transactional(readOnly = true)
    public QuizDto getQuizById(UUID id) {
        var quiz = quizRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + id + " not found"));
        return quizMapper.toDto(quiz);
    }


    @Override
    public QuizDto updateQuiz(UUID id, UpdateQuizRequest updateQuizRequest) {
        var quiz = quizRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + id + " not found"));

        var category = Optional.ofNullable(updateQuizRequest.categoryId())
                .flatMap(categoryRepository::findById)
                .orElse(null);

        List<Tag> tags = Optional.ofNullable(updateQuizRequest.tagIds())
                .map(ids -> ids.stream()
                        .map(tagId -> tagRepository.findById(tagId)
                                .orElseThrow(() -> new ResourceNotFoundException("Tag " + tagId + " not found")))
                        .collect(Collectors.toList()))
                .orElse(null);

        quizMapper.updateEntity(quiz, updateQuizRequest, category, tags);
        return quizMapper.toDto(quizRepository.save(quiz));
    }

    @Override
    public void deleteQuizById(UUID id) {
        quizRepository.deleteById(id);
    }

    @Override
    public void addQuestionToQuiz(UUID quizId, UUID questionId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz not found"));
        var question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found"));
        quiz.getQuestions().add(question);
        quizRepository.save(quiz);
    }

    @Override
    public void removeQuestionFromQuiz(UUID quizId, UUID questionId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.getQuestions().removeIf(q -> q.getId().equals(questionId));
        quizRepository.save(quiz);
    }


    @Override
    public void addTagToQuiz(UUID quizId, UUID tagId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        var tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + tagId + " not found"));
        quiz.getTags().add(tag);
        quizRepository.save(quiz);
    }

    @Override
    public void removeTagFromQuiz(UUID quizId, UUID tagId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.getTags().removeIf(tag -> tag.getId().equals(tagId));
        quizRepository.save(quiz);
    }

    @Override
    public void changeCategory(UUID quizId, UUID categoryId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz not found"));
        var cat = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        quiz.setCategory(cat);
        quizRepository.save(quiz);
    }
}
