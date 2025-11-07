package uk.gegc.quizmaker.features.auth.infra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthTokenCryptoServiceTest {

    private OAuthTokenCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new OAuthTokenCryptoService("lrPUVT2UZTcTqiRvLjrl0XWrnTl+BV5FEbSqxzXwKYE=");
        cryptoService.init();
    }

    @Test
    @DisplayName("encrypt/decrypt should round-trip token values")
    void encryptDecrypt_RoundTrip() {
        String cipherText = cryptoService.encrypt("sensitive-token");

        assertThat(cipherText)
                .isNotBlank()
                .isNotEqualTo("sensitive-token");
        assertThat(cryptoService.decrypt(cipherText)).isEqualTo("sensitive-token");
    }

    @Test
    @DisplayName("encrypt should return null for empty input")
    void encrypt_NullOnEmptyInput() {
        assertThat(cryptoService.encrypt("   ")).isNull();
    }
}
