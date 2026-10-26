package uk.gegc.quizmaker.features.attempt.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.*;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.snapshot.SnapshotFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException.Reason.*;

class PolicySnapshotTest {
    private final CanonicalPolicyFormat format = new CanonicalPolicyFormat();
    private final PolicyResolution value = snapshot("codec", id("revision"), true).resolution();

    @ParameterizedTest
    @ValueSource(strings = {"free-practice-v1.json", "composed-practice-v1.json"})
    void independentlyFrozenResolverEvidenceDecodesWithoutResolvingCurrentDefaults(String file) throws Exception {
        String json;
        try (var stream = getClass().getResourceAsStream("/attempt-policy-resolution/" + file)) {
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        }
        var decoded = format.decode(json, hash(json));
        assertThat(decoded.canonicalJson()).isEqualTo(json);
        assertThat(new CanonicalPolicyFormat().verify(decoded)).isEqualTo(decoded);
        assertThat(decoded.policy().schemaVersion()).isEqualTo(1);
        assertThat(decoded.provenance()).isNotEmpty();
    }

    @Test
    void exactSnapshotMetadataMustMatchItsCanonicalRepresentation() {
        assertThat(format.verify(value)).isEqualTo(value);
        var changed = snapshot("codec", id("revision"), false).resolution();
        rejected(() -> format.verify(new PolicyResolution(value.resolverVersion(), value.canonicalVersion(), value.hashAlgorithm(),
                value.preset(), changed.policy(), value.provenance(), value.canonicalJson(), value.hash())), CORRUPT_SNAPSHOT);
        rejected(() -> format.verify(new PolicyResolution(2, 1, "SHA-256", value.preset(), value.policy(), value.provenance(),
                value.canonicalJson(), value.hash())), CORRUPT_SNAPSHOT);
        rejected(() -> format.verify(null), INVALID_INPUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolverVersion", "canonicalVersion", "policy/schemaVersion", "preset/version"})
    void futureVersionsNeverUseCurrentDefaults(String path) throws Exception {
        String json = changed(root -> field(root, path, new JsonMapper().getNodeFactory().numberNode(2)));
        rejected(() -> format.decode(json, hash(json)), UNSUPPORTED_VERSION);
    }

    @Test
    void unsupportedAlgorithmOrPresetIsExplicitlyRejected() throws Exception {
        String algorithm = changed(root -> root.put("hashAlgorithm", "MD5"));
        rejected(() -> format.decode(algorithm, hash(algorithm)), UNSUPPORTED_HASH_ALGORITHM);
        String preset = changed(root -> ((ObjectNode) root.get("preset")).put("id", "unknown-private-preset"));
        rejected(() -> format.decode(preset, hash(preset)), UNSUPPORTED_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "extra", "illegal-origin", "source-version", "null-origin"})
    void evenRehashedProvenanceMustBeCompleteAndStructurallyTrustworthy(String fault) throws Exception {
        String json = changed(root -> {
            var provenance = (ObjectNode) root.get("provenance");
            switch (fault) {
                case "missing" -> provenance.remove("hints.mode");
                case "extra" -> provenance.set("private-content-must-not-leak", provenance.get("hints.mode"));
                case "illegal-origin" -> ((ObjectNode) provenance.get("content.quizRevisionId")).put("source", "PARTICIPANT");
                case "source-version" -> ((ObjectNode) provenance.get("hints.mode")).put("version", 2);
                case "null-origin" -> provenance.putNull("hints.mode");
            }
        });
        rejected(() -> format.decode(json, hash(json)), CORRUPT_SNAPSHOT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-field", "extra-field", "numeric-enum", "coerced-version", "null-boolean", "missing-boolean"})
    void strictParsingRejectsDroppedFieldsCoercionAndImplicitPrimitiveDefaults(String fault) throws Exception {
        String json = changed(root -> {
            var policy = (ObjectNode) root.get("policy");
            switch (fault) {
                case "missing-field" -> root.remove("provenance");
                case "extra-field" -> root.put("private-content-must-not-leak", "answer");
                case "numeric-enum" -> ((ObjectNode) policy.get("hints")).put("mode", 0);
                case "coerced-version" -> root.put("resolverVersion", "1");
                case "null-boolean" -> ((ObjectNode) policy.get("submission")).putNull("requireAllAnswers");
                case "missing-boolean" -> ((ObjectNode) policy.get("submission")).remove("requireAllAnswers");
            }
        });
        rejected(() -> format.decode(json, hash(json)), CORRUPT_SNAPSHOT);
    }

    @Test
    void nonCanonicalDuplicateTrailingOrMalformedJsonCannotPassWithARecomputedHash() {
        for (String json : new String[]{"null", "[]", "{", value.canonicalJson() + " {}", " " + value.canonicalJson(),
                value.canonicalJson().replace("\"resolverVersion\":1", "\"resolverVersion\":1,\"resolverVersion\":1")}) {
            rejected(() -> format.decode(json, hash(json)), CORRUPT_SNAPSHOT);
        }
        rejected(() -> format.decode(null, value.hash()), CORRUPT_SNAPSHOT);
        rejected(() -> format.decode(value.canonicalJson(), null), CORRUPT_SNAPSHOT);
        rejected(() -> format.decode(value.canonicalJson(), "0".repeat(64)), CORRUPT_SNAPSHOT);
        rejected(() -> format.decode(value.canonicalJson(), value.hash().toUpperCase()), CORRUPT_SNAPSHOT);
        assertThatThrownBy(() -> format.decode("private-content-must-not-leak", value.hash()))
                .hasMessageNotContaining("private-content-must-not-leak").hasNoCause();
    }

    @Test
    void stableIdentityAndExplicitLegacyTypesCannotBeCreatedWithNullTargetIds() {
        rejected(() -> new PolicySnapshot(null, value), INVALID_INPUT);
        rejected(() -> new PolicySnapshot(id("snapshot"), null), INVALID_INPUT);
        rejected(() -> new PolicySnapshotReference.Stored(null), INVALID_INPUT);
        assertThat(PolicySnapshotReference.Legacy.NO_SNAPSHOT).isNotNull();
    }

    private String changed(Consumer<ObjectNode> mutation) throws Exception {
        var mapper = new JsonMapper();
        var root = (ObjectNode) mapper.readTree(value.canonicalJson());
        mutation.accept(root);
        return mapper.writeValueAsString(root);
    }

    private static void field(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("/");
        ObjectNode parent = root;
        for (int i = 0; i < parts.length - 1; i++) parent = (ObjectNode) parent.get(parts[i]);
        parent.set(parts[parts.length - 1], value);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
