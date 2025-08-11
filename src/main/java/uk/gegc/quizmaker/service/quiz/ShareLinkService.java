package uk.gegc.quizmaker.service.quiz;

import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;

import java.util.UUID;

public interface ShareLinkService {
    ShareLinkDto createShareLink(UUID quizId, UUID userId, CreateShareLinkRequest request);
    ShareLinkDto validateToken(String token);
    void revokeShareLink(UUID shareLinkId, UUID userId);
    java.util.List<ShareLinkDto> getUserShareLinks(UUID userId);
    void recordShareLinkUsage(String tokenHash, String userAgent, String ipAddress);
    ShareLinkDto consumeOneTimeToken(String token);
    void revokeActiveShareLinksForQuiz(UUID quizId);
}


