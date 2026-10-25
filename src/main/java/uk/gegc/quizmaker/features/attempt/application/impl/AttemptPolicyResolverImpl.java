package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.AttemptPolicyResolver;
import uk.gegc.quizmaker.features.attempt.application.AttemptPresetService;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;

@Service
public final class AttemptPolicyResolverImpl implements AttemptPolicyResolver {
    private final AttemptPresetService presets;
    private final MeterRegistry metrics;
    private final CanonicalPolicyFormat format = new CanonicalPolicyFormat();

    public AttemptPolicyResolverImpl(AttemptPresetService presets, MeterRegistry metrics) {
        this.presets = Objects.requireNonNull(presets);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public PolicyResolution resolve(PolicyResolutionRequest request) {
        try {
            var result = resolveValue(request);
            count("success", "none");
            countSources(result);
            return result;
        } catch (PolicyResolutionException ex) {
            count("failure", ex.reason().name());
            throw ex;
        } catch (PolicyValidationException ex) {
            count("failure", ex.reason().name());
            throw ex;
        } catch (PresetResolutionException ex) {
            count("failure", ex.reason().name());
            throw ex;
        }
    }

    private PolicyResolution resolveValue(PolicyResolutionRequest request) {
        check(request != null, INVALID_INPUT);
        check(request.resolverVersion() == RESOLVER_VERSION, UNSUPPORTED_RESOLVER_VERSION);
        check(request.canonicalVersion() == CANONICAL_VERSION, UNSUPPORTED_CANONICAL_VERSION);
        check(HASH_ALGORITHM.equals(request.hashAlgorithm()), UNSUPPORTED_HASH_ALGORITHM);
        var base = presets.load(request.preset()).bindContent(request.content());
        var sources = new EnumMap<OverrideSource, SourceOverrides>(OverrideSource.class);
        for (var source : request.overrides()) {
            check(source.sourceVersion() == 1, UNSUPPORTED_SOURCE_VERSION);
            check(sources.putIfAbsent(source.source(), source) == null, DUPLICATE_SOURCE);
        }

        var answerOrder = base.answerOrdering();
        var hints = base.hints();
        var provenance = new HashMap<String, ValueSource>();
        // Explicit precedence, independent of input collection order. Safety/structural facts
        // cannot be overridden because Choice has no representation for those fields.
        for (var sourceType : List.of(OverrideSource.AUTHOR, OverrideSource.PARTICIPANT)) {
            var source = sources.get(sourceType);
            if (source == null) continue;
            var seen = new HashSet<Setting>();
            for (var choice : source.choices()) {
                check(seen.add(choice.setting()), DUPLICATE_SETTING);
                var origin = new ValueSource(sourceType == OverrideSource.AUTHOR
                        ? PolicyResolution.Source.AUTHOR : PolicyResolution.Source.PARTICIPANT, 1);
                if (choice instanceof AnswerOrderChoice order) {
                    check(source.allowed().answerOrders().contains(order.value()), FORBIDDEN_OVERRIDE);
                    answerOrder = new AnswerOrderingPolicy(order.value(), base.answerOrdering().algorithmVersion());
                    provenance.put("answerOrdering.mode", origin);
                } else if (choice instanceof HintChoice hint) {
                    check(source.allowed().hintModes().contains(hint.value()), FORBIDDEN_OVERRIDE);
                    hints = new HintPolicy(hint.value(), base.hints().source());
                    provenance.put("hints.mode", origin);
                }
            }
        }
        // Re-enter the existing aggregate validator after composition; no parallel rule set.
        var effective = new ResolvedAttemptPolicy(base.schemaVersion(), base.content(), base.questionSelection(),
                base.questionOrdering(), answerOrder, base.delivery(), base.navigation(), base.submission(),
                base.answerChange(), base.disclosure(), hints, base.timing(), base.pause(), base.attempts(),
                base.access(), base.grading(), base.review(), base.results(), base.accessibility(), base.integrity());
        return format.encode(request.preset(), effective, provenance);
    }

    private static void check(boolean valid, PolicyResolutionException.Reason reason) {
        if (!valid) throw new PolicyResolutionException(reason);
    }

    private void count(String outcome, String reason) {
        try {
            metrics.counter("attempt.policy.resolutions", "resolver_version", "1", "outcome", outcome, "reason", reason).increment();
        } catch (RuntimeException ignored) {
            // Telemetry must not change a deterministic resolution or hide its original error.
        }
    }

    private void countSources(PolicyResolution result) {
        try {
            result.provenance().values().stream().distinct().forEach(source ->
                    metrics.counter("attempt.policy.resolution.sources", "source", source.source().name(),
                            "source_version", "1").increment());
        } catch (RuntimeException ignored) {
            // These labels come exclusively from the closed source vocabulary.
        }
    }
}
