package uk.gegc.quizmaker.features.attempt.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptDetailsDto;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.application.ParticipantAttemptViewService;
import uk.gegc.quizmaker.features.attempt.application.impl.ParticipantAttemptViewServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParticipantAttemptViewActivationTest {
    @Test void viewControllerIsGatedAndCannotSwitchLegacyReaders() {
        for (String[] properties : List.of(new String[0],
                new String[]{"quizmaker.attempts.participant-view-enabled=false"},
                new String[]{"quizmaker.attempts.participant-view-enabled=true"})) {
            var legacy = mock(AttemptService.class);
            var target = mock(ParticipantAttemptViewService.class);
            new ApplicationContextRunner()
                    .withUserConfiguration(AttemptController.class, ParticipantAttemptViewController.class)
                    .withBean(AttemptService.class, () -> legacy)
                    .withBean(ParticipantAttemptViewService.class, () -> target)
                    .withPropertyValues(properties).run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(AttemptController.class);
                        assertThat(context.containsBean("participantAttemptViewController"))
                                .isEqualTo(properties.length == 1 && properties[0].endsWith("=true"));
                        var id = UUID.randomUUID();
                        var detail = new AttemptDetailsDto(id, UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                                null, AttemptStatus.IN_PROGRESS, AttemptMode.ALL_AT_ONCE, List.of());
                        var review = new uk.gegc.quizmaker.features.attempt.api.dto.AttemptReviewDto(id,
                                detail.quizId(), detail.userId(), Instant.EPOCH, Instant.EPOCH, 1.0, 1, 1, List.of());
                        when(legacy.getAttemptDetail("participant", id)).thenReturn(detail);
                        when(legacy.getAttemptReview("participant", id, false, false, false)).thenReturn(review);
                        when(legacy.getAttemptAnswerKey("participant", id)).thenReturn(review);
                        var controller = context.getBean(AttemptController.class);
                        var auth = new UsernamePasswordAuthenticationToken("participant", null);
                        assertThat(controller.getAttempt(id, auth).getBody()).isSameAs(detail);
                        assertThat(controller.getAttemptReview(id, false, false, false, auth).getBody()).isSameAs(review);
                        assertThat(controller.getAttemptAnswerKey(id, auth).getBody()).isSameAs(review);
                        verifyNoInteractions(target);
                    });
        }
    }

    @Test void disabledAdapterPreservesLegacyReviewAndAnswerKeyEnvelopesAndFlags() {
        for (String[] properties : List.of(new String[0], new String[]{"quizmaker.attempts.participant-view-enabled=false"})) {
            new ApplicationContextRunner().withUserConfiguration(ParticipantAttemptViewServiceImpl.class)
                    .withPropertyValues(properties).run(context -> {
                        var legacy = mock(AttemptService.class); var id = UUID.randomUUID();
                        var expected = new uk.gegc.quizmaker.features.attempt.api.dto.AttemptReviewDto(id, UUID.randomUUID(), UUID.randomUUID(),
                                Instant.EPOCH, Instant.EPOCH, 1.0, 1, 1, List.of());
                        when(legacy.getAttemptReview("participant", id, false, false, false)).thenReturn(expected);
                        when(legacy.getAttemptAnswerKey("participant", id)).thenReturn(expected);
                        var controller = new AttemptController(legacy);
                        var auth = new UsernamePasswordAuthenticationToken("participant", null);
                        assertThat(controller.getAttemptReview(id, false, false, false, auth).getBody()).isSameAs(expected);
                        assertThat(controller.getAttemptAnswerKey(id, auth).getBody()).isSameAs(expected);
                        verify(legacy).getAttemptReview("participant", id, false, false, false);
                        verify(legacy).getAttemptAnswerKey("participant", id); verifyNoMoreInteractions(legacy);
                    });
        }
    }
    @Test void absentOrFalseActivationKeepsTheActualControllerOnTheLegacyReader() {
        for (String[] properties : List.of(new String[0], new String[]{"quizmaker.attempts.participant-view-enabled=false"})) {
            new ApplicationContextRunner().withUserConfiguration(ParticipantAttemptViewServiceImpl.class)
                    .withPropertyValues(properties).run(context -> {
                        assertThat(context).hasNotFailed().doesNotHaveBean(ParticipantAttemptViewService.class);
                        var legacy = mock(AttemptService.class); var id = UUID.randomUUID();
                        var expected = new AttemptDetailsDto(id, UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                                null, AttemptStatus.IN_PROGRESS, AttemptMode.ALL_AT_ONCE, List.of());
                        when(legacy.getAttemptDetail("participant", id)).thenReturn(expected);
                        var controller = new AttemptController(legacy);
                        var response = controller.getAttempt(id, new UsernamePasswordAuthenticationToken("participant", null));
                        assertThat(response.getBody()).isSameAs(expected);
                        assertThat(response.getHeaders().getCacheControl()).isNull();
                        verify(legacy).getAttemptDetail("participant", id); verifyNoMoreInteractions(legacy);
                    });
        }
    }
}
