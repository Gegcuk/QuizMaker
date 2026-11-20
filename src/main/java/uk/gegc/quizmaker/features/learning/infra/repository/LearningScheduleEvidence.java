package uk.gegc.quizmaker.features.learning.infra.repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

/** Identical authoritative state/initial-receipt/last-event validation for single and batch reads. */
final class LearningScheduleEvidence {
    private static final FsrsStateFormat FORMAT = new FsrsStateFormat();
    private LearningScheduleEvidence() { }

    static Snapshot read(ResultSet row, Key key) throws SQLException {
        require(row.getBytes("active_cycle_id") != null, CORRUPT);
        require("fsrs/v1".equals(row.getString("strategy_id")), UNSUPPORTED_VERSION);
        var value = snapshot(row.getLong("cycle_version"), row.getString("state"));
        var id = value.state().identity();
        require(id.cycleId().equals(uuid(row, "active_cycle_id")) && id.learnerId().equals(key.learnerId())
                && id.quizId().equals(key.source().quizId()) && id.quizRevisionId().equals(key.source().quizRevisionId())
                && id.questionRevisionId().equals(key.questionRevisionId())
                && value.state().sequence() == row.getLong("sequence"), CORRUPT);
        var initial = snapshot(row.getLong("initial_version"), row.getString("initial_state"));
        var created = row.getObject("created_at", LocalDateTime.class);
        require(created != null && initial.cycleVersion() == value.cycleVersion() && initial.state().sequence() == 0
                && initial.state().identity().equals(id) && initial.state().manifest().equals(value.state().manifest())
                && initial.state().dueAt().equals(created.toInstant(ZoneOffset.UTC)), CORRUPT);
        if (value.state().sequence() == 0) require(initial.equals(value), CORRUPT);
        else {
            require(row.getString("transition") != null, CORRUPT);
            require(FORMAT.decodeTransition(row.getString("transition")).state().equals(value.state()), CORRUPT);
        }
        return value;
    }

    private static Snapshot snapshot(long version, String state) {
        require(version > 0 && state != null, CORRUPT);
        return new Snapshot(version, FORMAT.decodeState(state));
    }
    static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] bytes = row.getBytes(column);
        require(bytes != null && bytes.length == 16, CORRUPT);
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
