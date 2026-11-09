package uk.gegc.quizmaker.features.attempt.domain.model.selection;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.ContentPolicy;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.require;

/**
 * Transport-neutral, dark selection contracts. Item IDs identify immutable revision occurrences,
 * not mutable questions. Eligibility and authority are established by the trusted server caller.
 * Version 1 returns authored revision order for every strategy, independently of collection order.
 */
public final class QuestionSelection {
    public static final int VERSION = 1;
    public static final int PERCENTAGE_SCALE = 10_000;

    private QuestionSelection() {}

    public enum Strategy {
        ALL, MANUAL_SET, RANDOM_COUNT, RANDOM_PERCENTAGE;

        public boolean random() { return this == RANDOM_COUNT || this == RANDOM_PERCENTAGE; }

        public static Strategy parse(String value) {
            for (Strategy strategy : values()) {
                if (strategy.name().equals(value)) return strategy;
            }
            throw new QuestionSelectionException(UNKNOWN_STRATEGY);
        }
    }

    /** A set, not display order. Canonical IDs make equivalent configurations compare equally. */
    public record Configuration(int version, Strategy strategy, int algorithmVersion, List<UUID> manualItemIds,
                                Integer count, Integer percentageBasisPoints) {
        public Configuration(int version, Strategy strategy, int algorithmVersion, List<UUID> manualItemIds) {
            this(version, strategy, algorithmVersion, manualItemIds, null, null);
        }
        public Configuration {
            requireVersion(version);
            require(strategy != null, INVALID_CONFIGURATION);
            require(algorithmVersion == VERSION, UNSUPPORTED_STRATEGY_VERSION);
            manualItemIds = canonicalIds(manualItemIds);
            require(strategy == Strategy.MANUAL_SET || manualItemIds.isEmpty(), INVALID_CONFIGURATION);
            require(strategy == Strategy.RANDOM_COUNT ? count != null && count >= 0 : count == null, INVALID_CONFIGURATION);
            require(strategy == Strategy.RANDOM_PERCENTAGE
                    ? percentageBasisPoints != null && percentageBasisPoints >= 0 && percentageBasisPoints <= PERCENTAGE_SCALE
                    : percentageBasisPoints == null, INVALID_CONFIGURATION);
        }

        public static Configuration all() {
            return new Configuration(VERSION, Strategy.ALL, VERSION, List.of());
        }

        public static Configuration manualSet(List<UUID> itemIds) {
            return new Configuration(VERSION, Strategy.MANUAL_SET, VERSION, itemIds);
        }

        public static Configuration randomCount(int count) {
            return new Configuration(VERSION, Strategy.RANDOM_COUNT, VERSION, List.of(), count, null);
        }

        /** Fixed precision: 3300 means 33.00%, 1 means 0.01%, 10000 means 100%. */
        public static Configuration randomPercentage(int percentageBasisPoints) {
            return new Configuration(VERSION, Strategy.RANDOM_PERCENTAGE, VERSION, List.of(), null, percentageBasisPoints);
        }

        public int selectionSize(int candidateCount) {
            require(candidateCount >= 0, INVALID_INPUT);
            int size = switch (strategy) {
                case ALL -> candidateCount;
                case MANUAL_SET -> manualItemIds.size();
                case RANDOM_COUNT -> count;
                // Owner-approved ceiling. The long product cannot overflow for int-sized pools.
                case RANDOM_PERCENTAGE -> (int) (((long) candidateCount * percentageBasisPoints + PERCENTAGE_SCALE - 1)
                        / PERCENTAGE_SCALE);
            };
            require(size <= candidateCount, INSUFFICIENT_CANDIDATES);
            return size;
        }
    }

    /** Supplied only by an authorized server use case, never bound from an HTTP request or client seed. */
    public record SelectionContext(int version, UUID operationId) {
        public SelectionContext { requireVersion(version); require(operationId != null, INVALID_SEED_CONTEXT); }
        @Override public String toString() { return "SelectionContext[version=" + version + "]"; }
    }

