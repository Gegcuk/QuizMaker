package uk.gegc.quizmaker.features.auth.application;

import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRequestException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

public final class OAuthPkcePolicy {

    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9\\-._~]{43,128}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private OAuthPkcePolicy() {
    }

    public static void requireChallenge(String challenge, String method) {
        if (!"S256".equals(method) || challenge == null || !CHALLENGE.matcher(challenge).matches()) {
            throw new OAuthExchangeRequestException();
        }
    }

    public static void requireVerifier(String verifier) {
        if (verifier == null || !VERIFIER.matcher(verifier).matches()) {
            throw new OAuthExchangeRequestException();
        }
    }

    public static void requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new OAuthExchangeRequestException();
        }
    }

    public static String challenge(String verifier) {
        requireVerifier(verifier);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(
                verifier.getBytes(StandardCharsets.US_ASCII)
        ));
    }

    public static boolean matches(String verifier, String expectedChallenge) {
        byte[] actual = challenge(verifier).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedChallenge.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    public static String hashRawCode(String rawCode) {
        requireCode(rawCode);
        return java.util.HexFormat.of().formatHex(sha256(rawCode.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
