package uk.gegc.quizmaker.service.quiz.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.ShareLinkDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.quiz.*;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.quiz.ShareLinkAnalyticsRepository;
import uk.gegc.quizmaker.repository.quiz.ShareLinkRepository;
import uk.gegc.quizmaker.repository.quiz.ShareLinkUsageRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.security.AppPermissionEvaluator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShareLinkServiceImpl implements uk.gegc.quizmaker.service.quiz.ShareLinkService {

    private final ShareLinkRepository shareLinkRepository;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final ShareLinkUsageRepository usageRepository;
    private final ShareLinkAnalyticsRepository analyticsRepository;
    private final AppPermissionEvaluator appPermissionEvaluator;

    @Value("${quizmaker.share-links.token-pepper:}")
    private String tokenPepper;

    @Value("${quizmaker.share-links.default-expiry-hours:168}")
    private long defaultExpiryHours;

    @Value("${quizmaker.share-links.max-expiry-hours:720}")
    private long maxExpiryHours;

    @PostConstruct
    void checkPepperConfigured() {
        if (tokenPepper == null || tokenPepper.isBlank()) {
            throw new IllegalStateException("quizmaker.share-links.token-pepper is not configured");
        }
    }

    @Override
    @Transactional
    public CreateShareLinkResponse createShareLink(UUID quizId, UUID userId, CreateShareLinkRequest request) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " not found"));

        // Authorization: owner or admin/moderator
        if (!(quiz.getCreator() != null && userId.equals(quiz.getCreator().getId())
                || appPermissionEvaluator.hasPermission(creator, PermissionName.QUIZ_ADMIN)
                || appPermissionEvaluator.hasPermission(creator, PermissionName.QUIZ_MODERATE))) {
            throw new uk.gegc.quizmaker.exception.ForbiddenException("Not allowed to share this quiz");
        }

        // Generate server-side token (URL-safe base64) and store peppered hash
        String token = newToken();
        String tokenHash = sha256Hex(tokenPepper + token);

        ShareLink link = new ShareLink();
        link.setQuiz(quiz);
        link.setCreatedBy(creator);
        link.setTokenHash(tokenHash);
        link.setScope(request.scope() != null ? request.scope() : ShareLinkScope.QUIZ_VIEW);
        // Expiry validation & TTL policy
        Instant now = Instant.now();
        Instant exp = request.expiresAt() != null ? request.expiresAt() : now.plus(Duration.ofHours(defaultExpiryHours));
        if (exp.isBefore(now)) {
            throw new uk.gegc.quizmaker.exception.ValidationException("Expiry must be in the future");
        }
        Instant cap = now.plus(Duration.ofHours(maxExpiryHours));
        if (exp.isAfter(cap)) {
            exp = cap;
        }
        link.setExpiresAt(exp);
        link.setOneTime(Boolean.TRUE.equals(request.oneTime()));
        link.setRevokedAt(null);

        ShareLink saved = shareLinkRepository.save(link);
        ShareLinkDto dto = new ShareLinkDto(
                saved.getId(),
                quiz.getId(),
                creator.getId(),
                saved.getScope(),
                saved.getExpiresAt(),
                saved.isOneTime(),
                saved.getRevokedAt(),
                saved.getCreatedAt()
        );
        return new CreateShareLinkResponse(dto, token);
    }

    @Override
    @Transactional(readOnly = true)
    public ShareLinkDto validateToken(String token) {
        String tokenHash = sha256Hex(tokenPepper + token);
        ShareLink link = shareLinkRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or unknown token"));

        // Expiry check
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(java.time.Instant.now())) {
            throw new uk.gegc.quizmaker.exception.ValidationException("Token expired");
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

    private static final SecureRandom RNG = new SecureRandom();
    private static String newToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Override
    @Transactional
    public void revokeShareLink(UUID shareLinkId, UUID userId) {
        ShareLink link = shareLinkRepository.findById(shareLinkId)
                .orElseThrow(() -> new ResourceNotFoundException("ShareLink " + shareLinkId + " not found"));

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " not found"));

        // Owner or admin/moderator can revoke
        if (!(link.getCreatedBy() != null && link.getCreatedBy().getId().equals(userId)
                || appPermissionEvaluator.hasPermission(actor, PermissionName.QUIZ_ADMIN)
                || appPermissionEvaluator.hasPermission(actor, PermissionName.QUIZ_MODERATE))) {
            throw new uk.gegc.quizmaker.exception.ForbiddenException("Not allowed to revoke this share link");
        }

        if (link.getRevokedAt() != null) {
            // idempotent: already revoked
            return;
        }
        link.setRevokedAt(java.time.Instant.now());
        shareLinkRepository.save(link);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ShareLinkDto> getUserShareLinks(UUID userId) {
        java.util.List<ShareLink> links = shareLinkRepository.findAllByCreatedBy_IdOrderByCreatedAtDesc(userId);
        return links.stream()
                .map(l -> new ShareLinkDto(
                        l.getId(),
                        l.getQuiz().getId(),
                        l.getCreatedBy().getId(),
                        l.getScope(),
                        l.getExpiresAt(),
                        l.isOneTime(),
                        l.getRevokedAt(),
                        l.getCreatedAt()
                ))
                .toList();
    }

    @Override
    @Transactional
    public void recordShareLinkUsage(String tokenHash, String userAgent, String ipAddress) {
        ShareLink link = shareLinkRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or unknown token"));

        ShareLinkUsage usage = new ShareLinkUsage();
        usage.setShareLink(link);
        String ua = userAgent == null ? null : (userAgent.length() > 256 ? userAgent.substring(0, 256) : userAgent);
        usage.setUserAgent(ua);
        String bucket = LocalDate.now(ZoneOffset.UTC).toString();
        String ip = ipAddress == null ? "" : ipAddress;
        usage.setIpHash(sha256Hex(tokenPepper + ":" + bucket + ":" + ip));
        usageRepository.save(usage);
    }

    @Override
    @Transactional
    public void recordShareLinkEventById(UUID shareLinkId, ShareLinkEventType eventType,
                                         String userAgent, String ipAddress, String referrer) {
        ShareLink link = shareLinkRepository.findById(shareLinkId)
                .orElseThrow(() -> new ResourceNotFoundException("ShareLink " + shareLinkId + " not found"));
        persistAnalytics(link, eventType, userAgent, ipAddress, referrer);
    }

    @Override
    @Transactional
    public void recordShareLinkEventByToken(String token, ShareLinkEventType eventType,
                                            String userAgent, String ipAddress, String referrer) {
        String tokenHash = sha256Hex(tokenPepper + token);
        ShareLink link = shareLinkRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or unknown token"));
        persistAnalytics(link, eventType, userAgent, ipAddress, referrer);
    }

    private void persistAnalytics(ShareLink link, ShareLinkEventType eventType,
                                  String userAgent, String ipAddress, String referrer) {
        String ua = userAgent == null ? null : (userAgent.length() > 256 ? userAgent.substring(0, 256) : userAgent);
        String bucket = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String ip = ipAddress == null ? "" : ipAddress;
        String ipHash = sha256Hex(tokenPepper + ":" + bucket + ":" + ip);
        String ref = referrer == null ? null : (referrer.length() > 512 ? referrer.substring(0, 512) : referrer);

        ShareLinkAnalytics analytics = ShareLinkAnalytics.builder()
                .shareLink(link)
                .eventType(eventType)
                .ipHash(ipHash)
                .userAgent(ua)
                .dateBucket(bucket)
                .referrer(ref)
                .build();
        analyticsRepository.save(analytics);
    }

    @Override
    @Transactional
    public ShareLinkDto consumeOneTimeToken(String token) {
        return consumeOneTimeToken(token, null, null);
    }

    @Override
    @Transactional
    public ShareLinkDto consumeOneTimeToken(String token, String userAgent, String ipAddress) {
        String tokenHash = sha256Hex(tokenPepper + token);
        
        // First, try to find the token (including revoked ones)
        ShareLink link = shareLinkRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or unknown token"));

        if (!link.isOneTime()) {
            // Not one-time: just return dto
            return new ShareLinkDto(
                    link.getId(), link.getQuiz().getId(), link.getCreatedBy().getId(), link.getScope(),
                    link.getExpiresAt(), link.isOneTime(), link.getRevokedAt(), link.getCreatedAt());
        }

        // Check if already revoked
        if (link.getRevokedAt() != null) {
            throw new uk.gegc.quizmaker.exception.ShareLinkAlreadyUsedException("Token already used");
        }

        // Expiry check
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(java.time.Instant.now())) {
            throw new uk.gegc.quizmaker.exception.ValidationException("Token expired");
        }

        // Record usage before consuming (for one-time tokens)
        if (userAgent != null || ipAddress != null) {
            recordShareLinkUsage(tokenHash, userAgent, ipAddress);
        }

        // Atomic consumption via guarded update
        Instant now2 = Instant.now();
        int updated = shareLinkRepository.consumeOneTime(tokenHash, now2);
        if (updated == 0) {
            throw new uk.gegc.quizmaker.exception.ShareLinkAlreadyUsedException("Token already used");
        }
        // reflect state in returned DTO
        link.setRevokedAt(now2);

        return new ShareLinkDto(
                link.getId(), link.getQuiz().getId(), link.getCreatedBy().getId(), link.getScope(),
                link.getExpiresAt(), link.isOneTime(), link.getRevokedAt(), link.getCreatedAt());
    }

    @Override
    public String hashToken(String token) {
        return sha256Hex(tokenPepper + token);
    }

    @Override
    @Transactional
    public void revokeActiveShareLinksForQuiz(UUID quizId) {
        java.util.List<ShareLink> links = shareLinkRepository.findAllByQuiz_IdAndRevokedAtIsNull(quizId);
        if (links.isEmpty()) {
            return; // idempotent
        }
        java.time.Instant now = java.time.Instant.now();
        for (ShareLink l : links) {
            l.setRevokedAt(now);
        }
        shareLinkRepository.saveAll(links);
    }
}


