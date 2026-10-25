package uk.gegc.quizmaker.features.attempt.infra.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution.Source.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.CANONICALIZATION_FAILED;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;

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
