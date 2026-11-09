package uk.gegc.quizmaker.features.attempt.domain.model.selection;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.require;

/** Pure random-selection/v1: rank occurrence IDs by keyed digest, select a prefix, return authored order. */
public final class RandomQuestionSelection {
    private static final byte[] DOMAIN = "quizmaker/random-selection/v1\0".getBytes(StandardCharsets.US_ASCII);
    private RandomQuestionSelection() {}

    public static Result select(Input input, SeedMaterial seed) {
        require(input != null && input.configuration().strategy().random(), INVALID_INPUT);
        require(seed != null, SEED_UNAVAILABLE);
        int size = input.configuration().selectionSize(input.eligibleItems().size());
        try {
            var mac = Mac.getInstance("HmacSHA256");
            var bytes = seed.bytes();
            mac.init(new SecretKeySpec(bytes, "HmacSHA256"));
            record Ranked(UUID id, byte[] rank) {}
            var ranked = new ArrayList<Ranked>(input.eligibleItems().size());
            for (var item : input.eligibleItems()) {
                var message = ByteBuffer.allocate(DOMAIN.length + 16).put(DOMAIN)
                        .putLong(item.id().getMostSignificantBits()).putLong(item.id().getLeastSignificantBits());
                ranked.add(new Ranked(item.id(), mac.doFinal(message.array())));
            }
            ranked.sort((left, right) -> {
                int order = Arrays.compareUnsigned(left.rank(), right.rank());
                return order != 0 ? order : left.id().toString().compareTo(right.id().toString());
            });
            var selected = new HashSet<UUID>();
            for (int index = 0; index < size; index++) selected.add(ranked.get(index).id());
            var ids = input.eligibleItems().stream().map(Item::id).filter(selected::contains).toList();
            var fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var evidence = new RandomEvidence(1, input.context(), seed.keyVersion(), fingerprint,
                    input.eligibleItems().size(), size);
            return new Result(1, new Evidence(1, input.version(), input.quizRevisionId(), input.revisionSchemaVersion(),
                    input.configuration(), evidence), ids);
        } catch (GeneralSecurityException unavailable) {
            throw new QuestionSelectionException(SEED_UNAVAILABLE);
        }
    }
}
