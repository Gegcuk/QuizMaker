package uk.gegc.quizmaker.features.attempt.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.question.application.contract.v1.SupportedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

final class ActivityFixtures {
    static final UUID REQUIRED = id(1), OPTIONAL = id(2), FOREIGN = id(3);
    static final Instant TIME = Instant.parse("2026-11-18T12:00:00.123456Z");

    private ActivityFixtures() { }

    static UUID id(int suffix) { return new UUID(0, suffix); }

    static UngradedPolicy ungraded(ResponseRetention retention) {
        return new UngradedPolicy(1, retention, new ParticipationRequirements(List.of(
                new ItemRequirement(REQUIRED, true), new ItemRequirement(OPTIONAL, false))));
    }

    static ResolvedAttemptPolicy gradedPolicy() {
        return BuiltInPracticePreset.V1.bindContent(new ContentPolicy(id(10), 1, FormMode.PRECOMPUTED,
                SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED));
    }

    static AutomaticResult gradedResult(int earned) {
        var response = SupportedQuestionContracts.response(QuestionType.MCQ_SINGLE);
        var scoring = SupportedQuestionContracts.scoring(QuestionType.MCQ_SINGLE);
        return new AutomaticResult(id(20), 1, id(21), id(22), id(23), id(24), id(10), "a".repeat(64),
                AutomaticResult.Score.calculate(earned, 2, 7000), TIME, List.of(
                new AutomaticResult.Item(REQUIRED, id(30), id(40), 0, response, scoring, earned > 0 ? 1 : 0),
                new AutomaticResult.Item(OPTIONAL, id(31), id(41), 1, response, scoring, earned > 1 ? 1 : 0)));
    }
}
