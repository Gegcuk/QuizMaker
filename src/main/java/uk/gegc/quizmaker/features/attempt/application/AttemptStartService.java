package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.ContentPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.LaunchContext;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.Key;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.TimingPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.TimingMode;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AttemptLimitPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AttemptLimitMode;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.NavigationPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.NavigationMode;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.DeliveryMode;

/** Internal authenticated fixed-form start. No controller, routing or participant delivery is activated. */
public interface AttemptStartService {
    /**
     * Requested configuration is never authority; the server resolves user, access and publication.
     * Only verified current owner/full-editor scope can configure limits; the first committed start
     * captures them for this user/publication context. Later different limits conflict, not reset.
     */
    record Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                   PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder, boolean draftsEnabled, NavigationPolicy navigation, DeliveryMode delivery, QuestionSelection.Configuration selection, AttemptLimitPolicy attemptLimit, TimingPolicy timing) {
        public Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                       PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder, boolean draftsEnabled,
                       NavigationPolicy navigation, DeliveryMode delivery, QuestionSelection.Configuration selection, AttemptLimitPolicy attemptLimit) {
            this(version, quizId, content, publicationVersion, key, preset, context, legacyMode, answerOrder, draftsEnabled,
                    navigation, delivery, selection, attemptLimit, new TimingPolicy(TimingMode.NONE));
        }
        public Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                       PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder, boolean draftsEnabled,
                       NavigationPolicy navigation, DeliveryMode delivery, QuestionSelection.Configuration selection) {
            this(version, quizId, content, publicationVersion, key, preset, context, legacyMode, answerOrder, draftsEnabled,
                    navigation, delivery, selection, new AttemptLimitPolicy(AttemptLimitMode.UNLIMITED));
        }
        public Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                       PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder,
                       boolean draftsEnabled, NavigationPolicy navigation, DeliveryMode delivery) {
            this(version, quizId, content, publicationVersion, key, preset, context, legacyMode, answerOrder,
                    draftsEnabled, navigation, delivery, QuestionSelection.Configuration.all());
        }
        public Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                       PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder, boolean draftsEnabled, NavigationPolicy navigation) {
            this(version, quizId, content, publicationVersion, key, preset, context, legacyMode, answerOrder, draftsEnabled, navigation, DeliveryMode.ALL_AT_ONCE);
        }
        public Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                       PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder, boolean draftsEnabled) {
            this(version, quizId, content, publicationVersion, key, preset, context, legacyMode, answerOrder, draftsEnabled, new NavigationPolicy(NavigationMode.FREE));
        }
        public Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                       PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder) {
            this(version, quizId, content, publicationVersion, key, preset, context, legacyMode, answerOrder, false);
        }
        @Override public String toString() { return "AttemptStartCommand[redacted]"; }
    }

    /** References only. No answer material, role, capability or mutable draft is returned. */
    record Result(UUID attemptId, UUID formId, UUID quizRevisionId, long stateVersion, boolean replayed) { }

    /**
     * REQUIRED transaction, shared with all writers. When joining an outer transaction, return is
     * provisional until its commit. Retry unknown outcomes with the exact same key and command.
     * Access is rechecked on every call; a new publication invalidates fresh starts on the old
     * reference, but does not reinterpret an already committed receipt. No automatic retry/fallback.
     * Existing READ_COMMITTED and REPEATABLE_READ transactions are supported, including an
     * older snapshot: publication membership, revision contents and write eligibility use current
     * reads. Database lock conflicts keep #599's RETRYABLE / IN_PROGRESS classification.
     */
    Result start(Command command);
}
