package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.api.dto.ShareLinkAnalyticsDto;
import uk.gegc.quizmaker.features.quiz.api.dto.ShareLinkAnalyticsSummaryDto;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLinkEventType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing share link analytics with privacy protection.
 */
public interface ShareLinkAnalyticsService {

    /**
     * Record a share link analytics event.
     * 
     * @param shareLinkId The share link ID
     * @param eventType The event type
     * @param userAgent The user agent (will be truncated)
     * @param ipAddress The IP address (will be hashed)
     * @param referrer The referrer URL (optional)
     * @param countryCode The country code (optional)
     */
    void recordEvent(UUID shareLinkId, ShareLinkEventType eventType, String userAgent, 
                    String ipAddress, String referrer, String countryCode);

    /**
     * Get analytics events for a specific share link.
     * 
     * @param shareLinkId The share link ID
     * @return List of analytics events
     */
    List<ShareLinkAnalyticsDto> getShareLinkEvents(UUID shareLinkId);

    /**
     * Get analytics events for a specific share link and event type.
     * 
     * @param shareLinkId The share link ID
     * @param eventType The event type
     * @return List of analytics events
     */
    List<ShareLinkAnalyticsDto> getShareLinkEventsByType(UUID shareLinkId, ShareLinkEventType eventType);

    /**
     * Get analytics summary for a specific share link.
     * 
     * @param shareLinkId The share link ID
     * @return Analytics summary
     */
    ShareLinkAnalyticsSummaryDto getShareLinkSummary(UUID shareLinkId);

    /**
     * Get analytics events for a specific quiz within a date range.
     * 
     * @param quizId The quiz ID
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return List of analytics events
     */
    List<ShareLinkAnalyticsDto> getQuizEvents(UUID quizId, LocalDate startDate, LocalDate endDate);

    /**
     * Get analytics summary for a specific quiz within a date range.
     * 
     * @param quizId The quiz ID
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return Analytics summary
     */
    ShareLinkAnalyticsSummaryDto getQuizSummary(UUID quizId, LocalDate startDate, LocalDate endDate);

    /**
     * Get unique visitor count for a quiz within a date range.
     * 
     * @param quizId The quiz ID
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return Count of unique visitors
     */
    long getUniqueVisitorCount(UUID quizId, LocalDate startDate, LocalDate endDate);

    /**
     * Clean up old analytics events.
     * 
     * @param cutoffDate Events older than this date will be deleted
     * @return Number of deleted records
     */
    int cleanupOldEvents(LocalDate cutoffDate);
}
