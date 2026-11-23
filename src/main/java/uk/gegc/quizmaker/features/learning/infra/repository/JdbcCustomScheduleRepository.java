package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.UUID;
import java.util.function.Supplier;
import uk.gegc.quizmaker.features.learning.domain.model.CustomSchedule.State;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.repository.CustomScheduleRepository;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
import uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcCustomScheduleRepository implements CustomScheduleRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final LearningPlanFormat format = new LearningPlanFormat();
    public JdbcCustomScheduleRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public State state(Key key, Cycle cycle, Definition definition) {
        return safe(() -> jdbc.queryForObject("""
            SELECT s.schedule_version,s.reviewed_at,s.time_zone,e.after_json,c.created_at
            FROM learning_custom_state s JOIN learning_schedule_cycle c ON c.id=s.cycle_id
            LEFT JOIN learning_custom_change e ON e.cycle_id=s.cycle_id AND e.version=s.schedule_version
            WHERE s.cycle_id=UUID_TO_BIN(:cycle) AND s.learner_id=UUID_TO_BIN(:learner)
            """, scope(key,cycle), (r,n) -> {
                var reviewed = r.getObject("reviewed_at",LocalDateTime.class);
                String zone = r.getString("time_zone");
                var state = new State(r.getLong("schedule_version"),cycle.sequence(),cycle.stage(),
                        reviewed == null ? null : reviewed.toInstant(ZoneOffset.UTC),cycle.dueAt(),
                        zone == null ? definition.timeZone() : zone,cycle.completed());
                if (state.version() == 0) require(state.sequence() == 0 && state.stage() == 0
                        && !state.completed() && state.dueAt().equals(r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),CORRUPT);
                else require(state.equals(format.decode(r.getString("after_json"),State.class)),CORRUPT);
                return state;
            }));
    }
    @Override public Interval stage(UUID cycleId,int stage) {
        return safe(() -> jdbc.query("SELECT interval_json FROM learning_custom_stage WHERE cycle_id=UUID_TO_BIN(:cycle) AND stage=:stage",
                new MapSqlParameterSource("cycle",cycleId.toString()).addValue("stage",stage),
                (r,n) -> format.decode(r.getString(1),Interval.class)).stream().findFirst()
                .orElseThrow(() -> new uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException(CORRUPT)));
    }
    @Override public void change(Key key,Cycle cycle,State before,State after,UUID command,String kind,Interval generated,Instant at) {
        safe(() -> {
            var p = scope(key,cycle).addValue("version",after.version()).addValue("previous",before.version())
                    .addValue("sequence",after.sequence()).addValue("stage",after.stage()).addValue("due",time(after.dueAt()))
                    .addValue("reviewed",after.reviewedAt()==null?null:time(after.reviewedAt()))
                    .addValue("zone",after.timeZone()).addValue("completed",after.completed())
                    .addValue("command",command.toString()).addValue("kind",kind).addValue("now",time(at))
                    .addValue("before",format.encode(before)).addValue("after",format.encode(after));
            // Ratings and timezone confirmations share this command namespace. The caller's
            // learner admission lock serializes fresh writes before this check and the insert.
            require(!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM learning_custom_change
                        WHERE learner_id=UUID_TO_BIN(:learner) AND command_id=UUID_TO_BIN(:command))
                    """,p,Boolean.class)),CONFLICT);
            if (generated != null) {
                p.addValue("interval",format.encode(generated));
                jdbc.update("""
                    INSERT INTO learning_custom_stage(cycle_id,stage,interval_json) VALUES(UUID_TO_BIN(:cycle),:stage,:interval)
                    ON DUPLICATE KEY UPDATE stage=learning_custom_stage.stage
                    """,p);
                require(stage(cycle.cycleId(),after.stage()).equals(generated),CORRUPT);
            }
            jdbc.update("""
                INSERT INTO learning_custom_change(cycle_id,version,learner_id,command_id,kind,before_json,after_json,created_at)
                VALUES(UUID_TO_BIN(:cycle),:version,UUID_TO_BIN(:learner),UUID_TO_BIN(:command),:kind,:before,:after,:now)
                """,p);
            require(jdbc.update("""
                UPDATE learning_custom_state SET schedule_version=:version,sequence=:sequence,stage=:stage,due_at=:due,
                    reviewed_at=:reviewed,time_zone=:zone,completed=:completed
                WHERE cycle_id=UUID_TO_BIN(:cycle) AND learner_id=UUID_TO_BIN(:learner) AND schedule_version=:previous
                """,p)==1,CONFLICT);
            return null;
        });
    }
    private static MapSqlParameterSource scope(Key key,Cycle cycle) {
        return new MapSqlParameterSource("learner",key.learnerId().toString()).addValue("cycle",cycle.cycleId().toString());
    }
    private static LocalDateTime time(Instant at) { return LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
    private static <T> T safe(Supplier<T> action) {
        try { return action.get(); } catch(DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
}
