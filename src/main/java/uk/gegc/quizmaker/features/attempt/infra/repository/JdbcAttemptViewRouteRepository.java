package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptViewRouteRepository;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAttemptViewRouteRepository implements AttemptViewRouteRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAttemptViewRouteRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean isOwnedLegacyOnly(UUID attemptId, UUID trustedUserId) {
        var parameters = Map.of("id", attemptId.toString(), "user", trustedUserId.toString());
        // This nonlocking probe cannot wait on a foreign attempt and disclose its existence via BUSY.
        // Legacy data keeps its existing snapshot semantics; target access uses #537 current reads.
        if (jdbc.query("SELECT 1 FROM attempts WHERE id=UUID_TO_BIN(:id) AND user_id=UUID_TO_BIN(:user)",
                parameters, (row, index) -> true).isEmpty()) return false;
        // Never interpret incomplete, unknown-version or ambiguous target evidence as a legacy fallback.
        // Current reads also detect target roots committed after a caller's old RR snapshot.
        for (String table : java.util.List.of("attempt_participant_subject", "attempt_lifecycle_state", "attempt_trace_stream")) {
            if (!jdbc.query("SELECT 1 FROM " + table + " WHERE attempt_id=UUID_TO_BIN(:id) FOR SHARE",
                    parameters, (row, index) -> true).isEmpty())
                throw new AttemptDeliveryException(AttemptDeliveryException.Reason.NOT_FOUND);
        }
        return true;
    }
}
