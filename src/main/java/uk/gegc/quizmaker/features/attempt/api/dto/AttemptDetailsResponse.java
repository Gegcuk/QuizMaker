package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AttemptDetailsResponse", oneOf = {AttemptDetailsDto.class, ParticipantAttemptView.class},
        description = "Legacy retains AttemptDetailsDto without target-only fields. POLICY_DRIVEN/v1 is available only when the participant-view adapter is enabled; it never invents legacy mode/status or historical evidence.")
public sealed interface AttemptDetailsResponse permits AttemptDetailsDto, ParticipantAttemptView { }
