package uk.gegc.quizmaker.features.auth.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.auth.application.AuthRateLimitStore.Quota;
import uk.gegc.quizmaker.features.auth.config.AuthRateLimitProperties;
import uk.gegc.quizmaker.features.auth.infra.ratelimit.MySqlAuthRateLimitStore;
import uk.gegc.quizmaker.features.auth.infra.ratelimit.DatabaseAuthClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MySqlAuthRateLimitStoreTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    final ObjectMapper mapper = new ObjectMapper();
    AuthRateLimitProperties properties;
    MySqlAuthRateLimitStore first;
    MySqlAuthRateLimitStore second;

    @BeforeEach
    void prepare() {
        jdbc.execute("DROP TABLE IF EXISTS auth_rate_limits");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V77__create_shared_auth_rate_limits.sql"))
                .execute(jdbc.getDataSource());
        properties = new AuthRateLimitProperties();
        properties.setCleanupBatchSize(2);
        first = new MySqlAuthRateLimitStore(jdbc, mapper, manager, properties, new DatabaseAuthClock(jdbc));
        second = new MySqlAuthRateLimitStore(jdbc, mapper, manager, properties, new DatabaseAuthClock(jdbc));
    }

    @AfterEach
    void cleanup() { jdbc.execute("DROP TABLE IF EXISTS auth_rate_limits"); }

    @Test
    void migrationCreatesOnlyBoundedHashedMetadataAndIndexedExpiry() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'auth_rate_limits' ORDER BY ordinal_position", String.class))
                .containsExactly("key_digest", "dimension", "admissions", "window_started_at", "expires_at");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'auth_rate_limits' AND index_name = 'idx_auth_rate_limits_expiry'", Integer.class)).isEqualTo(1);
        Quota quota = quota("synthetic-private-email@example.test", "login-account", 1, 60, true);
        assertThat(first.admit(List.of(quota), true)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM auth_rate_limits").toString())
                .doesNotContain("synthetic-private-email", "@", "password", "token");
    }

    @Test
    void twoIndependentInstancesNeverExceedAllowanceUnderContention() throws Exception {
        Quota global = quota("shared", "login-account", 5, 60, true);
        Quota networkA = quota("network-a", "login-network", 60, 60, true);
        Quota networkB = quota("network-b", "login-network", 60, 60, true);
        Quota pairA = quota("shared-network-a", "login-account-network", 5, 60, true);
        Quota pairB = quota("shared-network-b", "login-account-network", 5, 60, true);
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                MySqlAuthRateLimitStore instance = i % 2 == 0 ? first : second;
                List<Quota> dimensions = i % 2 == 0 ? List.of(global, networkA, pairA) : List.of(pairB, networkB, global);
                results.add(pool.submit(() -> { start.await(); return instance.admit(dimensions, true); }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Long> result : results) if (result.get(15, TimeUnit.SECONDS) == 0) allowed++;
            assertThat(allowed).isEqualTo(5);
            assertThat(size(global)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT SUM(JSON_LENGTH(admissions)) FROM auth_rate_limits "
                    + "WHERE dimension = 'login-network'", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT SUM(JSON_LENGTH(admissions)) FROM auth_rate_limits "
                    + "WHERE dimension = 'login-account-network'", Integer.class)).isEqualTo(5);
            MySqlAuthRateLimitStore restarted = new MySqlAuthRateLimitStore(jdbc, mapper, manager, properties, new DatabaseAuthClock(jdbc));
            assertThat(restarted.admit(List.of(global), true)).isBetween(1L, 60L);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void denialInOneDimensionDoesNotConsumeOtherDimensionsOrExtendExistingDeadline() {
        Quota account = quota("account", "login-account", 1, 60, true);
        Quota network = quota("network", "login-network", 60, 60, true);
        assertThat(first.admit(List.of(account, network), true)).isZero();
        LocalDateTime expiry = jdbc.queryForObject("SELECT expires_at FROM auth_rate_limits WHERE key_digest = ?", LocalDateTime.class, account.digest());
        assertThat(second.admit(List.of(network, account), true)).isPositive();
        assertThat(size(network)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_rate_limits WHERE key_digest = ?", LocalDateTime.class, account.digest())).isEqualTo(expiry);
    }

    @Test
    void rollingWindowExpiresIndividualAdmissionsAndRetryAfterRoundsUp() throws Exception {
        Quota quota = quota("rolling", "login-account", 2, 60, true);
        first.admit(List.of(quota), true);
        long now = databaseMillis();
        jdbc.update("UPDATE auth_rate_limits SET admissions = ? WHERE key_digest = ?",
                mapper.writeValueAsString(List.of(now - 61000, now - 30500)), quota.digest());
        assertThat(first.admit(List.of(quota), true)).isZero();
        assertThat(size(quota)).isEqualTo(2);
        assertThat(first.admit(List.of(quota), true)).isBetween(29L, 30L);
    }

    @Test
    void retryAfterUsesLongestWaitAcrossAllExhaustedDimensions() {
        Quota shortWindow = quota("short", "login-network", 1, 30, true);
        Quota longWindow = quota("long", "login-account", 1, 60, true);
        first.admit(List.of(shortWindow, longWindow), true);
        assertThat(second.admit(List.of(shortWindow, longWindow), true)).isBetween(59L, 60L);
        assertThat(size(shortWindow)).isEqualTo(1);
        assertThat(size(longWindow)).isEqualTo(1);
    }

    @Test
    void fixedRecoveryWindowRemainsAnchoredAtFirstAdmissionAndResetsAfterExpiry() {
        Quota quota = quota("fixed", "verify-email", 2, 60, false);
        first.admit(List.of(quota), true);
        LocalDateTime now = databaseNow();
        jdbc.update("UPDATE auth_rate_limits SET window_started_at = ?, admissions = ?, expires_at = ? WHERE key_digest = ?",
                now.minusSeconds(31), "[1,2]", now.plusSeconds(29), quota.digest());
        assertThat(first.admit(List.of(quota), true)).isBetween(28L, 29L);
        jdbc.update("UPDATE auth_rate_limits SET window_started_at = ? WHERE key_digest = ?", now.minusSeconds(61), quota.digest());
        assertThat(first.admit(List.of(quota), true)).isZero();
        assertThat(size(quota)).isEqualTo(1);
    }

    @Test
    void admissionSurvivesRollbackOfEnclosingAuthenticationTransaction() {
        Quota quota = quota("outer-rollback", "login-account", 1, 60, true);
        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.executeWithoutResult(transaction -> {
            assertThat(first.admit(List.of(quota), true)).isZero();
            transaction.setRollbackOnly();
        });
        assertThat(second.admit(List.of(quota), true)).isPositive();
    }

    @Test
    void schoolBurstFitsProfileAAndAdmissionUsesBoundedQueriesPerRequest() {
        Quota network = quota("school-router", "login-network", 60, 60, true);
        for (int i = 0; i < 25; i++) {
            assertThat(first.admit(List.of(network, quota("student-" + i, "login-account", 10, 60, true)), true)).isZero();
        }
        assertThat(size(network)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limits", Integer.class)).isEqualTo(26);
        assertThat(jdbc.queryForObject("SELECT MAX(JSON_LENGTH(admissions)) FROM auth_rate_limits", Integer.class)).isEqualTo(25);
    }

    @Test
    void cleanupDeletesOnlyBoundedExpiredRowsAndPreservesLiveAllowances() {
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO auth_rate_limits VALUES (?, 'login-account', '[]', NULL, ?)",
                    quota("expired-" + i, "login-account", 1, 60, true).digest(), databaseNow().minusHours(1));
        }
        Quota live = quota("live", "login-account", 1, 60, true);
        first.admit(List.of(live), true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limits WHERE expires_at < UTC_TIMESTAMP(6)", Integer.class)).isEqualTo(3);
        assertThat(size(live)).isEqualTo(1);
    }

    @Test
    void changedLimitReusesExistingStateAndReadOnlyProbeDoesNotAdmit() {
        Quota initial = quota("same", "login-account", 2, 60, true);
        assertThat(first.admit(List.of(initial), false)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limits", Integer.class)).isZero();
        first.admit(List.of(initial), true);
        first.admit(List.of(initial), true);
        Quota decreased = new Quota(initial.digest(), initial.dimension(), 1, initial.window(), true);
        assertThat(second.admit(List.of(decreased), true)).isPositive();
        assertThat(size(initial)).isEqualTo(2);
    }

    @Test
    void malformedPersistedStateFailsClosedAndDoesNotConsumeSiblingAllowance() {
        Quota quota = quota("corrupt", "login-account", 2, 60, true);
        first.admit(List.of(quota), true);
        jdbc.update("UPDATE auth_rate_limits SET admissions = '[null]' WHERE key_digest = ?", quota.digest());
        assertThatThrownBy(() -> second.admit(List.of(quota), true)).isInstanceOf(IllegalStateException.class);
    }


    @Test
    void rotatingIdentitiesCannotCreateBucketsWhenNetworkIsAlreadyExhausted() {
        Quota network = quota("exhausted-network", "registration-network-burst", 1, 60, true);
        first.admit(List.of(network), true);
        for (int i = 0; i < 20; i++) {
            assertThat(second.admit(List.of(network,
                    quota("rotating-identity-" + i, "registration-identity", 3, 3600, true)), true)).isPositive();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limits", Integer.class)).isEqualTo(1);
    }

    private int size(Quota quota) { return jdbc.queryForObject("SELECT JSON_LENGTH(admissions) FROM auth_rate_limits WHERE key_digest = ?", Integer.class, quota.digest()); }
    private LocalDateTime databaseNow() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class); }
    private long databaseMillis() { return databaseNow().toInstant(ZoneOffset.UTC).toEpochMilli(); }
    static Quota quota(String key, String dimension, int requests, long seconds, boolean rolling) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((dimension + ":" + key).getBytes(StandardCharsets.UTF_8)));
            return new Quota(digest, dimension, requests, Duration.ofSeconds(seconds), rolling);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
