package uk.gegc.quizmaker.features.auth.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OAuth PKCE policy")
class OAuthPkcePolicyTest {

    private static final String VERIFIER = "v".repeat(43);

    @Test
    @DisplayName("S256 derives an unpadded 43-character challenge and matches it safely")
    void challengeAndMatch_validVerifier_succeeds() {
        String challenge = OAuthPkcePolicy.challenge(VERIFIER);

        assertThat(challenge).matches("[A-Za-z0-9_-]{43}");
        assertThat(OAuthPkcePolicy.matches(VERIFIER, challenge)).isTrue();
        assertThat(OAuthPkcePolicy.matches("x".repeat(43), challenge)).isFalse();
    }

    @Test
    @DisplayName("the persisted lookup key is a fixed digest rather than the raw code")
    void hashRawCode_validCode_returnsSha256Hex() {
        String rawCode = "c".repeat(43);

        assertThat(OAuthPkcePolicy.hashRawCode(rawCode))
                .matches("[0-9a-f]{64}")
                .doesNotContain(rawCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",
            "contains a space but is deliberately longer than forty-three characters",
            "åaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    @DisplayName("malformed verifiers are rejected before persistence")
    void requireVerifier_malformed_rejects(String verifier) {
        assertThatThrownBy(() -> OAuthPkcePolicy.requireVerifier(verifier))
                .isInstanceOf(OAuthExchangeRequestException.class)
                .hasMessage("OAuth exchange request is not valid");
    }

    @Test
    @DisplayName("only an exact S256 challenge is accepted at login initiation")
    void requireChallenge_nonS256OrMalformed_rejects() {
        String challenge = OAuthPkcePolicy.challenge(VERIFIER);

        assertThatThrownBy(() -> OAuthPkcePolicy.requireChallenge(challenge, "plain"))
                .isInstanceOf(OAuthExchangeRequestException.class);
        assertThatThrownBy(() -> OAuthPkcePolicy.requireChallenge(challenge + "=", "S256"))
                .isInstanceOf(OAuthExchangeRequestException.class);
    }
}
