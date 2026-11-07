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
    @Test void absentOrFalseActivationKeepsTheActualControllerOnTheLegacyReader() {
        for (String[] properties : List.of(new String[0], new String[]{"quizmaker.attempts.participant-view-enabled=false"})) {
            new ApplicationContextRunner().withUserConfiguration(ParticipantAttemptViewServiceImpl.class)
                    .withPropertyValues(properties).run(context -> {
                        assertThat(context).hasNotFailed().doesNotHaveBean(ParticipantAttemptViewService.class);
                        var legacy = mock(AttemptService.class); var id = UUID.randomUUID();
                        var expected = new AttemptDetailsDto(id, UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                                null, AttemptStatus.IN_PROGRESS, AttemptMode.ALL_AT_ONCE, List.of());
                        when(legacy.getAttemptDetail("participant", id)).thenReturn(expected);
                        var controller = new AttemptController(legacy, context.getBeanProvider(ParticipantAttemptViewService.class));
                        var response = controller.getAttempt(id, new UsernamePasswordAuthenticationToken("participant", null));
                        assertThat(response.getBody()).isSameAs(expected);
                        assertThat(response.getHeaders().getCacheControl()).isNull();
                        verify(legacy).getAttemptDetail("participant", id); verifyNoMoreInteractions(legacy);
                    });
        }
    }
}
