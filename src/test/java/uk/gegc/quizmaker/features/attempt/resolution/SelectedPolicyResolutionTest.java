package uk.gegc.quizmaker.features.attempt.resolution;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.SelectionPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.*;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.Configuration;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException;
import uk.gegc.quizmaker.features.attempt.infra.policy.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.resolution.PolicyResolutionFixtures.*;

class SelectedPolicyResolutionTest {
    private final AttemptPolicyResolverImpl resolver = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), new SimpleMeterRegistry());
    static Stream<Configuration> configurations() {
        return Stream.of(Configuration.manualSet(List.of(new UUID(0, 2), new UUID(0, 1))),
                Configuration.randomCount(3), Configuration.randomPercentage(3300));
    }
    @ParameterizedTest @MethodSource("configurations")
    void exactTypedSelectionRequiresAuthorGrantAndRoundTripsWithoutChangingThePreset(Configuration config) {
        var selected = SelectionPolicy.from(config);
        var grant = new AllowedChoices(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(selected));
        var authorized = new SourceOverrides(OverrideSource.AUTHOR, 1, grant, List.of(new SelectionChoice(selected)));
        var result = resolver.resolve(request(authorized));
        assertThat(result.policy().questionSelection().configuration()).isEqualTo(config);
        assertThat(result.preset()).isEqualTo(resolver.resolve(request()).preset());
        assertThat(new CanonicalPolicyFormat().decode(result.canonicalJson(), result.hash())).isEqualTo(result);
        assertThat(result.provenance().get("questionSelection.mode").source()).isEqualTo(PolicyResolution.Source.AUTHOR);
        for (var denied : List.of(new SourceOverrides(OverrideSource.PARTICIPANT, 1, grant, List.of(new SelectionChoice(selected))),
                new SourceOverrides(OverrideSource.AUTHOR, 1, NO_CHOICES, List.of(new SelectionChoice(selected)))))
            assertThatThrownBy(() -> resolver.resolve(request(denied))).isInstanceOfSatisfying(PolicyResolutionException.class,
                    e -> assertThat(e.reason()).isEqualTo(PolicyResolutionException.Reason.FORBIDDEN_OVERRIDE));
        var forged = new CanonicalPolicyFormat().encode(result.preset(), result.policy(), Map.of());
        assertThatThrownBy(() -> new CanonicalPolicyFormat().decode(forged.canonicalJson(), forged.hash()))
                .isInstanceOf(PolicySnapshotException.class);
        assertThat(resolver.resolve(request()).policy().questionSelection().configuration()).isEqualTo(Configuration.all());
    }
    @Test void equivalentManualSetsHaveIdenticalCanonicalEvidenceAndDifferentConfigurationsHaveDifferentHashes() {
        var first = SelectionPolicy.from(Configuration.manualSet(List.of(new UUID(0, 2), new UUID(0, 1))));
        var reordered = SelectionPolicy.from(Configuration.manualSet(List.of(new UUID(0, 1), new UUID(0, 2))));
        assertThat(resolve(first)).isEqualTo(resolve(reordered));
        assertThat(resolve(SelectionPolicy.from(Configuration.randomCount(2))).hash())
                .isNotEqualTo(resolve(SelectionPolicy.from(Configuration.randomCount(3))).hash());
    }
    @Test void malformedUnknownOrCoercedPersistedSelectionCannotBeReadEvenWithACorrectHash() throws Exception {
        var result = resolve(SelectionPolicy.from(Configuration.randomCount(3)));
        String original = result.canonicalJson();
        for (String json : List.of(original.replace("\"count\":3", "\"count\":-1"),
                original.replace("\"count\":3", "\"count\":\"3\""),
                original.replace("\"count\":3", "\"count\":null"),
                original.replace("\"count\":3", "\"count\":3,\"seed\":\"private\""),
                original.replace("RANDOM_COUNT", "STRATIFIED"),
                original.replace("\"algorithmVersion\":1,\"count\":3", "\"algorithmVersion\":2,\"count\":3"))) {
            assertThat(json).isNotEqualTo(original);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> new CanonicalPolicyFormat().decode(json, hash)).isInstanceOf(PolicySnapshotException.class)
                    .hasNoCause().hasMessageNotContaining("private");
        }
    }
    @Test void codecRejectsUnknownPropertiesAndMissingParametersAndKeepsAllRepresentationExact() throws Exception {
        var json = JsonMapper.builder().addModule(SelectionPolicyJson.module()).build();
        assertThat(json.writeValueAsString(SelectionPolicy.from(Configuration.all())))
                .isEqualTo("{\"algorithmVersion\":1,\"mode\":\"ALL\"}");
        for (String value : List.of("{\"algorithmVersion\":1,\"mode\":\"RANDOM_COUNT\"}",
                "{\"algorithmVersion\":1,\"mode\":\"ALL\",\"seed\":1}",
                "{\"algorithmVersion\":1,\"mode\":\"MANUAL_SET\",\"manualItemIds\":[\"1-1-1-1-1\"]}"))
            assertThatThrownBy(() -> json.readValue(value, SelectionPolicy.class)).isInstanceOfAny(com.fasterxml.jackson.core.JsonProcessingException.class,
                    uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException.class);
    }
    private PolicyResolution resolve(SelectionPolicy p) {
        return resolver.resolve(request(new SourceOverrides(OverrideSource.AUTHOR, 1,
                new AllowedChoices(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(p)), List.of(new SelectionChoice(p)))));
    }
}
