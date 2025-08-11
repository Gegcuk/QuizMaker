package uk.gegc.quizmaker.service.quiz;

import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;

import java.util.UUID;

public interface ShareLinkService {
    ShareLinkDto createShareLink(UUID quizId, UUID userId, CreateShareLinkRequest request);
    ShareLinkDto validateToken(String token);
}


