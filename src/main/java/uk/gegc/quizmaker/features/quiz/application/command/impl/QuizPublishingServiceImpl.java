package uk.gegc.quizmaker.features.quiz.application.command.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.validation.QuizPublishValidator;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizPublishingServiceImpl implements QuizPublishingService {

    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final QuizMapper quizMapper;
    private final AccessPolicy accessPolicy;
    private final QuizPublishValidator publishValidator;

    @Override
    @Transactional
    public QuizDto setStatus(String username, UUID quizId, QuizStatus status) {
        Quiz quiz = quizRepository.findByIdWithQuestions(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        accessPolicy.requireOwnerOrAny(user,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                PermissionName.QUIZ_MODERATE,
                PermissionName.QUIZ_ADMIN);

        boolean hasModerationPermissions = accessPolicy.hasAny(user, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN);

        if (status == QuizStatus.PUBLISHED && !hasModerationPermissions) {
            if (quiz.getVisibility() == Visibility.PUBLIC) {
                throw new ForbiddenException("Only moderators can publish PUBLIC quizzes. Set visibility to PRIVATE first or submit for moderation.");
            }
        }

        if (status == QuizStatus.PUBLISHED) {
            publishValidator.ensurePublishable(quiz);
        }

        quiz.setStatus(status);
        return quizMapper.toDto(quizRepository.save(quiz));
    }
}
