package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningPlanRepository;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
import uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningPlanRepository implements LearningPlanRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final LearningPlanFormat format = new LearningPlanFormat();
    public JdbcLearningPlanRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String PLANS = """
        SELECT BIN_TO_UUID(p.id) id,p.version,p.plan_version,p.paused,p.created_at,p.updated_at,v.definition
        FROM learning_plan p LEFT JOIN learning_plan_version v ON v.plan_id=p.id AND v.version=p.plan_version
        WHERE p.learner_id=UUID_TO_BIN(:learner)
        """;
    @Override public Optional<View> plan(UUID learner, UUID plan, boolean lock) {
        return query(PLANS + " AND p.id=UUID_TO_BIN(:plan)" + (lock ? " FOR UPDATE" : ""),
                owner(learner).addValue("plan", plan.toString()), this::view).stream().findFirst();
    }
    @Override public List<View> plans(UUID learner, int take, UUID after) {
        return query(PLANS + (after == null ? "" : " AND p.id>UUID_TO_BIN(:after)") + " ORDER BY p.id LIMIT :take",
                owner(learner).addValue("after", text(after)).addValue("take", take), this::view);
    }
    @Override public void save(UUID learner, View plan, boolean configurationChanged) {
        var p = owner(learner).addValue("plan", text(plan.planId())).addValue("version", plan.version())
                .addValue("pv", plan.planVersion()).addValue("paused", plan.paused())
                .addValue("created", time(plan.createdAt())).addValue("updated", time(plan.updatedAt()));
        if (plan.version() == 1) update("""
            INSERT INTO learning_plan(id,learner_id,version,plan_version,paused,created_at,updated_at)
            VALUES(UUID_TO_BIN(:plan),UUID_TO_BIN(:learner),:version,:pv,:paused,:created,:updated)
            """, p);
        else require(update("""
            UPDATE learning_plan SET version=:version,plan_version=:pv,paused=:paused,updated_at=:updated
            WHERE learner_id=UUID_TO_BIN(:learner) AND id=UUID_TO_BIN(:plan) AND version=:previous
            """, p.addValue("previous", plan.version() - 1)) == 1, CONFLICT);
        if (configurationChanged) update("""
            INSERT INTO learning_plan_version(plan_id,version,definition,created_at)
            VALUES(UUID_TO_BIN(:plan),:pv,:definition,:updated)
            """, p.addValue("definition", format.encode(plan.definition())));
    }
    @Override public List<Version> versions(UUID learner, UUID plan, int take, long after) {
        return query("""
            SELECT v.version,v.definition,v.created_at FROM learning_plan_version v
            JOIN learning_plan p ON p.id=v.plan_id WHERE p.learner_id=UUID_TO_BIN(:learner)
            AND p.id=UUID_TO_BIN(:plan) AND v.version>:after ORDER BY v.version LIMIT :take
            """, owner(learner).addValue("plan", text(plan)).addValue("after", after).addValue("take", take), this::version);
    }
    @Override public Optional<Version> version(UUID learner, UUID plan, long version) {
        return query("""
            SELECT v.version,v.definition,v.created_at FROM learning_plan_version v JOIN learning_plan p ON p.id=v.plan_id
            WHERE p.learner_id=UUID_TO_BIN(:learner) AND p.id=UUID_TO_BIN(:plan) AND v.version=:version
            """, owner(learner).addValue("plan", text(plan)).addValue("version", version), this::version).stream().findFirst();
    }
    @Override public Optional<Receipt> receipt(UUID learner, UUID command) {
        return query("""
            SELECT input_json,result_json FROM learning_plan_command
            WHERE learner_id=UUID_TO_BIN(:learner) AND command_id=UUID_TO_BIN(:command)
            """, owner(learner).addValue("command", text(command)), (r,n) ->
                new Receipt(r.getString(1), format.decode(r.getString(2), Acknowledgement.class))).stream().findFirst();
    }
    @Override public void record(UUID learner, UUID command, String input, Acknowledgement result, Instant now) {
        update("""
            INSERT INTO learning_plan_command(learner_id,command_id,input_json,result_json,created_at)
            VALUES(UUID_TO_BIN(:learner),UUID_TO_BIN(:command),:input,:result,:now)
            """, owner(learner).addValue("command", text(command)).addValue("input", input)
                .addValue("result", format.encode(result)).addValue("now", time(now)));
    }
    private static String bindingTable(UUID question) { return question == null ? "learning_plan_quiz_default" : "learning_plan_question_override"; }
    private static String bindingWhere(UUID question) {
        return "learner_id=UUID_TO_BIN(:learner) AND quiz_id=UUID_TO_BIN(:quiz)"
                + (question == null ? "" : " AND quiz_revision_id=UUID_TO_BIN(:revision) AND question_revision_id=UUID_TO_BIN(:question)");
    }
    @Override public Binding binding(UUID learner, Source source, UUID question) {
        return query("SELECT version,BIN_TO_UUID(plan_id) plan," + (question == null ? "NULL" : "plan_version")
                        + " pv FROM " + bindingTable(question) + " WHERE " + bindingWhere(question),
                scope(learner, source, question), (r,n) -> new Binding(r.getLong(1), uuid(r.getString(2)), r.getObject(3, Long.class)))
                .stream().findFirst().orElse(new Binding(0, null, null));
    }
    @Override public void bind(UUID learner, Source source, UUID question, Binding binding) {
        var p = scope(learner, source, question).addValue("version", binding.version())
                .addValue("plan", text(binding.planId())).addValue("pv", binding.planVersion());
        if (binding.version() == 1) {
            String fields = question == null ? "" : ",quiz_revision_id,question_revision_id,plan_version";
            String values = question == null ? "" : ",UUID_TO_BIN(:revision),UUID_TO_BIN(:question),:pv";
            update("INSERT INTO " + bindingTable(question) + "(learner_id,quiz_id,version,plan_id" + fields
                    + ") VALUES(UUID_TO_BIN(:learner),UUID_TO_BIN(:quiz),:version,UUID_TO_BIN(:plan)" + values + ")", p);
        } else require(update("UPDATE " + bindingTable(question)
                + " SET version=:version,plan_id=UUID_TO_BIN(:plan)" + (question == null ? "" : ",plan_version=:pv")
                + " WHERE " + bindingWhere(question) + " AND version=:previous", p.addValue("previous", binding.version() - 1)) == 1, CONFLICT);
    }
    private static final String CYCLES = """
        SELECT BIN_TO_UUID(s.question_revision_id) question,BIN_TO_UUID(c.id) cycle,c.cycle_version,c.strategy_id,
               BIN_TO_UUID(f.plan_id) plan,f.plan_version,f.sequence,f.stage,f.due_at,f.completed,p.paused,
               (f.cycle_id IS NULL OR p.id IS NULL OR v.plan_id IS NULL OR f.learner_id<>c.learner_id
                OR NOT (c.strategy_id <=> 'custom/v1')) invalid
        FROM learning_schedule_slot s LEFT JOIN learning_schedule_cycle c ON c.id=s.active_cycle_id
        LEFT JOIN learning_custom_state f ON f.cycle_id=c.id
        LEFT JOIN learning_plan p ON p.id=f.plan_id AND p.learner_id=s.learner_id
        LEFT JOIN learning_plan_version v ON v.plan_id=f.plan_id AND v.version=f.plan_version
        WHERE s.learner_id=UUID_TO_BIN(:learner) AND s.quiz_id=UUID_TO_BIN(:quiz)
          AND s.quiz_revision_id=UUID_TO_BIN(:revision)
        """;
    @Override public Optional<Cycle> custom(Key key) {
        return query(CYCLES + " AND c.strategy_id='custom/v1' AND s.question_revision_id=UUID_TO_BIN(:question)",
                scope(key.learnerId(), key.source(), key.questionRevisionId()), this::cycle).stream().findFirst();
    }
    @Override public void createCustom(Key key, Cycle cycle) {
        update("""
            INSERT INTO learning_custom_state(cycle_id,learner_id,plan_id,plan_version,sequence,stage,due_at,completed)
            VALUES(UUID_TO_BIN(:cycle),UUID_TO_BIN(:learner),UUID_TO_BIN(:plan),:pv,0,0,:due,FALSE)
            """, owner(key.learnerId()).addValue("cycle", text(cycle.cycleId())).addValue("plan", text(cycle.planId()))
                .addValue("pv", cycle.planVersion()).addValue("due", time(cycle.dueAt())));
    }
    @Override public List<Cycle> cycles(UUID learner, Source source, Window window, Instant now, int take, Cursor after) {
        String eligible = window == Window.ALL ? "TRUE" : "f.completed=FALSE AND p.paused=FALSE AND f.due_at"
                + (window == Window.DUE ? "<=:now" : ">:now");
        String position = after == null ? "" : " AND (due_at>:after OR (due_at=:after AND UUID_TO_BIN(question)>UUID_TO_BIN(:afterQuestion)))";
        return query("WITH selected AS (" + CYCLES + " AND (c.strategy_id<>'fsrs/v1' OR c.id IS NULL) AND ("
                        + eligible + " OR f.cycle_id IS NULL OR p.id IS NULL OR v.plan_id IS NULL"
                        + " OR NOT (c.strategy_id <=> 'custom/v1'))) SELECT * FROM selected"
                        + " WHERE invalid OR (TRUE" + position + ") ORDER BY invalid DESC,due_at,UUID_TO_BIN(question) LIMIT :take",
                scope(learner, source, null).addValue("now", time(now)).addValue("take", take)
                        .addValue("after", after == null ? null : time(after.dueAt()))
                        .addValue("afterQuestion", after == null ? null : text(after.questionRevisionId())), this::cycle);
    }
    private View view(ResultSet r, int n) throws SQLException {
        require(r.getLong("version") >= r.getLong("plan_version") && r.getLong("plan_version") > 0, CORRUPT);
        return new View(uuid(r.getString("id")),r.getLong("version"),r.getLong("plan_version"),
                format.decode(r.getString("definition"), Definition.class),r.getBoolean("paused"), instant(r,"created_at"),instant(r,"updated_at"));
    }
    private Version version(ResultSet r, int n) throws SQLException {
        return new Version(r.getLong("version"),format.decode(r.getString("definition"), Definition.class),instant(r,"created_at"));
    }
    private Cycle cycle(ResultSet r, int n) throws SQLException {
        require(r.getString("strategy_id")!=null,CORRUPT);
        require("custom/v1".equals(r.getString("strategy_id")),UNSUPPORTED_VERSION);
        require(!r.getBoolean("invalid") && r.getLong("sequence") >= 0 && r.getInt("stage") >= 0, CORRUPT);
        return new Cycle(uuid(r.getString("question")),uuid(r.getString("cycle")),r.getLong("cycle_version"),Strategy.CUSTOM,
                uuid(r.getString("plan")),r.getLong("plan_version"),r.getLong("sequence"),r.getInt("stage"),
                instant(r,"due_at"),r.getBoolean("paused"),r.getBoolean("completed"));
    }
    private static MapSqlParameterSource owner(UUID learner) { return new MapSqlParameterSource("learner", learner.toString()); }
    private static MapSqlParameterSource scope(UUID learner, Source source, UUID question) {
        return owner(learner).addValue("quiz",text(source.quizId())).addValue("revision",text(source.quizRevisionId())).addValue("question",text(question));
    }
    private <T> List<T> query(String sql, MapSqlParameterSource p, RowMapper<T> mapper) { return safe(() -> jdbc.query(sql,p,mapper)); }
    private int update(String sql, MapSqlParameterSource p) { return safe(() -> jdbc.update(sql,p)); }
    private static <T> T safe(Supplier<T> work) { try { return work.get(); } catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); } }
    private static String text(UUID id) { return id == null ? null : id.toString(); }
    private static UUID uuid(String id) { return id == null ? null : UUID.fromString(id); }
    private static LocalDateTime time(Instant time) { return LocalDateTime.ofInstant(time,ZoneOffset.UTC); }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        var time = r.getObject(name,LocalDateTime.class); require(time != null,CORRUPT); return time.toInstant(ZoneOffset.UTC);
    }
}
