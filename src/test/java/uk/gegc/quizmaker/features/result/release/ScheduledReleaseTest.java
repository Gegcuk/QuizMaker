package uk.gegc.quizmaker.features.result.release;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException;
import uk.gegc.quizmaker.features.result.domain.model.release.ScheduledRelease;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

class ScheduledReleaseTest {
    final Instant time = Instant.parse("2026-12-04T17:00:00Z");
    ScheduledRelease pending() {
        return new ScheduledRelease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),
                UUID.randomUUID(),time,time.minusSeconds(60),ScheduledRelease.State.PENDING,0,0,time,null);
    }
    @ParameterizedTest @EnumSource(value=ResultReleaseException.Reason.class,names={"BUSY","RETRYABLE","UNAVAILABLE"})
    void exactlyThreePersistableRetriesAfterInitialFailure(ResultReleaseException.Reason reason) {
        var first=pending(); var current=first; var at=time;
        for(int wait:List.of(1,5,15)) {
            var failed=current.failed(reason,at);
            assertThat(failed.state()).isEqualTo(ScheduledRelease.State.RETRY);
            assertThat(failed.nextAt()).isEqualTo(at.plusSeconds(wait*60L));
            assertThat(failed.failures()).isEqualTo(current.failures()+1);
            assertThat(failed.eventId()).isEqualTo(first.eventId());
            assertThat(failed.configurationId()).isEqualTo(first.configurationId());
            at=failed.nextAt(); current=failed;
        }
        var exhausted=current.failed(reason,at);
        assertThat(exhausted.state()).isEqualTo(ScheduledRelease.State.REVIEW);
        assertThat(exhausted.failures()).isEqualTo(4);
        assertThat(exhausted.nextAt()).isNull();
        assertThatThrownBy(()->exhausted.failed(reason,time)).isInstanceOf(ResultReleaseException.class);
        assertThatThrownBy(exhausted::released).isInstanceOf(ResultReleaseException.class);
    }
    @ParameterizedTest @EnumSource(value=ResultReleaseException.Reason.class,names={"BUSY","RETRYABLE","UNAVAILABLE"},mode=EnumSource.Mode.EXCLUDE)
    void uncertainOrUnsupportedFactsDoNotBecomeAnAutomaticRetry(ResultReleaseException.Reason reason) {
        var failed=pending().failed(reason,time);
        assertThat(failed.state()).isEqualTo(ScheduledRelease.State.REVIEW);
        assertThat(failed.nextAt()).isNull();
        assertThat(failed.lastFailure()).isEqualTo(reason);
    }
    @Test void successfulRetryPreservesIdentityAndBudgetAndCannotFailAgain() {
        var pending=pending(); var released=pending.failed(UNAVAILABLE,time).released();
        assertThat(released.eventId()).isEqualTo(pending.eventId());
        assertThat(released.state()).isEqualTo(ScheduledRelease.State.RELEASED);
        assertThat(released.failures()).isEqualTo(1);
        assertThat(released.rowVersion()).isEqualTo(2);
        assertThat(released.nextAt()).isNull();
        assertThatThrownBy(()->released.failed(UNAVAILABLE,time)).isInstanceOf(ResultReleaseException.class);
        assertThatThrownBy(released::released).isInstanceOf(ResultReleaseException.class);
        assertThat(released.toString()).doesNotContain(released.eventId().toString(),released.configurationId().toString());
    }
    @Test void missingRetryTimeAndContradictoryFailureStateAreRejected() {
        var p=pending();
        assertThatThrownBy(()->new ScheduledRelease(p.submissionId(),p.resultId(),p.configurationId(),p.configurationHash(),
                p.eventId(),p.dueAt(),p.registeredAt(),ScheduledRelease.State.RETRY,1,1,null,UNAVAILABLE))
                .isInstanceOf(ResultReleaseException.class);
        assertThatThrownBy(()->new ScheduledRelease(p.submissionId(),p.resultId(),p.configurationId(),p.configurationHash(),
                p.eventId(),p.dueAt(),p.registeredAt(),ScheduledRelease.State.RELEASED,0,1,null,UNAVAILABLE))
                .isInstanceOf(ResultReleaseException.class);
    }
}
