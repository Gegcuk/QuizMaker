package uk.gegc.quizmaker.features.attempt.infra.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution.Source.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.CANONICALIZATION_FAILED;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException.Reason.*;

/**
 * Project format v1, not RFC 8785: compact UTF-8 JSON, alphabetically sorted properties/map keys,
 * symbolic enums, integer/boolean scalars, lowercase UUIDs and schema-normalized array order.
 * An isolated mapper prevents application ObjectMapper settings from changing historical bytes.
 * A material representation change requires a new supported canonical version and golden vector.
 */
public final class CanonicalPolicyFormat {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private final JsonMapper reader = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    /** Verify captured evidence without resolving presets or reading any mutable input. */
    public PolicyResolution verify(PolicyResolution value) {
        if (value == null) throw new PolicySnapshotException(INVALID_INPUT);
        PolicyResolution decoded = decode(value.canonicalJson(), value.hash());
        if (!decoded.equals(value)) throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
        return decoded;
    }

    public PolicyResolution decode(String json, String hash) {
        if (json == null || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
        }
        try {
            JsonNode root = reader.readTree(json);
            if (root == null || !root.isObject()) throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
            version(root.get("resolverVersion"));
            version(root.get("canonicalVersion"));
            version(root.at("/policy/schemaVersion"));
            version(root.at("/preset/version"));
            if (!HASH_ALGORITHM.equals(root.path("hashAlgorithm").textValue())) {
                throw new PolicySnapshotException(UNSUPPORTED_HASH_ALGORITHM);
            }
            Envelope envelope = reader.treeToValue(root, Envelope.class);
            if (!BuiltInPracticePreset.REFERENCE.equals(envelope.preset())) {
                throw new PolicySnapshotException(UNSUPPORTED_VERSION);
            }
            var defaults = new TreeMap<String, ValueSource>();
            collect(mapper.valueToTree(envelope.policy()), "", defaults);
            var provenance = envelope.provenance();
            if (!defaults.keySet().equals(provenance.keySet())) throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
            for (var entry : provenance.entrySet()) {
                var source = entry.getValue();
                boolean selectable = entry.getKey().equals("answerOrdering.mode") || entry.getKey().equals("hints.mode");
                if (source == null || !(source.equals(defaults.get(entry.getKey()))
                        || selectable && (source.source() == AUTHOR || source.source() == PARTICIPANT))) {
                    throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
                }
            }
            PolicyResolution reconstructed = encode(envelope.preset(), envelope.policy(), provenance);
            // Exact canonical representation as well as semantic validity: no dropped fields,
            // coercion, duplicate keys, normalized array changes or whitespace substitutions.
            if (!json.equals(reconstructed.canonicalJson()) || !MessageDigest.isEqual(
                    hash.getBytes(StandardCharsets.US_ASCII), reconstructed.hash().getBytes(StandardCharsets.US_ASCII))) {
                throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
            }
            return reconstructed;
        } catch (PolicySnapshotException failure) {
            throw failure;
        } catch (JsonProcessingException | IllegalArgumentException | PolicyResolutionException
                 | PolicyValidationException | PresetResolutionException failure) {
            throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
        }
    }

    private static void version(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
        }
        if (value.intValue() != 1) throw new PolicySnapshotException(UNSUPPORTED_VERSION);
    }

    public PolicyResolution encode(PresetReference preset, ResolvedAttemptPolicy policy,
                                   Map<String, ValueSource> overriddenSources) {
        if (preset == null || policy == null || overriddenSources == null
                || overriddenSources.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)) {
            throw new PolicyResolutionException(CANONICALIZATION_FAILED);
        }
        try {
            var provenance = new TreeMap<String, ValueSource>();
            collect(mapper.valueToTree(policy), "", provenance);
            if (!provenance.keySet().containsAll(overriddenSources.keySet())) {
                throw new PolicyResolutionException(CANONICALIZATION_FAILED);
            }
            provenance.putAll(overriddenSources);
            var envelope = new Envelope(RESOLVER_VERSION, CANONICAL_VERSION, HASH_ALGORITHM, preset, policy, provenance);
            String json = mapper.writeValueAsString(envelope);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
            return new PolicyResolution(RESOLVER_VERSION, CANONICAL_VERSION, HASH_ALGORITHM,
                    preset, policy, provenance, json, hash);
        } catch (JsonProcessingException | IllegalArgumentException | NoSuchAlgorithmException ex) {
            // Do not attach the exception: serializer diagnostics can include policy values.
            throw new PolicyResolutionException(CANONICALIZATION_FAILED);
        }
    }

    private void collect(JsonNode node, String path, Map<String, ValueSource> provenance) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collect(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), provenance));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collect(node.get(i), path + "[" + i + "]", provenance);
        } else {
            var source = path.startsWith("content.") ? REVISION
                    : path.equals("schemaVersion") || path.startsWith("integrity.") ? PLATFORM_SAFETY : PRESET;
            provenance.put(path, new ValueSource(source, 1));
        }
    }

    private record Envelope(int resolverVersion, int canonicalVersion, String hashAlgorithm,
                            PresetReference preset, ResolvedAttemptPolicy policy, Map<String, ValueSource> provenance) {}
}
