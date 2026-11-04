package uk.gegc.quizmaker.features.attempt.domain.model.receipt;

import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.require;

/** Dark authenticated-command v1 values. None of these values grants access or disclosure. */
public final class CommandReceipt {
    public static final int VERSION = 1;
    public static final Duration RETENTION = Duration.ofDays(30);
    public static final int MAX_CLEANUP_BATCH = 100;
    private static final Instant MIN = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    private CommandReceipt() { }

    /** Internal bound for the future Idempotency-Key transport; exact, case-sensitive, never trimmed. */
    public record Key(String digest) {
        public Key { require(hex(digest), INVALID_INPUT); }
        public static Key from(String value) {
            require(value != null && !value.isEmpty() && value.length() <= 200
                    && value.chars().allMatch(c -> c >= 33 && c <= 126), INVALID_INPUT);
            return new Key(HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.US_ASCII))));
        }
        @Override public String toString() { return "CommandKey[redacted]"; }
    }

    public record Fingerprint(String digest) {
        public Fingerprint { require(hex(digest), INVALID_INPUT); }
        @Override public String toString() { return "CommandFingerprint[redacted]"; }
    }

    /**
     * User is server-resolved. Its key namespace spans operations/contexts, so reusing a key for
     * another target/context/operation conflicts. Different users have independent namespaces.
     * START targets an immutable revision; other operations target their attempt or form item.
     */
    public record Request(int version, UUID userId, UUID contextId, AttemptCommands.Type operation,
                          UUID targetId, long expectedVersion, Key key, Fingerprint input) {
        public Request {
            require(version == VERSION, UNSUPPORTED_VERSION);
            require(userId != null && contextId != null && targetId != null && operation != null
                    && operation != AttemptCommands.Type.VIEW_ATTEMPT && expectedVersion >= 0
                    && key != null && input != null, INVALID_INPUT);
        }
        @Override public String toString() { return "ReceiptRequest[version=" + version + ",operation=" + operation + "]"; }
    }

    /** References only: never an arbitrary response body, answer, score, explanation or capability. */
    public record Acknowledgement(int version, UUID attemptId, UUID resultId, long stateVersion) {
        public Acknowledgement {
            require(version == VERSION, UNSUPPORTED_VERSION);
            require(attemptId != null && resultId != null && stateVersion > 0, INVALID_INPUT);
        }
        @Override public String toString() { return "CommandAcknowledgement[redacted]"; }
    }

    public record Stored(UUID id, Request request, Acknowledgement acknowledgement, Instant recordedAt) {
        public Stored {
            require(id != null && request != null && acknowledgement != null, INVALID_INPUT);
            recordedAt = time(recordedAt);
        }
        @Override public String toString() { return "CommandReceipt[redacted]"; }
    }

    public record Result(Stored receipt, boolean replayed) {
        public Result { require(receipt != null, INVALID_INPUT); }
        @Override public String toString() { return "ReceiptResult[replayed=" + replayed + "]"; }
    }

    public static boolean expired(Instant terminalAt, Instant now) {
        time(now);
        return terminalAt != null && !now.isBefore(time(terminalAt).plus(RETENTION));
    }

    public static Instant time(Instant value) {
        require(value != null && !value.isBefore(MIN) && !value.isAfter(MAX), INVALID_INPUT);
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    public static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static boolean hex(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
