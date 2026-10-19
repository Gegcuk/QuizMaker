package uk.gegc.quizmaker.features.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public class AuthRateLimitProperties {
    private Map<String, Limit> limits = new LinkedHashMap<>();
    private String keySecret;
    private int transactionTimeoutSeconds = 3;
    private int cleanupBatchSize = 100;

    public AuthRateLimitProperties() {
        rolling("login-account", 10, 60);
        rolling("login-account-network", 5, 60);
        rolling("login-network", 60, 60);
        rolling("registration-email", 3, 3600);
        rolling("registration-identity", 3, 3600);
        rolling("registration-network-burst", 30, 60);
        rolling("registration-network-sustained", 60, 3600);
        rolling("refresh-session", 10, 60);
        rolling("refresh-user", 30, 60);
        rolling("refresh-network", 300, 60);
        fixed("change-password", 3);
        fixed("forgot-password", 5);
        fixed("reset-password", 5);
        fixed("verify-email", 10);
        fixed("resend-verification", 5);
        fixed("oauth-code-exchange-ip", 30);
        fixed("oauth-code-exchange-code", 5);
    }

    private void rolling(String name, int requests, long seconds) {
        limits.put(name, new Limit(requests, seconds, true));
    }

    private void fixed(String name, int requests) {
        limits.put(name, new Limit(requests, 60, false));
    }

    public void setLimits(Map<String, Limit> overrides) {
        Map<String, Limit> merged = new AuthRateLimitProperties().getLimits();
        if (overrides == null) { this.limits = null; return; }
        overrides.forEach((name, override) -> {
            Limit baseline = merged.get(name);
            if (override != null && baseline != null) {
                if (override.requests == null) override.requests = baseline.requests;
                if (override.windowSeconds == null) override.windowSeconds = baseline.windowSeconds;
                if (override.rolling == null) override.rolling = baseline.rolling;
            }
            merged.put(name, override);
        });
        this.limits = merged;
    }

    @PostConstruct
    public void validate() {
        if (keySecret == null || keySecret.length() < 32) {
            throw new IllegalStateException("Authentication limiter key secret must contain at least 32 characters");
        }
        if (transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 30
                || cleanupBatchSize < 1 || cleanupBatchSize > 1000) {
            throw new IllegalStateException("Invalid authentication limiter storage bounds");
        }
        var defaults = new AuthRateLimitProperties().getLimits();
        if (limits == null || !limits.keySet().equals(defaults.keySet())) {
            throw new IllegalStateException("Authentication limiter configuration must name every supported dimension");
        }
        limits.forEach((name, limit) -> {
            if (limit == null || limit.requests == null || limit.windowSeconds == null || limit.rolling == null || limit.requests < 1 || limit.requests > 10000
                    || limit.windowSeconds < 1 || limit.windowSeconds > 86400
                    || !limit.rolling.equals(defaults.get(name).rolling)) {
                throw new IllegalStateException("Invalid authentication limiter policy: " + name);
            }
        });
    }

    @Getter
    @Setter
    public static class Limit {
        private Integer requests;
        private Long windowSeconds;
        private Boolean rolling;
        public Limit() { }
        public Limit(int requests, long windowSeconds, boolean rolling) {
            this.requests = requests;
            this.windowSeconds = windowSeconds;
            this.rolling = rolling;
        }
        public boolean isRolling() { return Boolean.TRUE.equals(rolling); }
        public Duration window() { return Duration.ofSeconds(windowSeconds); }
    }
}
