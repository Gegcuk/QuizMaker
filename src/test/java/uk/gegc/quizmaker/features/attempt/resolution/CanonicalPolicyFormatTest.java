package uk.gegc.quizmaker.features.attempt.resolution;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPolicyResolverImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.CANONICALIZATION_FAILED;
import static uk.gegc.quizmaker.features.attempt.resolution.AttemptPolicyResolverTest.rejected;
import static uk.gegc.quizmaker.features.attempt.resolution.PolicyResolutionFixtures.*;

class CanonicalPolicyFormatTest {
    private final AttemptPolicyResolverImpl resolver = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), new SimpleMeterRegistry());

    @Test
    void baselineHasIndependentFrozenCanonicalBytesAndHash() throws Exception {
        assertGolden(resolver.resolve(request()), "free-practice-v1.json", "d5c8dffdd279905d64dc7a1fe5479ce746dc12fe8ae85f7f1159490f4dd15524");
    }

    @Test
    void composedPolicyHasIndependentFrozenCanonicalBytesAndHash() throws Exception {
        var result = resolver.resolve(request(
                source(OverrideSource.AUTHOR, new HintChoice(HintMode.DISABLED)),
                source(OverrideSource.PARTICIPANT, new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT))));
        assertGolden(result, "composed-practice-v1.json", "54642ff15708559dcb8d889eb0ef48131d072d9ce7b96b98f6ceee013fa5a239");
    }

    @Test
    void jsonPropertyMapAndNormalizedCollectionOrderCannotChangeCanonicalEvidence() throws Exception {
        var expected = resolver.resolve(request());
        var codec = new CanonicalPolicyFormat();
        for (int seed = 0; seed < 24; seed++) {
            var properties = new ArrayList<>(expected.provenance().entrySet());
            Collections.shuffle(properties, new Random(seed));
            var reordered = new LinkedHashMap<String, ValueSource>();
            properties.forEach(e -> reordered.put(e.getKey(), e.getValue()));
            var rules = new ArrayList<>(expected.policy().disclosure().rules());
            var review = new ArrayList<>(expected.policy().review().content());
            Collections.shuffle(rules, new Random(seed + 1));
            Collections.shuffle(review, new Random(seed + 2));
            var p = expected.policy();
            var normalized = new ResolvedAttemptPolicy(p.schemaVersion(), p.content(), p.questionSelection(), p.questionOrdering(),
                    p.answerOrdering(), p.delivery(), p.navigation(), p.submission(), p.answerChange(), new DisclosurePolicy(rules),
                    p.hints(), p.timing(), p.pause(), p.attempts(), p.access(), p.grading(),
                    new ReviewPolicy(p.review().availability(), review, p.review().window()), p.results(), p.accessibility(), p.integrity());
            assertThat(codec.encode(expected.preset(), normalized, reordered)).isEqualTo(expected);
        }
        var transport = JsonMapper.builder().disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
        var tree = transport.valueToTree(expected.policy());
        var reversed = transport.createObjectNode();
        var names = new ArrayList<String>();
        tree.fieldNames().forEachRemaining(names::add);
        Collections.reverse(names);
        names.forEach(name -> reversed.set(name, tree.get(name)));
        var restored = transport.treeToValue(reversed, ResolvedAttemptPolicy.class);
        assertThat(codec.encode(expected.preset(), restored, expected.provenance())).isEqualTo(expected);
    }

    @Test
    void semanticChangesChangeTheHashAndSurviveIndependentSerializationReconstruction() throws Exception {
        var baseline = resolver.resolve(request());
        var hints = resolver.resolve(request(source(OverrideSource.PARTICIPANT, new HintChoice(HintMode.DISABLED))));
        var order = resolver.resolve(request(source(OverrideSource.PARTICIPANT, new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT))));
        var revision = resolver.resolve(new PolicyResolutionRequest(1, 1, "SHA-256", baseline.preset(), content(43), List.of()));
        assertThat(List.of(baseline.hash(), hints.hash(), order.hash(), revision.hash())).doesNotHaveDuplicates();
        var transport = new JsonMapper();
        var reconstructed = new JsonMapper().readValue(transport.writeValueAsBytes(hints), PolicyResolution.class);
        assertThat(reconstructed).isEqualTo(hints);
        assertThat(new CanonicalPolicyFormat().encode(reconstructed.preset(), reconstructed.policy(), reconstructed.provenance())).isEqualTo(hints);
        assertThat(baseline.policy().content().quizRevisionId()).isEqualTo(content(42).quizRevisionId());
    }

    @Test
    void invalidCanonicalSourceMetadataFailsWithoutEchoingTheInput() {
        var baseline = resolver.resolve(request());
        var invalid = new LinkedHashMap<String, ValueSource>();
        invalid.put("private-input-must-not-appear", new ValueSource(PolicyResolution.Source.AUTHOR, 1));
        rejected(() -> new CanonicalPolicyFormat().encode(baseline.preset(), baseline.policy(), invalid), CANONICALIZATION_FAILED);
        invalid.clear();
        invalid.put("hints.mode", null);
        rejected(() -> new CanonicalPolicyFormat().encode(baseline.preset(), baseline.policy(), invalid), CANONICALIZATION_FAILED);
        rejected(() -> new CanonicalPolicyFormat().encode(null, baseline.policy(), baseline.provenance()), CANONICALIZATION_FAILED);
    }

    private static void assertGolden(PolicyResolution result, String resource, String hash) throws Exception {
        try (var fixture = CanonicalPolicyFormatTest.class.getResourceAsStream("/attempt-policy-resolution/" + resource)) {
            assertThat(fixture).isNotNull();
            var expected = new String(fixture.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(result.canonicalJson()).isEqualTo(expected);
            assertThat(result.canonicalBytes()).containsExactly(expected.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(result.hash()).isEqualTo(hash);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.canonicalBytes()))).isEqualTo(hash);
    }
}
