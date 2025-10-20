package uk.gegc.quizmaker.features.quiz.application.command.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
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
public class QuizVisibilityServiceImpl implements QuizVisibilityService {

    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final QuizMapper quizMapper;

    @Override
    @Transactional
    public QuizDto setVisibility(String username, UUID quizId, Visibility visibility) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        accessPolicy.requireOwnerOrAny(user,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                PermissionName.QUIZ_MODERATE,
                PermissionName.QUIZ_ADMIN);

        boolean hasModerationPermissions = accessPolicy.hasAny(user, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN);

        if (visibility == Visibility.PUBLIC && !hasModerationPermissions) {
            throw new ForbiddenException("Only moderators can set quiz visibility to PUBLIC");
        }

        quiz.setVisibility(visibility);
        return quizMapper.toDto(quizRepository.save(quiz));
    }
}
