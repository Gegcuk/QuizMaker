package uk.gegc.quizmaker.dto.quiz;

import uk.gegc.quizmaker.model.quiz.ShareLinkEventType;

import java.util.Map;
import java.util.UUID;

/**
 * DTO for share link analytics summary with aggregated metrics.
 */
public record ShareLinkAnalyticsSummaryDto(
    UUID shareLinkId,
    UUID quizId,
    long totalViews,
    long totalAttempts,
    long totalConsumptions,
    long uniqueVisitors,
    Map<ShareLinkEventType, Long> eventCounts,
    Map<String, Long> dailyViews // date -> count
) {
}
