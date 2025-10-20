package uk.gegc.quizmaker.features.quiz.application.command.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizRelationServiceImpl implements QuizRelationService {

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final TagRepository tagRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;

    @Override
    @Transactional
    public void addQuestionToQuiz(String username, UUID quizId, UUID questionId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));

        User user = requireUser(username);
        accessPolicy.requireOwnerOrAny(user,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                PermissionName.QUIZ_MODERATE,
                PermissionName.QUIZ_ADMIN);

        quiz.getQuestions().add(question);
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User user = requireUser(username);
        accessPolicy.requireOwnerOrAny(user,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                PermissionName.QUIZ_MODERATE,
                PermissionName.QUIZ_ADMIN);

        quiz.getQuestions().removeIf(question -> question.getId().equals(questionId));
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void addTagToQuiz(String username, UUID quizId, UUID tagId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + tagId + " not found"));

        User user = requireUser(username);
        accessPolicy.requireOwnerOrAny(user,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                PermissionName.QUIZ_MODERATE,
                PermissionName.QUIZ_ADMIN);

        quiz.getTags().add(tag);
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void removeTagFromQuiz(String username, UUID quizId, UUID tagId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User user = requireUser(username);
        accessPolicy.requireOwnerOrAny(user,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                PermissionName.QUIZ_MODERATE,
                PermissionName.QUIZ_ADMIN);

        quiz.getTags().removeIf(tag -> tag.getId().equals(tagId));
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void changeCategory(String username, UUID quizId, UUID categoryId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category " + categoryId + " not found"));

        User user = requireUser(username);
        accessPolicy.requireOwnerOrAny(user,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                PermissionName.QUIZ_MODERATE,
                PermissionName.QUIZ_ADMIN);

        quiz.setCategory(category);
        quizRepository.save(quiz);
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));
    }
}
