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

    @Override
    @Transactional(readOnly = true)
    public ShareLinkDto validateToken(String token) {
        String pepper = System.getenv("TOKEN_PEPPER_SECRET");
        String tokenHash = sha256Hex((pepper != null ? pepper : "") + token);
        ShareLink link = shareLinkRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or unknown token"));

        // Expiry check
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(java.time.Instant.now())) {
            throw new uk.gegc.quizmaker.exception.ValidationException("Token expired");
        }

        // Revoked check
        if (link.getRevokedAt() != null) {
            throw new uk.gegc.quizmaker.exception.ValidationException("Token revoked");
        }

        return new ShareLinkDto(
                link.getId(),
                link.getQuiz().getId(),
                link.getCreatedBy().getId(),
                link.getScope(),
                link.getExpiresAt(),
                link.isOneTime(),
                link.getRevokedAt(),
                link.getCreatedAt()
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

    @Override
    @Transactional
    public void revokeShareLink(UUID shareLinkId, UUID userId) {
        ShareLink link = shareLinkRepository.findById(shareLinkId)
                .orElseThrow(() -> new ResourceNotFoundException("ShareLink " + shareLinkId + " not found"));

        // Only owner or admin-like flows would be allowed; with no auth service here, enforce creator match
        if (link.getCreatedBy() == null || !link.getCreatedBy().getId().equals(userId)) {
            throw new uk.gegc.quizmaker.exception.ForbiddenException("Not allowed to revoke this share link");
        }

        if (link.getRevokedAt() != null) {
            // idempotent: already revoked
            return;
        }
        link.setRevokedAt(java.time.Instant.now());
        shareLinkRepository.save(link);
    }
}


