package uk.gegc.quizmaker.features.quiz.application.command.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizCommandServiceImpl implements QuizCommandService {

    private final UserRepository userRepository;
    private final TagRepository tagRepository;
    private final QuizMapper quizMapper;
    private final AppPermissionEvaluator appPermissionEvaluator;
    private final QuizRepository quizRepository;
    private final CategoryRepository categoryRepository;
    private final QuizDefaultsProperties quizDefaultsProperties;


    @Transactional
    @Override
    public UUID createQuiz(String username, CreateQuizRequest request) {
        User creator = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Category category = resolveCategoryFor(request);

        Set<Tag> tags = request.tagIds().stream()
                .map(id -> tagRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException("Tag " + id + " not found")))
                .collect(Collectors.toSet());

        Quiz quiz = quizMapper.toEntity(request, creator, category, tags);

        // Harden quiz creation: non-moderators cannot create PUBLIC/PUBLISHED quizzes directly
        boolean hasModerationPermissions = appPermissionEvaluator.hasPermission(creator, PermissionName.QUIZ_MODERATE)
                || appPermissionEvaluator.hasPermission(creator, PermissionName.QUIZ_ADMIN);

        if (!hasModerationPermissions) {
            // Force visibility to PRIVATE and status to DRAFT for non-moderators
            quiz.setVisibility(Visibility.PRIVATE);
            quiz.setStatus(QuizStatus.DRAFT);
        } else {
            // Moderators can set visibility, but enforce invariant: PUBLIC quizzes are published
            if (quiz.getVisibility() == Visibility.PUBLIC) {
                quiz.setStatus(QuizStatus.PUBLISHED);
            }
        }

        return quizRepository.save(quiz).getId();
    }

    private Category resolveCategoryFor(CreateQuizRequest request) {
        UUID defaultCategoryId = quizDefaultsProperties.getDefaultCategoryId();
        UUID requestedCategoryId = request.categoryId();

        if (requestedCategoryId == null) {
            log.info("Quiz '{}' creation request omitted categoryId; using default category {}", request.title(), defaultCategoryId);
            return categoryRepository.findById(defaultCategoryId)
                    .orElseThrow(() -> new IllegalStateException("Configured default category %s is missing".formatted(defaultCategoryId)));
        }

        return categoryRepository.findById(requestedCategoryId)
                .orElseGet(() -> {
                    log.warn("Quiz '{}' requested category {} which does not exist; using default {}", request.title(), requestedCategoryId, defaultCategoryId);
                    return categoryRepository.findById(defaultCategoryId)
                            .orElseThrow(() -> new IllegalStateException("Configured default category %s is missing".formatted(defaultCategoryId)));
                });
    }

}
