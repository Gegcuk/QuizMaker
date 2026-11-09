package uk.gegc.quizmaker.features.attempt.infra.selection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.application.SelectionSeedSource;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;

/** Optional dark configuration: missing/invalid material denies random selection, never ALL/MANUAL_SET startup. */
@Component
public final class HmacSelectionSeedSource implements SelectionSeedSource {
    private static final byte[] DOMAIN = "quizmaker/selection-seed/v1\0".getBytes(StandardCharsets.US_ASCII);
    private final int keyVersion;
    private final byte[] key;

    public HmacSelectionSeedSource(@Value("${quizmaker.attempt.selection.seed-key-version:}") String version,
                                   @Value("${quizmaker.attempt.selection.seed-key-base64:}") String encodedKey) {
        int parsedVersion = 0;
        byte[] parsedKey = null;
        try {
            if (version != null && version.length() <= 10 && encodedKey != null && encodedKey.length() == 44) {
                parsedVersion = Integer.parseInt(version);
                var decoded = Base64.getDecoder().decode(encodedKey);
                if (parsedVersion > 0 && decoded.length == 32 && Base64.getEncoder().encodeToString(decoded).equals(encodedKey))
                    parsedKey = decoded;
            }
        } catch (IllegalArgumentException invalid) { /* No input value or secret becomes an exception/log message. */ }
        this.keyVersion = parsedVersion;
        this.key = parsedKey;
    }

    @Override public SeedMaterial derive(SeedRequest request) {
        if (request == null) throw new QuestionSelectionException(INVALID_SEED_CONTEXT);
        if (key == null) throw new QuestionSelectionException(SEED_UNAVAILABLE);
        var configuration = request.configuration();
        var strategy = configuration.strategy().name().getBytes(StandardCharsets.US_ASCII);
        var revision = request.quizRevisionId();
        var operation = request.context().operationId();
        var message = ByteBuffer.allocate(DOMAIN.length + 9 * Integer.BYTES + 32 + strategy.length)
                .put(DOMAIN).putInt(request.version()).putInt(request.revisionSchemaVersion())
                .putInt(configuration.version()).putInt(configuration.algorithmVersion())
                .putInt(request.context().version()).putInt(keyVersion)
                .putInt(configuration.count() == null ? -1 : configuration.count())
                .putInt(configuration.percentageBasisPoints() == null ? -1 : configuration.percentageBasisPoints())
                .putLong(revision.getMostSignificantBits()).putLong(revision.getLeastSignificantBits())
                .putLong(operation.getMostSignificantBits()).putLong(operation.getLeastSignificantBits())
                .putInt(strategy.length).put(strategy);
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return new SeedMaterial(keyVersion, mac.doFinal(message.array()));
        } catch (GeneralSecurityException unavailable) {
            throw new QuestionSelectionException(SEED_UNAVAILABLE);
        }
    }
}
