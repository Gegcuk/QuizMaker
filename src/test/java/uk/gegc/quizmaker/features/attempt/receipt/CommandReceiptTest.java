package uk.gegc.quizmaker.features.attempt.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.*;

class CommandReceiptTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Test
    void canonicalIdentityIgnoresObjectOrderAndNumericSpellingButPreservesSemanticDifferences() throws Exception {
        assertThat(fingerprint("{\"b\":2.00,\"a\":1}" )).isEqualTo(fingerprint("{\"a\":1.0,\"b\":2}"));
        // Independent SHA-256 golden for the exact UTF-8 canonical object {"a":1,"b":2}.
        assertThat(fingerprint("{\"b\":2,\"a\":1}").digest())
                .isEqualTo("43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777");
        assertThat(fingerprint("{\"a\":-0.0}")).isEqualTo(fingerprint("{\"a\":0}"));
        assertThat(fingerprint("{\"a\":[1,2]}")).isNotEqualTo(fingerprint("{\"a\":[2,1]}"));
        assertThat(fingerprint("{\"a\":null}")).isNotEqualTo(fingerprint("{}"));
        assertThat(fingerprint("{\"a\":\"1\"}")).isNotEqualTo(fingerprint("{\"a\":1}"));
        assertThat(fingerprint("{\"a\":\"é\"}")).isNotEqualTo(fingerprint("{\"a\":\"é\"}"));
        assertThat(fingerprint("{\"a\":true}")).isNotEqualTo(fingerprint("{\"a\":false}"));
        assertThat(fingerprint("{\"x\":{\"b\":2,\"a\":1}}")).isEqualTo(fingerprint("{\"x\":{\"a\":1,\"b\":2}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "true", "1", "\"text\""})
    void onlyValidatedObjectInputsCanBeFingerprinted(String value) {
        rejected(() -> fingerprint(value), INVALID_INPUT);
    }

    @Test
    void canonicalizationIsBoundedAndRejectsNonJsonNodes() {
        rejected(() -> CanonicalCommandInput.fingerprint(2, JSON.createObjectNode()), UNSUPPORTED_VERSION);
        rejected(() -> CanonicalCommandInput.fingerprint(1, null), INVALID_INPUT);
        rejected(() -> CanonicalCommandInput.fingerprint(1, JSON.createObjectNode().set("v", DoubleNode.valueOf(Double.NaN))), INVALID_INPUT);
        rejected(() -> CanonicalCommandInput.fingerprint(1, JSON.createObjectNode().set("v", new POJONode(new Object()))), INVALID_INPUT);
        rejected(() -> CanonicalCommandInput.fingerprint(1, JSON.createObjectNode().put("v", new byte[]{1})), INVALID_INPUT);
        rejected(() -> CanonicalCommandInput.fingerprint(1, JSON.createObjectNode().put("v", "x".repeat(5 * 1024 * 1024))), INVALID_INPUT);
        var nested = JSON.createObjectNode(); var current = nested;
        for (int i = 0; i < 66; i++) current = current.putObject("next");
        rejected(() -> CanonicalCommandInput.fingerprint(1, nested), INVALID_INPUT);
        var many = JSON.createObjectNode(); var values = many.putArray("values");
        for (int i = 0; i < 100_000; i++) values.add(i);
        rejected(() -> CanonicalCommandInput.fingerprint(1, many), INVALID_INPUT);
        assertThat(CanonicalCommandInput.fingerprint(1, JSON.createObjectNode().put("v", "supported"))).isNotNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " key", "key ", "a\nb", "a\tb", "ключ", "\u007f"})
    void keysAreOpaqueExactBoundedAndContainNoWhitespace(String value) { rejected(() -> Key.from(value), INVALID_INPUT); }

    @Test
    void keyBoundAndCaseAreExplicitAndRawKeysAreNotRetained() {
        assertThat(Key.from("x".repeat(200)).digest()).hasSize(64);
        rejected(() -> Key.from("x".repeat(201)), INVALID_INPUT);
        assertThat(Key.from("KEY")).isNotEqualTo(Key.from("key"));
        assertThat(Key.from("key").toString()).isEqualTo("CommandKey[redacted]");
        rejected(() -> new Key("A".repeat(64)), INVALID_INPUT);
        rejected(() -> new Fingerprint(null), INVALID_INPUT);
    }

    @Test
    void d08StartsAtTerminalStateAndExpiresAtExactlyThirtyDays() {
        assertThat(CommandReceipt.expired(null, NOW.plusSeconds(100_000_000))).isFalse();
        var expiry = NOW.plusSeconds(30L * 24 * 60 * 60);
        assertThat(CommandReceipt.expired(NOW, expiry.minusNanos(1000))).isFalse();
        assertThat(CommandReceipt.expired(NOW, expiry)).isTrue();
        assertThat(CommandReceipt.expired(NOW, expiry.plusSeconds(1))).isTrue();
        assertThat(CommandReceipt.time(NOW.plusNanos(123456789))).isEqualTo(NOW.plusNanos(123456000));
        rejected(() -> CommandReceipt.time(null), INVALID_INPUT);
        rejected(() -> CommandReceipt.time(Instant.MIN), INVALID_INPUT);
    }

    @Test
    void metadataIsTypedVersionedAndRedacted() {
        var input = CanonicalCommandInput.fingerprint(1, JSON.createObjectNode().put("answer", "PRIVATE"));
        var key = Key.from("PRIVATE");
        var request = new Request(1, ID, ID, AttemptCommands.Type.START, ID, 0, key, input);
        var ack = new Acknowledgement(1, ID, ID, 1);
        var stored = new Stored(ID, request, ack, NOW);
        assertThat(new Result(stored, false).toString() + stored + request + ack + input + key)
                .doesNotContain("PRIVATE", ID.toString(), input.digest(), key.digest());
        rejected(() -> new Request(2, ID, ID, AttemptCommands.Type.START, ID, 0, key, input), UNSUPPORTED_VERSION);
        rejected(() -> new Request(1, ID, ID, AttemptCommands.Type.VIEW_ATTEMPT, ID, 0, key, input), INVALID_INPUT);
        rejected(() -> new Request(1, null, ID, AttemptCommands.Type.START, ID, 0, key, input), INVALID_INPUT);
        rejected(() -> new Request(1, ID, ID, AttemptCommands.Type.START, ID, -1, key, input), INVALID_INPUT);
        rejected(() -> new Acknowledgement(2, ID, ID, 1), UNSUPPORTED_VERSION);
        rejected(() -> new Acknowledgement(1, null, ID, 1), INVALID_INPUT);
        rejected(() -> new Acknowledgement(1, ID, ID, 0), INVALID_INPUT);
        rejected(() -> new Stored(null, request, ack, NOW), INVALID_INPUT);
        rejected(() -> new Result(null, true), INVALID_INPUT);
    }

    static Fingerprint fingerprint(String value) throws Exception { return CanonicalCommandInput.fingerprint(1, JSON.readTree(value)); }
    static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ReceiptException.Reason reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ReceiptException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        });
    }
}
