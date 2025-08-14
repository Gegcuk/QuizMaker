package uk.gegc.quizmaker.dto.quiz;

import com.fasterxml.jackson.annotation.JsonFormat;
import uk.gegc.quizmaker.model.quiz.ShareLinkEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for share link analytics data with privacy protection.
 */
public record ShareLinkAnalyticsDto(
    UUID id,
    UUID shareLinkId,
    ShareLinkEventType eventType,
    String dateBucket,
    String countryCode,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant createdAt
) {
    // Note: ipHash, userAgent, and referrer are intentionally excluded for privacy
}
