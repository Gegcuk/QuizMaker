package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.StartFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.UngradedPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException;
import uk.gegc.quizmaker.features.attempt.domain.repository.UngradedActivityRepository;
import uk.gegc.quizmaker.features.attempt.infra.activity.ActivityContractJson;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import uk.gegc.quizmaker.shared.util.PrivateSqlText;

import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException.Reason.CONTRADICTORY_STATE;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcUngradedActivityRepository implements UngradedActivityRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ActivityContractJson json = new ActivityContractJson();

    public JdbcUngradedActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public boolean isOwnedUngraded(UUID attemptId, UUID trustedUserId) {
        return access(() -> !jdbc.query("""
                SELECT 1 FROM attempt_participant_subject s
                JOIN attempt_lifecycle_state l ON l.attempt_id=s.attempt_id
                WHERE s.user_id=UUID_TO_BIN(:user) AND s.attempt_id=UUID_TO_BIN(:attempt)
                  AND s.schema_version=1 AND s.subject_type='AUTHENTICATED_USER' AND l.activity_kind='UNGRADED'
                """, Map.of("user", trustedUserId.toString(), "attempt", attemptId.toString()),
                (row, index) -> true).isEmpty());
    }

    @Override public void bind(Binding binding) {
        access(() -> jdbc.update("""
                INSERT INTO attempt_ungraded_activity
                    (attempt_id, form_id, response_retention, privacy_notice_presented, activity_policy)
                VALUES (UUID_TO_BIN(:attempt), UUID_TO_BIN(:form), :retention, :notice, :policy)
                """, Map.of("attempt", binding.attemptId().toString(), "form", binding.formId().toString(),
                "retention", binding.policy().responseRetention().name(),
                "notice", binding.startFacts().privacyNoticePresented(), "policy", json.encodePolicy(binding.policy()))));
    }

    @Override public Optional<Binding> read(UUID attemptId, UUID formId) {
        return access(() -> jdbc.query("""
                SELECT activity_policy, response_retention, privacy_notice_presented
                FROM attempt_ungraded_activity
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND form_id=UUID_TO_BIN(:form)
                """, Map.of("attempt", attemptId.toString(), "form", formId.toString()), (row, index) -> {
            if (!(json.decodePolicy(row.getString("activity_policy")) instanceof UngradedPolicy policy)
                    || !policy.responseRetention().name().equals(row.getString("response_retention"))) {
                throw new ActivityContractException(CONTRADICTORY_STATE);
            }
            return new Binding(attemptId, formId, policy, new StartFacts(row.getBoolean("privacy_notice_presented")));
        }).stream().findFirst());
    }

    @Override public boolean participated(UUID attemptId, UUID formItemId) {
        return access(() -> !jdbc.query("""
                SELECT 1 FROM attempt_ungraded_participation
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND form_item_id=UUID_TO_BIN(:item)
                """, Map.of("attempt", attemptId.toString(), "item", formItemId.toString()),
                (row, index) -> true).isEmpty());
    }

    @Override public Set<UUID> participatedItems(UUID attemptId) {
        return access(() -> Set.copyOf(jdbc.query("""
                SELECT BIN_TO_UUID(form_item_id) FROM attempt_ungraded_participation
                WHERE attempt_id=UUID_TO_BIN(:attempt)
                """, Map.of("attempt", attemptId.toString()),
                (row, index) -> UUID.fromString(row.getString(1)))));
    }

    @Override public void insert(Participation participation) {
        var binding = participation.binding();
        access(() -> jdbc.update("""
                INSERT INTO attempt_ungraded_participation
                    (id, attempt_id, form_id, form_item_id, question_revision_id, response_contract_id,
                     response_contract_version, response_retention, normalized_response, command_id, accepted_at)
                VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:attempt), UUID_TO_BIN(:form), UUID_TO_BIN(:item),
                        UUID_TO_BIN(:question), :contract, :version, :retention, :response, UUID_TO_BIN(:command), :time)
                """, new MapSqlParameterSource()
                .addValue("id", participation.id().toString())
                .addValue("attempt", binding.attemptId().toString())
                .addValue("form", binding.formId().toString())
                .addValue("item", participation.formItemId().toString())
                .addValue("question", participation.questionRevisionId().toString())
                .addValue("contract", participation.responseContract().id())
                .addValue("version", participation.responseContract().version())
                .addValue("retention", binding.policy().responseRetention().name())
                .addValue("response", PrivateSqlText.of(participation.normalizedResponse()))
                .addValue("command", participation.commandId().toString())
                .addValue("time", Timestamp.from(participation.acceptedAt()))));
    }

    private static <T> T access(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }
}
