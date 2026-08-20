package uk.gegc.quizmaker.features.auth.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OAuth exchange configuration")
class OAuth2ExchangePropertiesTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final String CLIENT_ID = "quizzence-web";
    private static final String REDIRECT_URI = "https://www.quizzence.com/oauth2/redirect";

    private OAuth2ExchangeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OAuth2ExchangeProperties();
        properties.setRedirectUri(REDIRECT_URI);
        OAuth2ExchangeProperties.Client client = new OAuth2ExchangeProperties.Client();
        client.setRedirectUri(REDIRECT_URI);
        properties.getExchange().getClients().put(CLIENT_ID, client);
    }

    @Test
    @DisplayName("secure defaults use a two-minute code and no legacy token redirect")
    void defaults_areSecureOnly() {
        assertThat(properties.getExchange().getCodeTtl()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.isLegacyRedirectAllowed(NOW)).isFalse();
        properties.validateConfiguration(NOW);
    }

    @Test
    @DisplayName("the configured exchange lifetime cannot exceed the approved two minutes")
    void codeTtl_overTwoMinutes_isRejected() {
        properties.getExchange().setCodeTtl(Duration.ofMinutes(2).plusMillis(1));

        assertThatThrownBy(() -> properties.validateConfiguration(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth exchange code TTL must be greater than zero and no more than two minutes");
    }

    @Test
    @DisplayName("legacy URL tokens require a future absolute deadline no more than seven days away")
    void legacyWindow_isDatedAndBounded() {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW.plus(Duration.ofDays(7)));
        assertThat(properties.isLegacyRedirectAllowed(NOW)).isTrue();
        properties.validateConfiguration(NOW);

        properties.getExchange().setLegacyTokenRedirectUntil(NOW.plus(Duration.ofDays(7)).plusSeconds(1));
        assertThat(properties.isLegacyRedirectAllowed(NOW)).isFalse();
        assertThatThrownBy(() -> properties.validateConfiguration(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Legacy OAuth token redirects cannot be enabled for more than seven days");
    }

    @Test
    @DisplayName("the deadline disables legacy redirects at the exact instant")
    void legacyWindow_atDeadline_isDisabled() {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW);

        assertThat(properties.isLegacyRedirectAllowed(NOW)).isFalse();
    }

    @Test
    @DisplayName("client and redirect bindings use exact allowlisted values")
    void requireClient_requiresExactBinding() {
        assertThat(properties.requireClient(CLIENT_ID, REDIRECT_URI)).isNotNull();

        assertThatThrownBy(() -> properties.requireClient(CLIENT_ID, REDIRECT_URI + "/other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OAuth client or redirect is not allowed");
        assertThatThrownBy(() -> properties.requireClient("unknown", REDIRECT_URI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OAuth client or redirect is not allowed");
    }

    @Test
    @DisplayName("redirects with fragments, query data, or non-local HTTP fail configuration")
    void validateConfiguration_unsafeRedirect_rejects() {
        properties.getExchange().getClients().get(CLIENT_ID)
                .setRedirectUri("http://example.com/oauth2/redirect?token=unsafe#fragment");

        assertThatThrownBy(() -> properties.validateConfiguration(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact HTTPS URIs");
    }
}
