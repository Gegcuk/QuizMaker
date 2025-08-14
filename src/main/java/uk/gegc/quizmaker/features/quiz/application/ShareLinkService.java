package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.ShareLinkDto;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLinkEventType;

import java.util.UUID;

public interface ShareLinkService {
    CreateShareLinkResponse createShareLink(UUID quizId, UUID userId, CreateShareLinkRequest request);
    ShareLinkDto validateToken(String token);
    void revokeShareLink(UUID shareLinkId, UUID userId);
    java.util.List<ShareLinkDto> getUserShareLinks(UUID userId);
    void recordShareLinkUsage(String tokenHash, String userAgent, String ipAddress);
    ShareLinkDto consumeOneTimeToken(String token);
    ShareLinkDto consumeOneTimeToken(String token, String userAgent, String ipAddress);
    void revokeActiveShareLinksForQuiz(UUID quizId);
    String hashToken(String token);
    void recordShareLinkEventById(UUID shareLinkId, ShareLinkEventType eventType,
                                  String userAgent, String ipAddress, String referrer);
    void recordShareLinkEventByToken(String token, ShareLinkEventType eventType,
                                     String userAgent, String ipAddress, String referrer);
}


