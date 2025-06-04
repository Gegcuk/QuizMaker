package uk.gegc.quizmaker.service.quiz.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.quiz.QuizService;

import java.util.Optional;
import java.util.Set;
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
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public UUID createQuiz(String username, CreateQuizRequest request) {
        User creator = userRepository.findByUsername(username)
                .or(()->userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Category category = Optional.ofNullable(request.categoryId())
                .flatMap(categoryRepository::findById)
                .orElseGet(() -> categoryRepository.findByName("General")
                        .orElseThrow(() -> new ResourceNotFoundException("Default category missing")));

        Set<Tag> tags = request.tagIds().stream()
                .map(id -> tagRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException("Tag " + id + " not found")))
                .collect(Collectors.toSet());

        Quiz quiz = quizMapper.toEntity(request, creator, category, tags);
        return quizRepository.save(quiz).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getQuizzes(Pageable pageable,
                                    QuizSearchCriteria criteria) {
        return quizRepository.findAll(pageable)
                .map(quizMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public QuizDto getQuizById(UUID id) {
        var quiz = quizRepository.findByIdWithTags(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + id + " not found"));
        return quizMapper.toDto(quiz);
    }

    @Override
    public QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest req) {
        Quiz quiz = quizRepository.findByIdWithTags(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + id + " not found"));

        Category category = null;
        if (req.categoryId() != null) {
            category = categoryRepository.findById(req.categoryId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Category " + req.categoryId() + " not found"));
        } else {
            category = quiz.getCategory();  // keep existing category when none provided
        }

        Set<Tag> tags = Optional.ofNullable(req.tagIds())
                .map(ids -> ids.stream()
                        .map(tagId -> tagRepository.findById(tagId)
                                .orElseThrow(() ->
                                        new ResourceNotFoundException("Tag " + tagId + " not found")))
                        .collect(Collectors.toSet()))
                .orElse(null);

        quizMapper.updateEntity(quiz, req, category, tags);
        return quizMapper.toDto(quizRepository.save(quiz));
    }

    @Override
    public void deleteQuizById(String username, UUID id) {
        if (!quizRepository.existsById(id)) {
            throw new ResourceNotFoundException("Quiz " + id + " not found");
        }
        quizRepository.deleteById(id);
    }

    @Override
    public void addQuestionToQuiz(String username, UUID quizId, UUID questionId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        var question = questionRepository.findById(questionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Question " + questionId + " not found"));
        quiz.getQuestions().add(question);
        quizRepository.save(quiz);
    }

    @Override
    public void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.getQuestions().removeIf(q -> q.getId().equals(questionId));
        quizRepository.save(quiz);
    }

    @Override
    public void addTagToQuiz(String username, UUID quizId, UUID tagId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        var tag = tagRepository.findById(tagId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Tag " + tagId + " not found"));
        quiz.getTags().add(tag);
        quizRepository.save(quiz);
    }

    @Override
    public void removeTagFromQuiz(String username, UUID quizId, UUID tagId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.getTags().removeIf(t -> t.getId().equals(tagId));
        quizRepository.save(quiz);
    }

    @Override
    public void changeCategory(String username, UUID quizId, UUID categoryId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        var cat = categoryRepository.findById(categoryId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Category " + categoryId + " not found"));
        quiz.setCategory(cat);
        quizRepository.save(quiz);
    }

    @Override
    public QuizDto setVisibility(String name, UUID quizId, Visibility visibility) {

        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.setVisibility(visibility);

        return quizMapper.toDto(quizRepository.save(quiz)) ;
    }

    @Override
    public QuizDto setStatus(String username, UUID quizId, QuizStatus status) {
        Quiz quiz = quizRepository.findByIdWithQuestions(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        if (status == QuizStatus.PUBLISHED && quiz.getQuestions().isEmpty()) {
            throw new IllegalArgumentException("Cannot publish quiz without questions");
        }

        quiz.setStatus(status);
        return quizMapper.toDto(quizRepository.save(quiz));
    }
}