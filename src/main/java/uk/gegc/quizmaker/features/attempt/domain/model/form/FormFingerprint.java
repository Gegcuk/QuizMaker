package uk.gegc.quizmaker.features.attempt.domain.model.form;

import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.Reason.INVALID_ORDER;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.require;

/**
 * form-fingerprint/v1: big-endian, length-delimited identity/order metadata only.
 * Never accepts question/document content, answers, media or a source reader. Hashing streams
 * directly to the digest: no aggregate JSON/byte array and no additional database/content read.
 * This detects accidental changes against saved evidence, not a privileged rewrite of both.
 */
public final class FormFingerprint {
    public static final int VERSION = 1;
    private FormFingerprint() { }

    /** inputBytes is the actual canonical metadata length, available for cost diagnostics/tests. */
    public record Value(String hex, long inputBytes) {
        public byte[] digest() { return HexFormat.of().parseHex(hex); }
        @Override public String toString() { return "FormFingerprint[v1, inputBytes=" + inputBytes + "]"; }
    }

    public static Value compute(FixedForm form, FormOptionOrder order) {
        require(form != null && order != null && form.id().equals(order.formId())
                && form.items().size() == order.items().size(), INVALID_ORDER);
        var byItem = new HashMap<UUID, FormOptionOrder.Item>();
        order.items().forEach(item -> byItem.put(item.formItemId(), item));
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var counted = new CountedSink();
            try (var out = new DataOutputStream(new DigestOutputStream(counted, digest))) {
                out.writeInt(0x514d4646); // QMFF domain separator
                out.writeInt(VERSION);
                uuid(out, form.id());
                out.writeInt(form.schemaVersion());
                uuid(out, form.quizRevisionId());
                out.writeInt(order.schemaVersion());
                text(out, order.policy().mode().name());
                out.writeInt(order.policy().algorithmVersion());
                out.writeInt(form.items().size());
                // FixedForm canonically sorts by saved position, including supported gaps.
                for (var item : form.items()) {
                    var options = byItem.get(item.id());
                    require(options != null && item.sourceItemId().equals(options.sourceItemId()), INVALID_ORDER);
                    uuid(out, item.id());
                    uuid(out, item.sourceItemId());
                    out.writeInt(item.position());
                    var binding = options.binding();
                    uuid(out, binding.questionRevisionId());
                    text(out, binding.response().id());
                    out.writeInt(binding.response().version());
                    text(out, binding.scoring().id());
                    out.writeInt(binding.scoring().version());
                    out.writeInt(options.optionIds().size());
                    for (var option : options.optionIds()) uuid(out, option);
                }
            }
            return new Value(HexFormat.of().formatHex(digest.digest()), counted.bytes);
        } catch (IOException | NoSuchAlgorithmException impossible) {
            // SHA-256 is required by Java; the in-memory sink performs no I/O.
            throw new IllegalStateException("Form fingerprint unavailable");
        }
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        // Only fixed enums or validated ASCII contract IDs (at most 128 characters).
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static final class CountedSink extends OutputStream {
        private long bytes;
        @Override public void write(int value) { bytes++; }
        @Override public void write(byte[] buffer, int offset, int length) { bytes += length; }
    }
}
