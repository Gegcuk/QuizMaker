package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTiming;

@Schema(name = "ParticipantAttemptTiming", description = "Saved overall deadline and current server time in UTC. No client timestamp can extend it. No section timer, pause or expiry-worker state is implied.")
public record ParticipantAttemptTiming(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1") int version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant effectiveDeadline,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "0", description = "Seconds; v1 has no grace.") int graceSeconds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant serverTime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "At/after the deadline. Does not assert that terminal processing has committed.") boolean expired) {
    public static ParticipantAttemptTiming from(AttemptTiming timing, Instant serverTime) {
        return timing == null ? null : new ParticipantAttemptTiming(timing.version(), timing.startedAt(),
                timing.effectiveDeadline(), 0, serverTime, timing.decide(serverTime) == AttemptTiming.Decision.EXPIRED);
    }
}
