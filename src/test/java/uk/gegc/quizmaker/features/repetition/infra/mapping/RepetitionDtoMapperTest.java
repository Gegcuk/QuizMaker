package uk.gegc.quizmaker.features.repetition.infra.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionEntryDto;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionHistoryDto;
import uk.gegc.quizmaker.features.repetition.domain.model.*;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RepetitionDtoMapper Tests")
class RepetitionDtoMapperTest extends BaseUnitTest {

    private RepetitionDtoMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new RepetitionDtoMapper();
    }

    @Test
    @DisplayName("Should map all fields and priorityScore")
    void shouldMapEntryDto() {
        UUID entryId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        Instant nextReviewAt = Instant.parse("2025-02-10T12:00:00Z");
        Instant lastReviewedAt = Instant.parse("2025-02-01T12:00:00Z");
        int priorityScore = 42;

        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setId(entryId);
        Question question = new Question();
        question.setId(questionId);
        entry.setQuestion(question);
        entry.setNextReviewAt(nextReviewAt);
        entry.setLastReviewedAt(lastReviewedAt);
        entry.setLastGrade(RepetitionEntryGrade.GOOD);
        entry.setIntervalDays(6);
        entry.setRepetitionCount(2);
        entry.setEaseFactor(2.48);
        entry.setReminderEnabled(true);

        RepetitionEntryDto dto = mapper.toEntryDto(entry, priorityScore);

        assertEquals(entryId, dto.entryId());
        assertEquals(questionId, dto.questionId());
        assertEquals(nextReviewAt, dto.nextReviewAt());
        assertEquals(lastReviewedAt, dto.lastReviewedAt());
        assertEquals(RepetitionEntryGrade.GOOD, dto.lastGrade());
        assertEquals(6, dto.intervalDays());
        assertEquals(2, dto.repetitionCount());
        assertEquals(2.48, dto.easeFactor());
        assertEquals(true, dto.reviewEnabled());
        assertEquals(priorityScore, dto.priorityScore());
    }

    @Test
    @DisplayName("Should map all history log fields")
    void shouldMapHistoryDto() {
        UUID reviewId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Instant reviewedAt = Instant.parse("2025-02-01T12:00:00Z");
        UUID sourceId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        RepetitionReviewLog log = new RepetitionReviewLog();
        log.setId(reviewId);
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setId(entryId);
        log.setEntry(entry);
        log.setContentType(RepetitionContentType.QUESTION);
        log.setContentId(contentId);
        log.setGrade(RepetitionEntryGrade.GOOD);
        log.setReviewedAt(reviewedAt);
        log.setIntervalDays(6);
        log.setEaseFactor(2.5);
        log.setRepetitionCount(2);
        log.setSourceType(RepetitionReviewSourceType.ATTEMPT_ANSWER);
        log.setSourceId(sourceId);
        log.setAttemptId(attemptId);

        RepetitionHistoryDto dto = mapper.toHistoryDto(log);

        assertEquals(reviewId, dto.reviewId());
        assertEquals(entryId, dto.entryId());
        assertEquals(RepetitionContentType.QUESTION, dto.contentType());
        assertEquals(contentId, dto.contentId());
        assertEquals(RepetitionEntryGrade.GOOD, dto.grade());
        assertEquals(reviewedAt, dto.reviewedAt());
        assertEquals(6, dto.intervalDays());
        assertEquals(2.5, dto.easeFactor());
        assertEquals(2, dto.repetitionCount());
        assertEquals(RepetitionReviewSourceType.ATTEMPT_ANSWER, dto.sourceType());
        assertEquals(sourceId, dto.sourceId());
        assertEquals(attemptId, dto.attemptId());
    }
}