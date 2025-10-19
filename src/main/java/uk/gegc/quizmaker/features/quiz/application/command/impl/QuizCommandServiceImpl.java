package uk.gegc.quizmaker.features.quiz.application.command.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.UpdateQuizRequest;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
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
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.*;
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
    private final QuizHashCalculator quizHashCalculator;


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

    @Transactional
    @Override
    public QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest req) {
        Quiz quiz = quizRepository.findByIdWithTags(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + id + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Ownership check: user must be the creator or have moderation/admin permissions
        if (!(quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId())
                || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))) {
            throw new ForbiddenException("Not allowed to update this quiz");
        }

        // Moderation: block edits while pending review and auto-revert to DRAFT if editing pending
        if (quiz.getStatus() == QuizStatus.PENDING_REVIEW) {
            // Auto-revert to DRAFT on any edits of PENDING_REVIEW quizzes
            quiz.setStatus(QuizStatus.DRAFT);
        }

        Category category;
        if (req.categoryId() != null) {
            category = categoryRepository.findById(req.categoryId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Category " + req.categoryId() + " not found"));
        } else {
            category = quiz.getCategory();
        }

        Set<Tag> tags = Optional.ofNullable(req.tagIds())
                .map(ids -> ids.stream()
                        .map(tagId -> tagRepository.findById(tagId)
                                .orElseThrow(() ->
                                        new ResourceNotFoundException("Tag " + tagId + " not found")))
                        .collect(Collectors.toSet()))
                .orElse(null);

        String beforeContentHash = quiz.getContentHash();

        quizMapper.updateEntity(quiz, req, category, tags);

        // Recompute hashes on save
        QuizDto draftDto = quizMapper.toDto(quiz);
        String newContentHash = quizHashCalculator.calculateContentHash(draftDto);
        String newPresentationHash = quizHashCalculator.calculatePresentationHash(draftDto);
        quiz.setContentHash(newContentHash);
        quiz.setPresentationHash(newPresentationHash);

        // If published and content hash changes, auto transition to PENDING_REVIEW
        if (beforeContentHash != null
                && quiz.getStatus() == QuizStatus.PUBLISHED
                && !beforeContentHash.equalsIgnoreCase(newContentHash)) {
            quiz.setStatus(QuizStatus.PENDING_REVIEW);
            // clear review outcome fields when moving to pending
            quiz.setReviewedAt(null);
            quiz.setReviewedBy(null);
            quiz.setRejectionReason(null);
        }

        return quizMapper.toDto(quizRepository.save(quiz));
    }

    @Transactional
    @Override
    public void deleteQuizById(String username, UUID id) {
        Quiz quiz = quizRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + id + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Ownership check: user must be the creator or have admin permissions
        if (!(quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId())
                || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))) {
            throw new ForbiddenException("Not allowed to delete this quiz");
        }

        quizRepository.deleteById(id);
    }

    @Transactional
    @Override
    public void deleteQuizzesByIds(String username, List<UUID> quizIds) {
        if (quizIds == null || quizIds.isEmpty()) {
            return;
        }

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        boolean hasAdminPermissions = appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN);

        var existing = quizRepository.findAllById(quizIds);
        List<Quiz> quizzesToDelete = new ArrayList<>();

        for (Quiz quiz : existing) {
            // Check ownership or admin permissions for each quiz
            boolean isOwner = quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId());
            if (isOwner || hasAdminPermissions) {
                quizzesToDelete.add(quiz);
            }
            // Silently ignore quizzes the user doesn't have permission to delete
        }

        if (!quizzesToDelete.isEmpty()) {
            quizRepository.deleteAll(quizzesToDelete);
        }
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
