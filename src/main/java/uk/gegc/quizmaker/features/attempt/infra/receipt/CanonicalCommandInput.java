package uk.gegc.quizmaker.features.attempt.infra.receipt;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.Fingerprint;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;

import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.require;

/**
 * Canonical JSON/SHA-256 v1 for already validated command inputs, never document versioning.
 * Object keys sort; arrays and strings retain order/value; finite numbers normalize by value.
 * Consumers own semantic normalization and duplicate-property rejection at their parsing boundary.
 */
public final class CanonicalCommandInput {
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private CanonicalCommandInput() { }

    public static Fingerprint fingerprint(int version, JsonNode input) {
        require(version == 1, UNSUPPORTED_VERSION);
        require(input != null && input.isObject(), INVALID_INPUT);
        var sink = new DigestSink();
        try (var json = JSON.createGenerator(sink)) {
            write(json, input, 0, new int[]{0});
        } catch (IOException | RuntimeException failure) {
            throw new ReceiptException(INVALID_INPUT);
        }
        return new Fingerprint(HexFormat.of().formatHex(sink.digest.digest()));
    }

    private static void write(JsonGenerator json, JsonNode node, int depth, int[] count) throws IOException {
        require(depth <= 64 && ++count[0] <= 100_000, INVALID_INPUT);
        if (node.isObject()) {
            require(node.size() <= 100_000 - count[0], INVALID_INPUT);
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            json.writeStartObject();
            for (String name : names) { json.writeFieldName(name); write(json, node.get(name), depth + 1, count); }
            json.writeEndObject();
        } else if (node.isArray()) {
            json.writeStartArray();
            for (var item : node) write(json, item, depth + 1, count);
            json.writeEndArray();
        } else if (node.isTextual()) json.writeString(node.textValue());
        else if (node.isBoolean()) json.writeBoolean(node.booleanValue());
        else if (node.isNull()) json.writeNull();
        else if (node.isNumber()) {
            require(!node.isFloatingPointNumber() || Double.isFinite(node.doubleValue()), INVALID_INPUT);
            var number = node.decimalValue().stripTrailingZeros();
            require(Math.abs((long) number.scale()) <= MAX_BYTES && number.precision() <= MAX_BYTES, INVALID_INPUT);
            json.writeNumber(number.toPlainString());
        } else throw new ReceiptException(INVALID_INPUT);
    }

    private static final class DigestSink extends OutputStream {
        private final MessageDigest digest = CommandReceipt.sha256();
        private int written;
        @Override public void write(int value) {
            require(++written <= MAX_BYTES, INVALID_INPUT); digest.update((byte) value);
        }
        @Override public void write(byte[] value, int offset, int length) {
            require(length <= MAX_BYTES - written, INVALID_INPUT);
            written += length; digest.update(value, offset, length);
        }
    }
}
