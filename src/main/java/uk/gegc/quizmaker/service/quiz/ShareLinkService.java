package uk.gegc.quizmaker.service.quiz;

import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkResponse;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;

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
    void recordShareLinkEventById(UUID shareLinkId, uk.gegc.quizmaker.model.quiz.ShareLinkEventType eventType,
                                  String userAgent, String ipAddress, String referrer);
    void recordShareLinkEventByToken(String token, uk.gegc.quizmaker.model.quiz.ShareLinkEventType eventType,
                                     String userAgent, String ipAddress, String referrer);
}


