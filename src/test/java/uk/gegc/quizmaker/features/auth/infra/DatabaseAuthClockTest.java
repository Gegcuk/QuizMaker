package uk.gegc.quizmaker.features.auth.infra;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gegc.quizmaker.features.auth.infra.ratelimit.DatabaseAuthClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseAuthClockTest {
    @Test
    void databaseUtcTimestampKeepsSameInstantWhenDisplayZoneChanges() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-10-19T12:00:00.123456"));
        var clock = new DatabaseAuthClock(jdbc);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-19T12:00:00.123456Z"));
        var display = clock.withZone(ZoneId.of("Europe/London"));
        assertThat(display.getZone()).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(display.instant()).isEqualTo(clock.instant());
    }

    @Test
    void missingDatabaseTimestampFailsClosedWithoutLocalTimeFallback() {
        assertThatThrownBy(() -> new DatabaseAuthClock(mock(JdbcTemplate.class)).instant())
                .isInstanceOf(IllegalStateException.class).hasMessage("Authentication database clock unavailable");
    }
}
