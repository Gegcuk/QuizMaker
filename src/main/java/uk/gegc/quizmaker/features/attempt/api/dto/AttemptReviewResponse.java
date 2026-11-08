package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AttemptReviewResponse", oneOf = {AttemptReviewDto.class, ParticipantAttemptView.class},
        description = "Legacy envelope is unchanged. Enabled target attempts return POLICY_DRIVEN/v1 from their original form and committed release. Include flags can narrow but never authorize categories.")
public sealed interface AttemptReviewResponse permits AttemptReviewDto, ParticipantAttemptView { }
