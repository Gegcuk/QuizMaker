package uk.gegc.quizmaker.repository.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.quiz.ShareLinkAnalytics;
import uk.gegc.quizmaker.model.quiz.ShareLinkEventType;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ShareLinkAnalytics entity operations.
 */
@Repository
public interface ShareLinkAnalyticsRepository extends JpaRepository<ShareLinkAnalytics, UUID> {

    /**
     * Find analytics events for a specific share link.
     * 
     * @param shareLinkId The share link ID
     * @return List of analytics events
     */
    List<ShareLinkAnalytics> findByShareLink_IdOrderByCreatedAtDesc(UUID shareLinkId);

    /**
     * Find analytics events for a specific share link and event type.
     * 
     * @param shareLinkId The share link ID
     * @param eventType The event type
     * @return List of analytics events
     */
    List<ShareLinkAnalytics> findByShareLink_IdAndEventTypeOrderByCreatedAtDesc(UUID shareLinkId, ShareLinkEventType eventType);

    /**
     * Count events by type for a specific share link.
     * 
     * @param shareLinkId The share link ID
     * @param eventType The event type
     * @return Count of events
     */
    long countByShareLink_IdAndEventType(UUID shareLinkId, ShareLinkEventType eventType);

    /**
     * Find analytics events for a specific quiz within a date range.
     * 
     * @param quizId The quiz ID
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return List of analytics events
     */
    @Query("SELECT sa FROM ShareLinkAnalytics sa " +
           "JOIN sa.shareLink sl " +
           "WHERE sl.quiz.id = :quizId " +
           "AND sa.dateBucket BETWEEN :startDate AND :endDate " +
           "ORDER BY sa.createdAt DESC")
    List<ShareLinkAnalytics> findByQuizIdAndDateRange(
            @Param("quizId") UUID quizId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );

    /**
     * Get daily event counts for a quiz within a date range.
     * 
     * @param quizId The quiz ID
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return List of daily event counts
     */
    @Query("SELECT sa.dateBucket, sa.eventType, COUNT(sa) " +
           "FROM ShareLinkAnalytics sa " +
           "JOIN sa.shareLink sl " +
           "WHERE sl.quiz.id = :quizId " +
           "AND sa.dateBucket BETWEEN :startDate AND :endDate " +
           "GROUP BY sa.dateBucket, sa.eventType " +
           "ORDER BY sa.dateBucket DESC, sa.eventType")
    List<Object[]> getDailyEventCounts(
            @Param("quizId") UUID quizId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );

    /**
     * Get unique visitor count (by IP hash) for a quiz within a date range.
     * 
     * @param quizId The quiz ID
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return Count of unique visitors
     */
    @Query("SELECT COUNT(DISTINCT sa.ipHash) " +
           "FROM ShareLinkAnalytics sa " +
           "JOIN sa.shareLink sl " +
           "WHERE sl.quiz.id = :quizId " +
           "AND sa.dateBucket BETWEEN :startDate AND :endDate")
    long getUniqueVisitorCount(
            @Param("quizId") UUID quizId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );

    /**
     * Delete analytics events older than the specified date.
     * 
     * @param cutoffDate The cutoff date (events older than this will be deleted)
     * @return Number of deleted records
     */
    @Query("DELETE FROM ShareLinkAnalytics sa WHERE sa.dateBucket < :cutoffDate")
    int deleteEventsOlderThan(@Param("cutoffDate") String cutoffDate);
}
