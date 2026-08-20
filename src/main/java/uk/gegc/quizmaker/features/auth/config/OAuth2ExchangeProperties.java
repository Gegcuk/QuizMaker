package uk.gegc.quizmaker.features.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Data
@Component
@ConfigurationProperties(prefix = "app.oauth2")
public class OAuth2ExchangeProperties {

    private static final Duration MAX_LEGACY_WINDOW = Duration.ofDays(7);
    private static final Duration MAX_CODE_TTL = Duration.ofMinutes(2);

    private String redirectUri = "http://localhost:3000/oauth2/redirect";
    private Exchange exchange = new Exchange();

    public boolean isLegacyRedirectAllowed(Instant now) {
        Instant deadline = exchange.getLegacyTokenRedirectUntil();
        return deadline != null
                && now.isBefore(deadline)
                && !deadline.isAfter(now.plus(MAX_LEGACY_WINDOW));
    }

    public Client requireClient(String clientId, String requestedRedirectUri) {
        Client client = exchange.getClients().get(clientId);
        if (client == null || !Objects.equals(client.getRedirectUri(), requestedRedirectUri)) {
            throw new IllegalArgumentException("OAuth client or redirect is not allowed");
        }
        validateRedirectUri(requestedRedirectUri);
        return client;
    }

    public void validateConfiguration(Instant now) {
        Duration codeTtl = exchange.getCodeTtl();
        if (codeTtl == null || codeTtl.isNegative() || codeTtl.isZero()
                || codeTtl.compareTo(MAX_CODE_TTL) > 0) {
            throw new IllegalStateException("OAuth exchange code TTL must be greater than zero and no more than two minutes");
        }
        validateRedirectUri(redirectUri);
        if (exchange.getClients().isEmpty()) {
            throw new IllegalStateException("At least one OAuth exchange client must be configured");
        }
        exchange.getClients().forEach((clientId, client) -> {
            if (clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalStateException("OAuth exchange client IDs must use a bounded safe format");
            }
            validateRedirectUri(client.getRedirectUri());
        });
        Instant deadline = exchange.getLegacyTokenRedirectUntil();
        if (deadline != null && deadline.isAfter(now.plus(MAX_LEGACY_WINDOW))) {
            throw new IllegalStateException("Legacy OAuth token redirects cannot be enabled for more than seven days");
        }
    }

    private void validateRedirectUri(String value) {
        try {
            URI uri = URI.create(value);
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !localHttp)
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("OAuth redirect URIs must be exact HTTPS URIs without query or fragment", exception);
        }
    }

    @Data
    public static class Exchange {
        private Duration codeTtl = Duration.ofMinutes(2);
        private Instant legacyTokenRedirectUntil;
        private Map<String, Client> clients = new LinkedHashMap<>();
    }

    @Data
    public static class Client {
        private String redirectUri;
    }
}
