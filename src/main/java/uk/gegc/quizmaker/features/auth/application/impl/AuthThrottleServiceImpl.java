package uk.gegc.quizmaker.features.auth.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gegc.quizmaker.features.auth.application.AuthRateLimitStore;
import uk.gegc.quizmaker.features.auth.application.AuthRateLimitStore.Quota;
import uk.gegc.quizmaker.features.auth.application.AuthThrottleService;
import uk.gegc.quizmaker.features.auth.config.AuthRateLimitProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.AuthRateLimitUnavailableException;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
public class AuthThrottleServiceImpl implements AuthThrottleService {
    private static final Set<String> EXISTING = Set.of("change-password", "forgot-password", "reset-password",
            "verify-email", "resend-verification", "oauth-code-exchange-ip", "oauth-code-exchange-code");
    private final AuthRateLimitStore store;
    private final AuthRateLimitProperties properties;
    private final UserRepository users;
    private final JwtTokenService tokens;
    private final TrustedProxyUtil proxies;
    private final MeterRegistry metrics;
    private final SecretKeySpec hashingKey;

    public AuthThrottleServiceImpl(AuthRateLimitStore store, AuthRateLimitProperties properties,
                                   UserRepository users, JwtTokenService tokens, TrustedProxyUtil proxies,
                                   MeterRegistry metrics) {
        this.store = store;
        this.properties = properties;
        this.users = users;
        this.tokens = tokens;
        this.proxies = proxies;
        this.metrics = metrics;
        // Domain separation permits using the already-delivered JWT secret as a compatible default.
        // Operators can supply an independent limiter secret without changing application code.
        hashingKey = new SecretKeySpec(hmac(properties.getKeySecret().getBytes(StandardCharsets.UTF_8),
                "quizmaker:auth-rate-limits:v1"), "HmacSHA256");
    }

    @Override
    public void login(String identifier, String network) {
        protect("login", () -> {
            Quota net = quota("login-network", network(network));
            String lookup = bounded(identifier);
            // Match credential lookup exactly, including case-sensitive username collations and precedence.
            // Both projections still run for known and unknown identifiers; only unknown keys are normalized.
            var usernameId = users.findAuthThrottleIdByUsername(lookup);
            var emailId = users.findAuthThrottleIdByEmail(lookup);
            String account = usernameId.or(() -> emailId)
                    .map(id -> "account:" + id).orElse("unknown:" + normalized(lookup));
            check(List.of(net, quota("login-account", account),
                    quota("login-account-network", account + "|" + network(network))), true);
        });
    }

    @Override
    public void registration(String email, String username, String network) {
        protect("register", () -> check(registrationQuotas(email, "username:" + normalized(username), network), true));
    }

    @Override
    public void providerRegistration(String provider, String subject, String email) {
        protect("register", () -> {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes context)) {
                throw new IllegalStateException("Missing authentication request context");
            }
            String network = proxies.getClientIp(context.getRequest());
            check(registrationQuotas(email, "provider:" + normalized(provider) + ":" + bounded(subject), network), true);
        });
    }

    private List<Quota> registrationQuotas(String email, String identity, String network) {
        List<Quota> quotas = new ArrayList<>(List.of(quota("registration-identity", identity),
                quota("registration-network-burst", network(network)),
                quota("registration-network-sustained", network(network))));
        if (email != null && !email.isBlank()) quotas.add(quota("registration-email", normalized(email)));
        return quotas;
    }

    @Override
    public void refresh(String refreshToken, String network) {
        protect("refresh", () -> {
            Quota net = quota("refresh-network", network(network));
            var claims = tokens.validateRefreshToken(refreshToken);
            List<Quota> quotas = new ArrayList<>(List.of(net));
            claims.ifPresent(identity -> {
                quotas.add(quota("refresh-session", identity.sessionId().toString()));
                quotas.add(quota("refresh-user", identity.userId().toString()));
            });
            // Invalid tokens consume the network budget; unverified claims never select an identity bucket.
            check(quotas, true);
        });
    }

    @Override
    public void existing(String operation, String key) {
        if (!EXISTING.contains(operation)) throw new IllegalArgumentException("Unknown authentication operation");
        protect(operation, () -> check(List.of(quota(operation, normalized(key))), true));
    }

    private Quota quota(String dimension, String identity) {
        var limit = properties.getLimits().get(dimension);
        String digest = HexFormat.of().formatHex(hmac(hashingKey.getEncoded(), dimension + "\0" + identity));
        return new Quota(digest, dimension, limit.getRequests(), limit.window(), limit.isRolling());
    }

    private void check(List<Quota> quotas, boolean consume) {
        long retryAfter = store.admit(quotas, consume);
        if (retryAfter > 0) throw new RateLimitExceededException("Too many authentication requests. Please retry later.", retryAfter);
    }

    private void protect(String operation, Runnable action) {
        try {
            action.run();
            outcome(operation, "allowed");
        } catch (RateLimitExceededException exception) {
            outcome(operation, "throttled");
            throw exception;
        } catch (DataAccessException | TransactionException | IllegalStateException exception) {
            outcome(operation, "store_error");
            log.warn("Authentication admission unavailable: operation={}", operation);
            throw new AuthRateLimitUnavailableException(exception);
        }
    }

    private void outcome(String operation, String outcome) {
        metrics.counter("auth.throttle.requests", "operation", operation, "outcome", outcome).increment();
    }
    private static String network(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalStateException("Missing trusted authentication network signal");
        }
        return value;
    }
    private static String normalized(String value) { return bounded(value).strip().toLowerCase(Locale.ROOT); }
    private static String bounded(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) {
            throw new IllegalStateException("Missing bounded authentication identity");
        }
        return value;
    }
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Authentication key hashing is unavailable", exception);
        }
    }
}
