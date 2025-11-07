package uk.gegc.quizmaker.features.auth.infra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OAuthTokenCryptoService Unit Tests")
class OAuthTokenCryptoServiceTest {

    private OAuthTokenCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new OAuthTokenCryptoService("lrPUVT2UZTcTqiRvLjrl0XWrnTl+BV5FEbSqxzXwKYE=");
        cryptoService.init();
    }

    @Test
    @DisplayName("encrypt/decrypt: when valid token then round-trips correctly")
    void encryptDecrypt_RoundTrip() {
        String cipherText = cryptoService.encrypt("sensitive-token");

        assertThat(cipherText)
                .isNotBlank()
                .isNotEqualTo("sensitive-token");
        assertThat(cryptoService.decrypt(cipherText)).isEqualTo("sensitive-token");
    }

    @Test
    @DisplayName("encrypt: when blank input then returns null")
    void encrypt_BlankInput_ReturnsNull() {
        assertThat(cryptoService.encrypt("   ")).isNull();
        assertThat(cryptoService.encrypt("")).isNull();
    }

    @Test
    @DisplayName("encrypt: when null input then returns null")
    void encrypt_NullInput_ReturnsNull() {
        assertThat(cryptoService.encrypt(null)).isNull();
    }

    @Test
    @DisplayName("decrypt: when blank input then returns null")
    void decrypt_BlankInput_ReturnsNull() {
        assertThat(cryptoService.decrypt("   ")).isNull();
        assertThat(cryptoService.decrypt("")).isNull();
    }

    @Test
    @DisplayName("decrypt: when null input then returns null")
    void decrypt_NullInput_ReturnsNull() {
        assertThat(cryptoService.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("encrypt: when long token then encrypts successfully")
    void encrypt_LongToken_EncryptsSuccessfully() {
        String longToken = "a".repeat(1000);
        String encrypted = cryptoService.encrypt(longToken);
        
        assertThat(encrypted).isNotNull();
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo(longToken);
    }

    @Test
    @DisplayName("encrypt: when token with special characters then encrypts successfully")
    void encrypt_SpecialCharacters_EncryptsSuccessfully() {
        String tokenWithSpecialChars = "token!@#$%^&*()_+-=[]{}|;':\",./<>?`~";
        String encrypted = cryptoService.encrypt(tokenWithSpecialChars);
        
        assertThat(encrypted).isNotNull();
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo(tokenWithSpecialChars);
    }

    @Test
    @DisplayName("encrypt: when token with unicode then encrypts successfully")
    void encrypt_UnicodeCharacters_EncryptsSuccessfully() {
        String tokenWithUnicode = "token-with-émojis-🔒-and-中文";
        String encrypted = cryptoService.encrypt(tokenWithUnicode);
        
        assertThat(encrypted).isNotNull();
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo(tokenWithUnicode);
    }

    @Test
    @DisplayName("encrypt: when same token encrypted twice then produces different ciphertext")
    void encrypt_SameTokenTwice_DifferentCiphertext() {
        String token = "same-token";
        String encrypted1 = cryptoService.encrypt(token);
        String encrypted2 = cryptoService.encrypt(token);
        
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        assertThat(cryptoService.decrypt(encrypted1)).isEqualTo(token);
        assertThat(cryptoService.decrypt(encrypted2)).isEqualTo(token);
    }

    @Test
    @DisplayName("decrypt: when invalid Base64 then throws exception")
    void decrypt_InvalidBase64_ThrowsException() {
        assertThatThrownBy(() -> cryptoService.decrypt("not-valid-base64!@#"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt OAuth token");
    }

    @Test
    @DisplayName("decrypt: when corrupted ciphertext then throws exception")
    void decrypt_CorruptedCiphertext_ThrowsException() {
        String encrypted = cryptoService.encrypt("valid-token");
        String corrupted = encrypted.substring(0, encrypted.length() - 5) + "XXXXX";
        
        assertThatThrownBy(() -> cryptoService.decrypt(corrupted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt OAuth token");
    }

    @Test
    @DisplayName("init: when key too short then throws exception")
    void init_KeyTooShort_ThrowsException() {
        OAuthTokenCryptoService shortKeyService = new OAuthTokenCryptoService("dG9vc2hvcnQ="); // "tooshort" in Base64
        
        assertThatThrownBy(() -> shortKeyService.init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must supply at least 256 bits");
    }

    @Test
    @DisplayName("init: when null secret then throws exception")
    void init_NullSecret_ThrowsException() {
        OAuthTokenCryptoService nullSecretService = new OAuthTokenCryptoService(null);
        
        assertThatThrownBy(() -> nullSecretService.init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is required");
    }

    @Test
    @DisplayName("init: when blank secret then throws exception")
    void init_BlankSecret_ThrowsException() {
        OAuthTokenCryptoService blankSecretService = new OAuthTokenCryptoService("   ");
        
        assertThatThrownBy(() -> blankSecretService.init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is required");
    }

    @Test
    @DisplayName("init: when non-Base64 secret then uses raw bytes")
    void init_NonBase64Secret_UsesRawBytes() {
        // 32+ character string that's not valid Base64
        String nonBase64Secret = "this-is-not-base64-but-long-enough-for-256-bits-of-entropy";
        OAuthTokenCryptoService rawBytesService = new OAuthTokenCryptoService(nonBase64Secret);
        rawBytesService.init();
        
        // Should work with raw bytes (no exception)
        String encrypted = rawBytesService.encrypt("test-token");
        assertThat(encrypted).isNotNull();
        assertThat(rawBytesService.decrypt(encrypted)).isEqualTo("test-token");
    }

    @Test
    @DisplayName("init: when key longer than 32 bytes then truncates to 32 bytes")
    void init_LongKey_TruncatesTo32Bytes() {
        // Base64 encoded 48 bytes
        String longKey = "lrPUVT2UZTcTqiRvLjrl0XWrnTl+BV5FEbSqxzXwKYE1234567890ABCDEF=";
        OAuthTokenCryptoService longKeyService = new OAuthTokenCryptoService(longKey);
        longKeyService.init();
        
        // Should work after truncation
        String encrypted = longKeyService.encrypt("test-token");
        assertThat(encrypted).isNotNull();
        assertThat(longKeyService.decrypt(encrypted)).isEqualTo("test-token");
    }

    @Test
    @DisplayName("encrypt: when JWT-like token then encrypts successfully")
    void encrypt_JwtLikeToken_EncryptsSuccessfully() {
        String jwtLikeToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String encrypted = cryptoService.encrypt(jwtLikeToken);
        
        assertThat(encrypted).isNotNull();
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo(jwtLikeToken);
    }

    @Test
    @DisplayName("encrypt/decrypt: preserves whitespace in tokens")
    void encryptDecrypt_PreservesWhitespace() {
        String tokenWithSpaces = "token with spaces\nand\tnewlines";
        String encrypted = cryptoService.encrypt(tokenWithSpaces);
        
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo(tokenWithSpaces);
    }

    @Test
    @DisplayName("encrypt: produces Base64 encoded output")
    void encrypt_ProducesBase64Output() {
        String token = "test-token";
        String encrypted = cryptoService.encrypt(token);
        
        // Should be valid Base64
        assertThat(encrypted).matches("^[A-Za-z0-9+/]+=*$");
    }
}
