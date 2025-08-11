package uk.gegc.quizmaker.service.quiz.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.quiz.ShareLinkAnalyticsDto;
import uk.gegc.quizmaker.dto.quiz.ShareLinkAnalyticsSummaryDto;
import uk.gegc.quizmaker.model.quiz.ShareLinkAnalytics;
import uk.gegc.quizmaker.model.quiz.ShareLinkEventType;
import uk.gegc.quizmaker.repository.quiz.ShareLinkAnalyticsRepository;
import uk.gegc.quizmaker.repository.quiz.ShareLinkRepository;
import uk.gegc.quizmaker.service.quiz.ShareLinkAnalyticsService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of ShareLinkAnalyticsService with privacy protection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShareLinkAnalyticsServiceImpl implements ShareLinkAnalyticsService {

    private final ShareLinkAnalyticsRepository analyticsRepository;
    private final ShareLinkRepository shareLinkRepository;

    @Value("${quizmaker.share-links.token-pepper:}")
    private String tokenPepper;

    @Override
    @Transactional
    public void recordEvent(UUID shareLinkId, ShareLinkEventType eventType, String userAgent, 
                          String ipAddress, String referrer, String countryCode) {
        try {
            // Find the share link
            var shareLink = shareLinkRepository.findById(shareLinkId)
                    .orElseThrow(() -> new IllegalArgumentException("Share link not found: " + shareLinkId));

            // Create analytics event
            ShareLinkAnalytics analytics = ShareLinkAnalytics.builder()
                    .shareLink(shareLink)
                    .eventType(eventType)
                    .ipHash(computeIpHash(ipAddress))
                    .userAgent(truncateUserAgent(userAgent))
                    .dateBucket(LocalDate.now(ZoneOffset.UTC).toString())
                    .countryCode(countryCode)
                    .referrer(truncateReferrer(referrer))
                    .build();

            analyticsRepository.save(analytics);
            
            log.debug("Recorded analytics event: {} for share link: {}", eventType, shareLinkId);
        } catch (Exception e) {
            log.error("Failed to record analytics event for share link: {}", shareLinkId, e);
            // Don't throw - analytics should not break main functionality
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareLinkAnalyticsDto> getShareLinkEvents(UUID shareLinkId) {
        return analyticsRepository.findByShareLink_IdOrderByCreatedAtDesc(shareLinkId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareLinkAnalyticsDto> getShareLinkEventsByType(UUID shareLinkId, ShareLinkEventType eventType) {
        return analyticsRepository.findByShareLink_IdAndEventTypeOrderByCreatedAtDesc(shareLinkId, eventType)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ShareLinkAnalyticsSummaryDto getShareLinkSummary(UUID shareLinkId) {
        var events = analyticsRepository.findByShareLink_IdOrderByCreatedAtDesc(shareLinkId);
        
        long totalViews = analyticsRepository.countByShareLink_IdAndEventType(shareLinkId, ShareLinkEventType.VIEW);
        long totalAttempts = analyticsRepository.countByShareLink_IdAndEventType(shareLinkId, ShareLinkEventType.ATTEMPT_START);
        long totalConsumptions = analyticsRepository.countByShareLink_IdAndEventType(shareLinkId, ShareLinkEventType.CONSUMED);
        
        // Count unique visitors (by IP hash)
        long uniqueVisitors = events.stream()
                .map(ShareLinkAnalytics::getIpHash)
                .distinct()
                .count();

        // Build event counts map
        Map<ShareLinkEventType, Long> eventCounts = new HashMap<>();
        for (ShareLinkEventType type : ShareLinkEventType.values()) {
            eventCounts.put(type, analyticsRepository.countByShareLink_IdAndEventType(shareLinkId, type));
        }

        // Build daily views map
        Map<String, Long> dailyViews = events.stream()
                .filter(e -> e.getEventType() == ShareLinkEventType.VIEW)
                .collect(Collectors.groupingBy(
                        ShareLinkAnalytics::getDateBucket,
                        Collectors.counting()
                ));

        return new ShareLinkAnalyticsSummaryDto(
                shareLinkId,
                events.isEmpty() ? null : events.get(0).getShareLink().getQuiz().getId(),
                totalViews,
                totalAttempts,
                totalConsumptions,
                uniqueVisitors,
                eventCounts,
                dailyViews
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareLinkAnalyticsDto> getQuizEvents(UUID quizId, LocalDate startDate, LocalDate endDate) {
        return analyticsRepository.findByQuizIdAndDateRange(
                        quizId, 
                        startDate.toString(), 
                        endDate.toString()
                )
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ShareLinkAnalyticsSummaryDto getQuizSummary(UUID quizId, LocalDate startDate, LocalDate endDate) {
        var events = analyticsRepository.findByQuizIdAndDateRange(
                quizId, 
                startDate.toString(), 
                endDate.toString()
        );

        if (events.isEmpty()) {
            return new ShareLinkAnalyticsSummaryDto(
                    null, quizId, 0, 0, 0, 0, new HashMap<>(), new HashMap<>()
            );
        }

        // Count events by type
        Map<ShareLinkEventType, Long> eventCounts = new HashMap<>();
        for (ShareLinkEventType type : ShareLinkEventType.values()) {
            long count = events.stream()
                    .filter(e -> e.getEventType() == type)
                    .count();
            eventCounts.put(type, count);
        }

        long totalViews = eventCounts.getOrDefault(ShareLinkEventType.VIEW, 0L);
        long totalAttempts = eventCounts.getOrDefault(ShareLinkEventType.ATTEMPT_START, 0L);
        long totalConsumptions = eventCounts.getOrDefault(ShareLinkEventType.CONSUMED, 0L);

        // Count unique visitors
        long uniqueVisitors = events.stream()
                .map(ShareLinkAnalytics::getIpHash)
                .distinct()
                .count();

        // Build daily views map
        Map<String, Long> dailyViews = events.stream()
                .filter(e -> e.getEventType() == ShareLinkEventType.VIEW)
                .collect(Collectors.groupingBy(
                        ShareLinkAnalytics::getDateBucket,
                        Collectors.counting()
                ));

        return new ShareLinkAnalyticsSummaryDto(
                null, // No specific share link for quiz summary
                quizId,
                totalViews,
                totalAttempts,
                totalConsumptions,
                uniqueVisitors,
                eventCounts,
                dailyViews
        );
    }

    @Override
    @Transactional(readOnly = true)
    public long getUniqueVisitorCount(UUID quizId, LocalDate startDate, LocalDate endDate) {
        return analyticsRepository.getUniqueVisitorCount(
                quizId, 
                startDate.toString(), 
                endDate.toString()
        );
    }

    @Override
    @Transactional
    public int cleanupOldEvents(LocalDate cutoffDate) {
        return analyticsRepository.deleteEventsOlderThan(cutoffDate.toString());
    }

    /**
     * Maps ShareLinkAnalytics entity to DTO with privacy protection.
     */
    private ShareLinkAnalyticsDto mapToDto(ShareLinkAnalytics analytics) {
        return new ShareLinkAnalyticsDto(
                analytics.getId(),
                analytics.getShareLink().getId(),
                analytics.getEventType(),
                analytics.getDateBucket(),
                analytics.getCountryCode(),
                analytics.getCreatedAt()
        );
    }

    /**
     * Computes SHA-256 hash of IP address with pepper and date bucket for privacy.
     */
    private String computeIpHash(String ipAddress) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            return "";
        }
        
        String dateBucket = LocalDate.now(ZoneOffset.UTC).toString();
        String input = tokenPepper + ":" + dateBucket + ":" + ipAddress.trim();
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available", e);
            return "";
        }
    }

    /**
     * Truncates user agent to 256 characters for storage efficiency.
     */
    private String truncateUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > 256 ? userAgent.substring(0, 256) : userAgent;
    }

    /**
     * Truncates referrer URL to 512 characters for storage efficiency.
     */
    private String truncateReferrer(String referrer) {
        if (referrer == null) {
            return null;
        }
        return referrer.length() > 512 ? referrer.substring(0, 512) : referrer;
    }

    /**
     * Converts byte array to hexadecimal string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