    /** Bounded seed derivation metadata; contains neither candidate membership nor question content. */
    public record SeedRequest(int version, UUID quizRevisionId, int revisionSchemaVersion,
                              Configuration configuration, SelectionContext context) {
        public SeedRequest {
            requireReference(version, quizRevisionId, revisionSchemaVersion);
            require(configuration != null && configuration.strategy().random() && context != null, INVALID_SEED_CONTEXT);
        }
        @Override public String toString() { return "SeedRequest[version=" + version + "]"; }
    }

    /** Opaque project-owned seed-port output. Defensive copies and redacted diagnostics protect its material. */
    public static final class SeedMaterial {
        private final int keyVersion;
        private final byte[] bytes;
        public SeedMaterial(int keyVersion, byte[] bytes) {
            require(keyVersion > 0 && bytes != null && bytes.length == 32, SEED_UNAVAILABLE);
            this.keyVersion = keyVersion;
            this.bytes = bytes.clone();
        }
        public int keyVersion() { return keyVersion; }
        public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "SeedMaterial[redacted]"; }
    }

    public record RandomEvidence(int version, SelectionContext context, int keyVersion, String seedFingerprint,
                                 int candidateCount, int selectedCount) {
        public RandomEvidence {
            requireVersion(version);
            require(context != null && keyVersion > 0 && seedFingerprint != null
                    && seedFingerprint.matches("[0-9a-f]{64}"), INVALID_RESULT);
            require(candidateCount >= 0 && selectedCount >= 0 && selectedCount <= candidateCount, INVALID_RESULT);
        }
        @Override public String toString() {
            return "RandomEvidence[version=" + version + ", keyVersion=" + keyVersion
                    + ", candidateCount=" + candidateCount + ", selectedCount=" + selectedCount + "]";
        }
    }

    public record Item(UUID id, Integer authoredPosition) {
        public Item { require(id != null && authoredPosition != null && authoredPosition >= 0, INVALID_ITEM); }
    }

    /** Supplied constraint metadata only: select every member or none, without authoring a case. */
    public record AtomicGroup(int version, List<UUID> itemIds) {
        public AtomicGroup {
            requireVersion(version);
            itemIds = canonicalIds(itemIds);
            require(!itemIds.isEmpty(), INVALID_CONSTRAINT);
        }
    }

    /**
     * Complete eligible metadata from one immutable revision. Missing eligibility is never inferred.
     * A manual set is bounded by this exact membership; there is no new numerical product quota.
     * Positions may have gaps, but duplicate positions cannot acquire a UUID tie-break fallback.
     */
    public record Input(int version, UUID quizRevisionId, int revisionSchemaVersion,
                        Configuration configuration, List<Item> eligibleItems, List<AtomicGroup> atomicGroups,
                        SelectionContext context) {
        public Input(int version, UUID quizRevisionId, int revisionSchemaVersion, Configuration configuration,
                     List<Item> eligibleItems, List<AtomicGroup> atomicGroups) {
            this(version, quizRevisionId, revisionSchemaVersion, configuration, eligibleItems, atomicGroups, null);
        }
        public Input {
            requireReference(version, quizRevisionId, revisionSchemaVersion);
            require(configuration != null && eligibleItems != null && atomicGroups != null, INVALID_INPUT);
            require(configuration.strategy().random() == (context != null), INVALID_SEED_CONTEXT);
            // Structured random selection belongs to #546; never split or resample an atomic case here.
            require(!configuration.strategy().random() || atomicGroups.isEmpty(), UNSUPPORTED_CONSTRAINT);
            var ids = new HashSet<UUID>();
            var positions = new HashSet<Integer>();
            for (Item item : eligibleItems) {
                require(item != null, INVALID_ITEM);
                require(ids.add(item.id()), DUPLICATE_ITEM);
                require(positions.add(item.authoredPosition()), DUPLICATE_POSITION);
            }
            for (UUID id : configuration.manualItemIds()) require(ids.contains(id), UNKNOWN_ITEM);
            var groups = new HashSet<AtomicGroup>();
            for (AtomicGroup group : atomicGroups) {
                require(group != null && groups.add(group), INVALID_CONSTRAINT);
                require(ids.containsAll(group.itemIds()), INVALID_CONSTRAINT);
            }
            eligibleItems = eligibleItems.stream().sorted(Comparator.comparingInt(Item::authoredPosition)).toList();
            atomicGroups = List.copyOf(atomicGroups);
        }

        /**
         * Projects #597 identity and checks #515's exact content binding. Every revision item is
         * eligible in this projection. A caller with further eligibility rules supplies their
         * complete verified metadata to Input instead. This factory grants no resource access.
         */
        public static Input fromRevision(ContentPolicy content, QuizRevision revision,
                                         Configuration configuration, List<AtomicGroup> atomicGroups) {
            return fromRevision(content, revision, configuration, atomicGroups, null);
        }

        public static Input fromRevision(ContentPolicy content, QuizRevision revision, Configuration configuration,
                                         List<AtomicGroup> atomicGroups, SelectionContext context) {
            require(content != null && revision != null, INVALID_INPUT);
            require(content.quizRevisionId().equals(revision.id())
                    && content.revisionSchemaVersion() == revision.schemaVersion(), REVISION_MISMATCH);
            return new Input(VERSION, revision.id(), revision.schemaVersion(), configuration,
                    revision.items().stream().map(item -> new Item(item.id(), item.authoredPosition())).toList(),
                    atomicGroups, context);
        }

        /** Do not accidentally log a complete eligible bank through a record's default toString. */
        @Override public String toString() {
            return "QuestionSelection.Input[version=" + version + ", eligibleItemCount=" + eligibleItems.size()
                    + ", atomicGroupCount=" + atomicGroups.size() + "]";
        }
    }

    /**
     * Exact strategy/configuration and revision versions, not a receipt, input digest or authority.
     * Unselected bank/group membership is deliberately absent. #522 owns persisted form evidence.
     */
    public record Evidence(int version, int inputVersion, UUID quizRevisionId, int revisionSchemaVersion,
                           Configuration configuration, RandomEvidence random) {
        public Evidence(int version, int inputVersion, UUID quizRevisionId, int revisionSchemaVersion,
                        Configuration configuration) {
            this(version, inputVersion, quizRevisionId, revisionSchemaVersion, configuration, null);
        }
        public Evidence {
            requireReference(version, quizRevisionId, revisionSchemaVersion);
            requireVersion(inputVersion);
            require(configuration != null, INVALID_INPUT);
            require(configuration.strategy().random() == (random != null), INVALID_RESULT);
            if (random != null) require(configuration.selectionSize(random.candidateCount()) == random.selectedCount(), INVALID_RESULT);
        }
    }

    /** Only selected stable IDs in authored order; no answers, content or unselected membership. */
    public record Result(int version, Evidence evidence, List<UUID> itemIds) {
        public Result {
            requireVersion(version);
            require(evidence != null, INVALID_RESULT);
            itemIds = copyIds(itemIds);
            if (evidence.random() != null) require(itemIds.size() == evidence.random().selectedCount(), INVALID_RESULT);
            if (evidence.configuration().strategy() == Strategy.MANUAL_SET) {
                require(new HashSet<>(itemIds).equals(new HashSet<>(evidence.configuration().manualItemIds())),
                        INVALID_RESULT);
            }
        }
    }

    private static List<UUID> canonicalIds(List<UUID> ids) {
        return copyIds(ids).stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private static List<UUID> copyIds(List<UUID> ids) {
        require(ids != null, INVALID_INPUT);
        var seen = new HashSet<UUID>();
        for (UUID id : ids) {
            require(id != null, INVALID_ITEM);
            require(seen.add(id), DUPLICATE_ITEM);
        }
        return List.copyOf(ids);
    }

    private static void requireReference(int version, UUID revisionId, int revisionVersion) {
        requireVersion(version);
        require(revisionVersion == VERSION, UNSUPPORTED_REVISION_VERSION);
        require(revisionId != null, INVALID_INPUT);
    }

    private static void requireVersion(int version) { require(version == VERSION, UNSUPPORTED_VERSION); }
}
