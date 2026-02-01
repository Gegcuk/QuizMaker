package uk.gegc.quizmaker.features.repetition.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.repetition.application.SrsAlgorithm;
import uk.gegc.quizmaker.features.repetition.application.exception.RepetitionAlreadyProcessedException;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RepetitionReviewServiceImpl unit tests")
public class RepetitionReviewServiceImplTest extends BaseUnitTest {

    @Mock private SpacedRepetitionEntryRepository entryRepository;
    @Mock private RepetitionReviewLogRepository logRepository;
    @Mock private SrsAlgorithm srsAlgorithm;
    @Mock private RepetitionReviewService self;

    private Clock clock;
    private RepetitionReviewServiceImpl repetitionReviewService;

    @BeforeEach
    void setUp(){
        clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        repetitionReviewService = new RepetitionReviewServiceImpl(clock, entryRepository, logRepository, srsAlgorithm, self);
    }

    @Test
    @DisplayName("reviewEntryTx: duplicate idempotency key throws RepetitionAlreadyProcessedException")
    void reviewEntryTx_duplicateIdempotencyKey_throwsAlreadyProcessed(){
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyId = UUID.randomUUID();

        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(1);
        entry.setEaseFactor(2.5);

        when(entryRepository.findByIdAndUser_Id(entryId,userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(
                        1,
                        0,
                        2.3,
                        Instant.now(clock),
                        Instant.now(clock),
                        RepetitionEntryGrade.AGAIN));

        doThrow(new DataIntegrityViolationException("Duplicate entry"))
                .when(logRepository).save(any());

        assertThrows(RepetitionAlreadyProcessedException.class, () ->
                repetitionReviewService.reviewEntry(entryId, userId, RepetitionEntryGrade.AGAIN, idempotencyId));
    }

}
