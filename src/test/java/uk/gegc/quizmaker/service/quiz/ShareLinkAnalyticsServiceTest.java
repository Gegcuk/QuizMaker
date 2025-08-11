package uk.gegc.quizmaker.service.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.quiz.ShareLinkAnalyticsDto;
import uk.gegc.quizmaker.dto.quiz.ShareLinkAnalyticsSummaryDto;
import uk.gegc.quizmaker.model.quiz.ShareLink;
import uk.gegc.quizmaker.model.quiz.ShareLinkAnalytics;
import uk.gegc.quizmaker.model.quiz.ShareLinkEventType;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.ShareLinkAnalyticsRepository;
import uk.gegc.quizmaker.repository.quiz.ShareLinkRepository;
import uk.gegc.quizmaker.service.quiz.impl.ShareLinkAnalyticsServiceImpl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareLinkAnalyticsServiceTest {

    @Mock
    private ShareLinkAnalyticsRepository analyticsRepository;

    @Mock
    private ShareLinkRepository shareLinkRepository;

    @InjectMocks
    private ShareLinkAnalyticsServiceImpl analyticsService;

    private UUID shareLinkId;
    private UUID quizId;
    private UUID userId;
    private ShareLink shareLink;
    private Quiz quiz;
    private User user;

    @BeforeEach
    void setUp() {
        shareLinkId = UUID.randomUUID();
        quizId = UUID.randomUUID();
        userId = UUID.randomUUID();

        user = new User();
        user.setId(userId);

        quiz = new Quiz();
        quiz.setId(quizId);

        shareLink = new ShareLink();
        shareLink.setId(shareLinkId);
        shareLink.setQuiz(quiz);
        shareLink.setCreatedBy(user);
    }

    @Test
    @DisplayName("recordEvent: records analytics event successfully")
    void recordEvent_recordsEventSuccessfully() {
        when(shareLinkRepository.findById(shareLinkId)).thenReturn(Optional.of(shareLink));
        when(analyticsRepository.save(any(ShareLinkAnalytics.class))).thenAnswer(inv -> inv.getArgument(0));

        analyticsService.recordEvent(shareLinkId, ShareLinkEventType.VIEW, "User-Agent", "127.0.0.1", "https://example.com", "US");

        verify(analyticsRepository).save(any(ShareLinkAnalytics.class));
    }

    @Test
    @DisplayName("recordEvent: handles missing share link gracefully")
    void recordEvent_handlesMissingShareLink() {
        when(shareLinkRepository.findById(shareLinkId)).thenReturn(Optional.empty());

        analyticsService.recordEvent(shareLinkId, ShareLinkEventType.VIEW, "User-Agent", "127.0.0.1", null, null);

        verify(analyticsRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordEvent: handles null values gracefully")
    void recordEvent_handlesNullValues() {
        when(shareLinkRepository.findById(shareLinkId)).thenReturn(Optional.of(shareLink));
        when(analyticsRepository.save(any(ShareLinkAnalytics.class))).thenAnswer(inv -> inv.getArgument(0));

        analyticsService.recordEvent(shareLinkId, ShareLinkEventType.VIEW, null, null, null, null);

        verify(analyticsRepository).save(any(ShareLinkAnalytics.class));
    }

    @Test
    @DisplayName("getShareLinkEvents: returns events for share link")
    void getShareLinkEvents_returnsEvents() {
        ShareLinkAnalytics analytics = createAnalyticsEvent();
        when(analyticsRepository.findByShareLink_IdOrderByCreatedAtDesc(shareLinkId))
                .thenReturn(List.of(analytics));

        List<ShareLinkAnalyticsDto> result = analyticsService.getShareLinkEvents(shareLinkId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).shareLinkId()).isEqualTo(shareLinkId);
        assertThat(result.get(0).eventType()).isEqualTo(ShareLinkEventType.VIEW);
    }

    @Test
    @DisplayName("getShareLinkEventsByType: returns events filtered by type")
    void getShareLinkEventsByType_returnsFilteredEvents() {
        ShareLinkAnalytics analytics = createAnalyticsEvent();
        when(analyticsRepository.findByShareLink_IdAndEventTypeOrderByCreatedAtDesc(shareLinkId, ShareLinkEventType.VIEW))
                .thenReturn(List.of(analytics));

        List<ShareLinkAnalyticsDto> result = analyticsService.getShareLinkEventsByType(shareLinkId, ShareLinkEventType.VIEW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).eventType()).isEqualTo(ShareLinkEventType.VIEW);
    }

    @Test
    @DisplayName("getShareLinkSummary: returns summary with correct counts")
    void getShareLinkSummary_returnsCorrectCounts() {
        ShareLinkAnalytics analytics = createAnalyticsEvent();
        when(analyticsRepository.findByShareLink_IdOrderByCreatedAtDesc(shareLinkId))
                .thenReturn(List.of(analytics));
        when(analyticsRepository.countByShareLink_IdAndEventType(shareLinkId, ShareLinkEventType.VIEW))
                .thenReturn(1L);
        when(analyticsRepository.countByShareLink_IdAndEventType(shareLinkId, ShareLinkEventType.ATTEMPT_START))
                .thenReturn(0L);
        when(analyticsRepository.countByShareLink_IdAndEventType(shareLinkId, ShareLinkEventType.CONSUMED))
                .thenReturn(0L);

        ShareLinkAnalyticsSummaryDto result = analyticsService.getShareLinkSummary(shareLinkId);

        assertThat(result.shareLinkId()).isEqualTo(shareLinkId);
        assertThat(result.quizId()).isEqualTo(quizId);
        assertThat(result.totalViews()).isEqualTo(1);
        assertThat(result.totalAttempts()).isEqualTo(0);
        assertThat(result.totalConsumptions()).isEqualTo(0);
        assertThat(result.uniqueVisitors()).isEqualTo(1);
    }

    @Test
    @DisplayName("getQuizEvents: returns events for quiz in date range")
    void getQuizEvents_returnsEventsInDateRange() {
        ShareLinkAnalytics analytics = createAnalyticsEvent();
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        
        when(analyticsRepository.findByQuizIdAndDateRange(quizId, startDate.toString(), endDate.toString()))
                .thenReturn(List.of(analytics));

        List<ShareLinkAnalyticsDto> result = analyticsService.getQuizEvents(quizId, startDate, endDate);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).eventType()).isEqualTo(ShareLinkEventType.VIEW);
    }

    @Test
    @DisplayName("getQuizSummary: returns summary for quiz in date range")
    void getQuizSummary_returnsSummaryInDateRange() {
        ShareLinkAnalytics analytics = createAnalyticsEvent();
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        
        when(analyticsRepository.findByQuizIdAndDateRange(quizId, startDate.toString(), endDate.toString()))
                .thenReturn(List.of(analytics));

        ShareLinkAnalyticsSummaryDto result = analyticsService.getQuizSummary(quizId, startDate, endDate);

        assertThat(result.quizId()).isEqualTo(quizId);
        assertThat(result.totalViews()).isEqualTo(1);
        assertThat(result.uniqueVisitors()).isEqualTo(1);
    }

    @Test
    @DisplayName("getQuizSummary: returns empty summary when no events")
    void getQuizSummary_returnsEmptySummaryWhenNoEvents() {
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        
        when(analyticsRepository.findByQuizIdAndDateRange(quizId, startDate.toString(), endDate.toString()))
                .thenReturn(List.of());

        ShareLinkAnalyticsSummaryDto result = analyticsService.getQuizSummary(quizId, startDate, endDate);

        assertThat(result.quizId()).isEqualTo(quizId);
        assertThat(result.totalViews()).isEqualTo(0);
        assertThat(result.totalAttempts()).isEqualTo(0);
        assertThat(result.totalConsumptions()).isEqualTo(0);
        assertThat(result.uniqueVisitors()).isEqualTo(0);
    }

    @Test
    @DisplayName("getUniqueVisitorCount: returns correct count")
    void getUniqueVisitorCount_returnsCorrectCount() {
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        
        when(analyticsRepository.getUniqueVisitorCount(quizId, startDate.toString(), endDate.toString()))
                .thenReturn(5L);

        long result = analyticsService.getUniqueVisitorCount(quizId, startDate, endDate);

        assertThat(result).isEqualTo(5);
    }

    @Test
    @DisplayName("cleanupOldEvents: deletes events older than cutoff date")
    void cleanupOldEvents_deletesOldEvents() {
        LocalDate cutoffDate = LocalDate.now().minusDays(30);
        when(analyticsRepository.deleteEventsOlderThan(cutoffDate.toString())).thenReturn(10);

        int result = analyticsService.cleanupOldEvents(cutoffDate);

        assertThat(result).isEqualTo(10);
        verify(analyticsRepository).deleteEventsOlderThan(cutoffDate.toString());
    }

    private ShareLinkAnalytics createAnalyticsEvent() {
        return ShareLinkAnalytics.builder()
                .id(UUID.randomUUID())
                .shareLink(shareLink)
                .eventType(ShareLinkEventType.VIEW)
                .ipHash("hashed-ip-address")
                .userAgent("Test User Agent")
                .dateBucket(LocalDate.now().toString())
                .countryCode("US")
                .referrer("https://example.com")
                .createdAt(Instant.now())
                .build();
    }
}
