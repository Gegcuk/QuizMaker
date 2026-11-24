package uk.gegc.quizmaker.features.learning.domain.repository;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSet.*;

/** All operations join the caller's transaction; source reads retain current authority. */
public interface LearningSetRepository {
    record Receipt(String input, Acknowledgement result) { }
    long lockGraph(UUID learner, boolean write);
    long advanceGraph(UUID learner);
    Optional<View> find(UUID learner, UUID set);
    View create(UUID learner, String name, Instant at);
    View change(UUID learner, View set, Member member, boolean present, Instant at);
    View delete(UUID learner, View set, Instant at);
    boolean containsQuestion(Member member);
    boolean reaches(UUID learner, UUID from, UUID target);
    void lockSources(UUID learner, Scope scope);
    List<View> list(UUID learner, int take, UUID after);
    List<Member> members(UUID learner, UUID set, int take, String after);
    List<Item> items(UUID learner, Scope scope, Window window, Instant now, int take, Cursor after, boolean custom);
    Optional<Receipt> receipt(UUID learner, UUID command);
    void record(UUID learner, UUID command, String input, Acknowledgement ack, Instant at);
}
