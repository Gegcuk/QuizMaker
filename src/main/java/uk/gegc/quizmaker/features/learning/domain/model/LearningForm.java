package uk.gegc.quizmaker.features.learning.domain.model;

import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.require;

/** Immutable metadata only. Session lifecycle, selection and schedule state are separate owners. */
public record LearningForm(UUID id, int version, UUID sessionId, LearningSession.Source source,
                           List<Item> items, Outcome outcome, int fingerprintVersion,
                           String fingerprint, Instant createdAt) {
    public enum Outcome { READY, EMPTY_COMPLETE }

    /** List order is delivery order; option IDs are immutable identities, never answer/source keys. */
    public record Item(UUID sourceItemId, UUID questionRevisionId, int questionSchemaVersion,
                       ContractVersion response, List<UUID> optionIds) {
        public Item {
            require(sourceItemId != null && questionRevisionId != null && response != null, INVALID_INPUT);
            require(questionSchemaVersion == 1, UNSUPPORTED_VERSION);
            require(optionIds != null && optionIds.stream().allMatch(Objects::nonNull)
                    && new HashSet<>(optionIds).size() == optionIds.size(), INVALID_INPUT);
            optionIds = List.copyOf(optionIds);
        }
        @Override public String toString() { return "LearningFormItem[redacted]"; }
    }

    public LearningForm {
        require(version == 1 && fingerprintVersion == 1, UNSUPPORTED_VERSION);
        require(id != null && sessionId != null && source != null && createdAt != null, CORRUPT);
        items = selection(items);
        require(outcome == (items.isEmpty() ? Outcome.EMPTY_COMPLETE : Outcome.READY), CORRUPT);
        require(createdAt.equals(createdAt.truncatedTo(ChronoUnit.MICROS))
                && !createdAt.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && createdAt.isBefore(Instant.parse("+10000-01-01T00:00:00Z")), CORRUPT);
        require(digest(id, sessionId, source, items).equals(fingerprint), CORRUPT);
    }

    public static LearningForm create(UUID id, LearningSession session, List<Item> items, Instant at) {
        var selected = selection(items);
        return new LearningForm(id, 1, session.id(), session.source(), selected,
                selected.isEmpty() ? Outcome.EMPTY_COMPLETE : Outcome.READY, 1,
                digest(id, session.id(), session.source(), selected), at);
    }

    public static List<Item> presentationOrder(List<Item> dueOrder, boolean shuffle, Random random) {
        if (!shuffle) return List.copyOf(dueOrder);
        var ordered = new ArrayList<>(dueOrder);
        Collections.shuffle(ordered, random);
        return List.copyOf(ordered);
    }

    public static List<Item> selection(List<Item> items) {
        require(items != null, INVALID_INPUT);
        var identities = new HashSet<UUID>();
        for (var item : items) require(item != null && identities.add(item.sourceItemId()), INVALID_INPUT);
        return List.copyOf(items);
    }

    /** Length-delimited streaming metadata; never accepts text/content or rereads a document. */
    private static String digest(UUID id, UUID sessionId, LearningSession.Source source, List<Item> items) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), sha))) {
                out.writeInt(0x514d4c46); // QMLF, fingerprint format 1
                out.writeInt(1); uuid(out, id); uuid(out, sessionId);
                out.writeInt(source.version()); uuid(out, source.quizId()); uuid(out, source.quizRevisionId());
                out.writeInt(source.revisionSchemaVersion()); out.writeInt(items.size());
                for (var item : items) {
                    uuid(out, item.sourceItemId()); uuid(out, item.questionRevisionId());
                    out.writeInt(item.questionSchemaVersion());
                    byte[] name = item.response().id().getBytes(StandardCharsets.US_ASCII);
                    out.writeInt(name.length); out.write(name); out.writeInt(item.response().version());
                    out.writeInt(item.optionIds().size());
                    for (var option : item.optionIds()) uuid(out, option);
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Learning form fingerprint unavailable");
        }
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    @Override public String toString() { return "LearningForm[version=" + version + ",outcome=" + outcome + "]"; }
}
