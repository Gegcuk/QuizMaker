package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts and decrypts OAuth provider tokens before persisting them.
 */
@Component
@Slf4j
public class OAuthTokenCryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int AUTH_TAG_LENGTH_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String configuredSecret;

    private SecretKey secretKey;

    public OAuthTokenCryptoService(
        @Value("${app.oauth2.token-secret}") String configuredSecret
    ) {
        this.configuredSecret = configuredSecret;
    }

    @PostConstruct
    void init() {
        byte[] keyBytes = decodeKey(configuredSecret);
        if (keyBytes.length < AES_256_KEY_BYTES) {
            throw new IllegalStateException("app.oauth2.token-secret must supply at least 256 bits of entropy");
        }
        if (keyBytes.length > AES_256_KEY_BYTES) {
            keyBytes = java.util.Arrays.copyOf(keyBytes, AES_256_KEY_BYTES);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt OAuth token", ex);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encryptedBytes = new byte[buffer.remaining()];
            buffer.get(encryptedBytes);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(encryptedBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt OAuth token", ex);
        }
    }

    private static byte[] decodeKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.oauth2.token-secret is required");
        }
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            log.warn("app.oauth2.token-secret is not valid Base64, falling back to raw bytes");
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
