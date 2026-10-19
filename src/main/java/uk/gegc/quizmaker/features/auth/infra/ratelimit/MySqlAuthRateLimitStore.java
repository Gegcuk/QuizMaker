package uk.gegc.quizmaker.features.auth.infra.ratelimit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.auth.application.AuthRateLimitStore;
import uk.gegc.quizmaker.features.auth.config.AuthRateLimitProperties;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Repository
@Slf4j
public class MySqlAuthRateLimitStore implements AuthRateLimitStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final int cleanupBatchSize;
    private final Clock clock;

    public MySqlAuthRateLimitStore(JdbcTemplate jdbc, ObjectMapper mapper,
                                  PlatformTransactionManager manager, AuthRateLimitProperties properties,
                                  @Qualifier("authAdmissionClock") Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cleanupBatchSize = properties.getCleanupBatchSize();
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        // Authentication failure/outer rollback must not erase admitted attempts.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(properties.getTransactionTimeoutSeconds());
    }

    @Override
    public long admit(List<Quota> quotas, boolean consume) {
        if (quotas == null || quotas.isEmpty() || quotas.size() > 4
                || quotas.stream().map(Quota::digest).distinct().count() != quotas.size()) {
            throw new IllegalArgumentException("Expected one to four distinct authentication dimensions");
        }
        Long result = transaction.execute(status -> {
            long wait = evaluate(quotas, consume);
            // Denied traffic must not create attacker-selected bucket rows (key-explosion protection).
            if (wait > 0) status.setRollbackOnly();
            return wait;
        });
        if (result == null) throw new IllegalStateException("Authentication admission returned no result");
        if (consume && result == 0) {
            // Cleanup never holds admission locks and cannot erase an already committed allowance.
            try {
                LocalDateTime cutoff = utc(clock.instant());
                List<String> expired = jdbc.queryForList("SELECT key_digest FROM auth_rate_limits "
                        + "WHERE expires_at < ? ORDER BY expires_at LIMIT ?", String.class, cutoff, cleanupBatchSize);
                if (!expired.isEmpty()) {
                    transaction.executeWithoutResult(status -> {
                        // Do not range-delete through the expiry index: that can invert admission's primary/index locks.
                        for (String digest : expired.stream().sorted().toList()) {
                            jdbc.queryForList("SELECT key_digest FROM auth_rate_limits WHERE key_digest = ? FOR UPDATE", digest);
                            jdbc.update("DELETE FROM auth_rate_limits WHERE key_digest = ? AND expires_at < ?", digest, cutoff);
                        }
                    });
                }
            } catch (DataAccessException | TransactionException exception) {
                log.warn("Expired authentication admission metadata cleanup deferred");
            }
        }
        return result;
    }

    private long evaluate(List<Quota> quotas, boolean consume) {
        // One database clock makes rolling windows independent of replica clock skew.
        Instant now = clock.instant();
        List<State> states = new ArrayList<>();
        long waitMillis = 0;
        // Stable ordering prevents A/B versus B/A lock inversions across simultaneous requests.
        for (Quota quota : quotas.stream().sorted(Comparator.comparing(Quota::digest)).toList()) {
            if (consume) {
                jdbc.update("INSERT INTO auth_rate_limits "
                                + "(key_digest, dimension, admissions, expires_at) VALUES (?, ?, '[]', ?) "
                                + "ON DUPLICATE KEY UPDATE key_digest = key_digest",
                        quota.digest(), quota.dimension(), utc(now.plus(quota.window())));
            }
            List<State> rows = jdbc.query("SELECT admissions, window_started_at FROM auth_rate_limits "
                            + "WHERE key_digest = ?" + (consume ? " FOR UPDATE" : ""),
                    (rs, index) -> new State(quota, decode(rs.getString(1)),
                            rs.getObject(2, LocalDateTime.class)), quota.digest());
            State state = rows.isEmpty() ? new State(quota, new ArrayList<>(), null) : rows.get(0);
            states.add(state);
        }
        now = clock.instant();
        for (State state : states) {
            Quota quota = state.quota;
            state.prune(now);
            if (state.admissions.size() >= quota.requests()) {
                long availableAt = quota.rolling()
                        ? state.admissions.get(state.admissions.size() - quota.requests()) + quota.window().toMillis()
                        : state.started.toInstant(ZoneOffset.UTC).plus(quota.window()).toEpochMilli();
                waitMillis = Math.max(waitMillis, availableAt - now.toEpochMilli());
            }
        }
        if (waitMillis > 0) return Math.max(1, (waitMillis + 999) / 1000);
        if (!consume) return 0;
        for (State state : states) {
            if (state.started == null) state.started = utc(now);
            state.admissions.add(now.toEpochMilli());
            Instant expiry = state.quota.rolling() ? now.plus(state.quota.window())
                    : state.started.toInstant(ZoneOffset.UTC).plus(state.quota.window());
            jdbc.update("UPDATE auth_rate_limits SET admissions = ?, window_started_at = ?, expires_at = ? "
                            + "WHERE key_digest = ?", encode(state.admissions), state.started,
                    utc(expiry), state.quota.digest());
        }
        return 0;
    }

    private List<Long> decode(String json) {
        try {
            if (json == null || json.length() > 200000) throw new IllegalStateException("Oversized limiter state");
            List<Long> values = mapper.readValue(json, new TypeReference<ArrayList<Long>>() { });
            if (values == null || values.size() > 10000 || values.stream().anyMatch(value -> value == null || value < 0)) {
                throw new IllegalStateException("Invalid limiter timestamps");
            }
            values.sort(Long::compareTo);
            return values;
        }
        catch (Exception exception) { throw new IllegalStateException("Invalid authentication limiter state", exception); }
    }
    private String encode(List<Long> values) {
        try { return mapper.writeValueAsString(values); }
        catch (Exception exception) { throw new IllegalStateException("Cannot encode authentication limiter state", exception); }
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }

    private static final class State {
        final Quota quota;
        final List<Long> admissions;
        LocalDateTime started;
        State(Quota quota, List<Long> admissions, LocalDateTime started) {
            this.quota = quota;
            this.admissions = admissions;
            this.started = started;
        }
        void prune(Instant now) {
            if (quota.rolling()) {
                admissions.removeIf(time -> time <= now.minus(quota.window()).toEpochMilli());
            } else if (started == null || !now.isBefore(started.toInstant(ZoneOffset.UTC).plus(quota.window()))) {
                admissions.clear();
                started = null;
            }
        }
    }
}
