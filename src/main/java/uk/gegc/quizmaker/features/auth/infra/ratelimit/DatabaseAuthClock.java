package uk.gegc.quizmaker.features.auth.infra.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** One authoritative admission time across replicas, exposed through the injectable Clock contract. */
@Component("authAdmissionClock")
public final class DatabaseAuthClock extends Clock {
    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    @Autowired
    public DatabaseAuthClock(JdbcTemplate jdbc) { this(jdbc, ZoneOffset.UTC); }

    private DatabaseAuthClock(JdbcTemplate jdbc, ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = Objects.requireNonNull(zone);
    }

    @Override
    public ZoneId getZone() { return zone; }

    @Override
    public Clock withZone(ZoneId zone) { return new DatabaseAuthClock(jdbc, zone); }

    @Override
    public Instant instant() {
        LocalDateTime timestamp = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        if (timestamp == null) throw new IllegalStateException("Authentication database clock unavailable");
        return timestamp.toInstant(ZoneOffset.UTC);
    }
}
