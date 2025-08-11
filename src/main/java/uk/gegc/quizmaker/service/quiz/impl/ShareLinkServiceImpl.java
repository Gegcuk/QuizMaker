package uk.gegc.quizmaker.service.quiz.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.ShareLink;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.quiz.ShareLinkRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShareLinkServiceImpl implements uk.gegc.quizmaker.service.quiz.ShareLinkService {

    private final ShareLinkRepository shareLinkRepository;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public ShareLinkDto createShareLink(UUID quizId, UUID userId, CreateShareLinkRequest request) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " not found"));

        // Generate server-side token and store peppered hash
        String token = HexFormat.of().withUpperCase().formatHex(java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        String pepper = System.getenv("TOKEN_PEPPER_SECRET");
        String tokenHash = sha256Hex((pepper != null ? pepper : "") + token);

        ShareLink link = new ShareLink();
        link.setQuiz(quiz);
        link.setCreatedBy(creator);
        link.setTokenHash(tokenHash);
        link.setScope(request.scope() != null ? request.scope() : ShareLinkScope.QUIZ_VIEW);
        link.setExpiresAt(request.expiresAt());
        link.setOneTime(Boolean.TRUE.equals(request.oneTime()));
        link.setRevokedAt(null);

        ShareLink saved = shareLinkRepository.save(link);
        return new ShareLinkDto(
                saved.getId(),
                quiz.getId(),
                creator.getId(),
                saved.getScope(),
                saved.getExpiresAt(),
                saved.isOneTime(),
                saved.getRevokedAt(),
                saved.getCreatedAt()
        );
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}


