package uk.gegc.quizmaker.features.result.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.*;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfiguration;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfiguration.*;

public final class ReleaseConfigurationDtos {
    private ReleaseConfigurationDtos() { }
    @Schema(name = "ReleaseScheduleRequest", types = {"object", "null"}, description = "Local date/time with microsecond precision and IANA zone. A DST overlap requires an explicit valid offset; gaps reject. No future-time constraint is inferred.")
    public record ScheduleRequest(@NotNull @Schema(type = "string", format = "local-date-time", example = "2026-12-04T17:00:00") LocalDateTime localTime,
                                  @NotBlank @Size(max = 100) String zoneId,
                                  @Schema(types = {"string", "null"}, example = "+01:00") ZoneOffset offset) { }
    @Schema(name = "ReleaseRuleRequest", description = "Explicit formal rule. Missing/null categories selects TOTAL_SCORE and PASS_FAIL; an empty set grants nothing. MANUAL requires dedicated assignment-and-organization-scoped release permission later. IMMEDIATE cannot combine with other triggers. No release occurs here.")
    public record RuleRequest(@NotNull Integer version, @NotEmpty Set<Trigger> triggers,
                              @Schema(types = {"array", "null"}) Set<Category> categories,
                              @Valid @Schema(types = {"object", "null"}) ScheduleRequest schedule) {
        public ReleaseConfiguration rule() {
            return ReleaseConfiguration.formal(version, triggers, categories, schedule == null ? null
                    : new ScheduleInput(schedule.localTime(), schedule.zoneId(), schedule.offset()));
        }
        @Override public String toString() { return "ReleaseRuleRequest"; }
    }
    @Schema(name = "PublishReleaseConfigurationRequest", description = "commandId is scoped to the authenticated author, quiz and revision. Reuse only with identical input, including expectedVersion. Zero means no current configuration; later versions are positive. Retry an unknown outcome with the same command.")
    public record PublishRequest(@NotNull UUID commandId, @NotNull @PositiveOrZero Long expectedVersion,
                                 @NotNull @Valid RuleRequest rule) {
        @Override public String toString() { return "PublishReleaseConfigurationRequest"; }
    }
    @Schema(name = "ReleaseScheduleEvidence", requiredProperties = {"localTime", "zoneId", "offset", "instant"}, description = "Captured conversion evidence. instant is authoritative UTC; reads never reinterpret it in the process timezone.")
    public record ScheduleView(@Schema(type = "string", format = "local-date-time") LocalDateTime localTime, String zoneId, ZoneOffset offset, Instant instant) { }
    @Schema(name = "ReleaseConfigurationPreview", requiredProperties = {"version", "family", "triggers", "categories", "notificationBoundary"}, description = "Validated configuration only. No score, release or notification delivery is created. Categories are a future disclosure ceiling, not current access authority. Inapplicable schedule/manualAuthority fields are omitted.")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RuleView(int version, Family family, Set<Trigger> triggers, Set<Category> categories,
                           @Schema(nullable = true) ScheduleView schedule,
                           @Schema(nullable = true) ManualAuthority manualAuthority, NotificationBoundary notificationBoundary) {
        public static RuleView from(ReleaseConfiguration rule) {
            var s = rule.schedule();
            return new RuleView(rule.version(), rule.family(), rule.triggers(), rule.categories(), s == null ? null
                    : new ScheduleView(s.localTime(), s.zoneId(), s.offset(), s.instant()), rule.manualAuthority(), rule.notificationBoundary());
        }
    }
    @Schema(name = "ReleaseConfigurationPublication", requiredProperties = {"id", "quizId", "revisionId", "publicationVersion", "hash", "rule", "publishedAt", "replayed"}, description = "Immutable revision-bound configuration. Capture id for later consumers; current is an authoring pointer and never rewrites old bindings. PublicationVersion and timestamps are metadata, not a released result.")
    public record PublicationView(UUID id, UUID quizId, UUID revisionId, long publicationVersion,
                                  String hash, RuleView rule, Instant publishedAt, boolean replayed) {
        public static PublicationView from(ReleaseConfigurationService.Publication p) {
            return new PublicationView(p.id(), p.quizId(), p.revisionId(), p.publicationVersion(), p.hash(), RuleView.from(p.rule()), p.publishedAt(), p.replayed());
        }
    }
}
