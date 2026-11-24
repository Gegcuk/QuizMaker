package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSet.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningSetRepository;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
import uk.gegc.quizmaker.features.learning.infra.scheduling.*;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static uk.gegc.quizmaker.features.learning.infra.repository.LearningScheduleEvidence.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcLearningSetRepository implements LearningSetRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final LearningPlanFormat format=new LearningPlanFormat();
    private final com.fasterxml.jackson.databind.ObjectMapper grantsJson=new com.fasterxml.jackson.databind.ObjectMapper();
    private final String manifest=new FsrsStateFormat().encodeManifest(FsrsManifest.approved());
    public JdbcLearningSetRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public long lockGraph(UUID learner,boolean write) {
        return safe(() -> {
            var p=owner(learner);
            if(write) jdbc.update("INSERT INTO learning_set_graph VALUES(UUID_TO_BIN(:learner),0) ON DUPLICATE KEY UPDATE learner_id=learner_id",p);
            var rows=jdbc.query("SELECT version FROM learning_set_graph WHERE learner_id=UUID_TO_BIN(:learner) FOR "+(write?"UPDATE":"SHARE"),p,(r,n)->r.getLong(1));
            return rows.isEmpty()?0:rows.get(0);
        });
    }
    @Override public long advanceGraph(UUID learner) {
        return safe(() -> {
            require(jdbc.update("UPDATE learning_set_graph SET version=version+1 WHERE learner_id=UUID_TO_BIN(:learner)",owner(learner))==1,CORRUPT);
            return jdbc.queryForObject("SELECT version FROM learning_set_graph WHERE learner_id=UUID_TO_BIN(:learner)",owner(learner),Long.class);
        });
    }
    @Override public Optional<View> find(UUID learner,UUID set) {
        return safe(() -> jdbc.query("SELECT * FROM learning_set WHERE learner_id=UUID_TO_BIN(:learner) AND id=UUID_TO_BIN(:set)",
                owner(learner).addValue("set",set.toString()),(r,n)->view(r)).stream().findFirst());
    }
    @Override public View create(UUID learner,String name,Instant at) {
        UUID id=UUID.randomUUID();
        return safe(() -> {
            jdbc.update("INSERT INTO learning_set VALUES(UUID_TO_BIN(:id),UUID_TO_BIN(:learner),:name,1,FALSE,:at,:at)",
                    owner(learner).addValue("id",id.toString()).addValue("name",name).addValue("at",time(at)));
            return find(learner,id).orElseThrow(() -> new LearningSessionException(CORRUPT));
        });
    }
    @Override public View change(UUID learner,View set,Member member,boolean present,Instant at) {
        return safe(() -> {
            var p=member(owner(learner).addValue("set",set.setId().toString()),member);
            if(present) jdbc.update("""
                INSERT INTO learning_set_member VALUES(UUID_TO_BIN(:learner),UUID_TO_BIN(:set),:key,:kind,
                    UUID_TO_BIN(:child),UUID_TO_BIN(:quiz),UUID_TO_BIN(:revision),:schema,UUID_TO_BIN(:question))
                ON DUPLICATE KEY UPDATE member_key=member_key
                """,p);
            else jdbc.update("DELETE FROM learning_set_member WHERE learner_id=UUID_TO_BIN(:learner) AND set_id=UUID_TO_BIN(:set) AND member_key=:key",p);
            return update(learner,set,false,at);
        });
    }
    @Override public View delete(UUID learner,View set,Instant at) {
        return safe(() -> {
            var p=owner(learner).addValue("set",set.setId().toString()).addValue("at",time(at));
            jdbc.update("""
                UPDATE learning_set p JOIN learning_set_member m ON m.learner_id=p.learner_id AND m.set_id=p.id
                SET p.version=p.version+1,p.updated_at=:at
                WHERE m.learner_id=UUID_TO_BIN(:learner) AND m.child_set_id=UUID_TO_BIN(:set)
                """,p);
            jdbc.update("DELETE FROM learning_set_member WHERE learner_id=UUID_TO_BIN(:learner) AND (set_id=UUID_TO_BIN(:set) OR child_set_id=UUID_TO_BIN(:set))",p);
            return update(learner,set,true,at);
        });
    }
    private View update(UUID learner,View set,boolean deleted,Instant at) {
        require(set.version()<Long.MAX_VALUE,CONFLICT);
        require(jdbc.update("""
            UPDATE learning_set SET version=version+1,deleted=:deleted,updated_at=:at
            WHERE learner_id=UUID_TO_BIN(:learner) AND id=UUID_TO_BIN(:set) AND version=:version AND deleted=FALSE
            """,owner(learner).addValue("set",set.setId().toString()).addValue("version",set.version())
                .addValue("deleted",deleted).addValue("at",time(at)))==1,CONFLICT);
        return find(learner,set.setId()).orElseThrow(() -> new LearningSessionException(CORRUPT));
    }
    @Override public boolean containsQuestion(Member member) {
        return safe(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM quiz_revision_items WHERE quiz_revision_id=UUID_TO_BIN(:revision)
                AND question_revision_id=UUID_TO_BIN(:question))
            """,member(new MapSqlParameterSource(),member),Boolean.class)));
    }
    private static final String REACHABLE="""
        WITH RECURSIVE reachable(id) AS (
            SELECT id FROM learning_set WHERE learner_id=UUID_TO_BIN(:learner) AND id=UUID_TO_BIN(:root) AND deleted=FALSE
            UNION DISTINCT
            SELECT m.child_set_id FROM reachable r JOIN learning_set_member m ON m.set_id=r.id
            JOIN learning_set c ON c.id=m.child_set_id AND c.learner_id=m.learner_id AND c.deleted=FALSE
            WHERE m.learner_id=UUID_TO_BIN(:learner) AND m.kind='SET'
        )
        """;
    @Override public boolean reaches(UUID learner,UUID from,UUID target) {
        return safe(() -> Boolean.TRUE.equals(jdbc.queryForObject(REACHABLE+" SELECT EXISTS(SELECT 1 FROM reachable WHERE id=UUID_TO_BIN(:target))",
                owner(learner).addValue("root",from.toString()).addValue("target",target.toString()),Boolean.class)));
    }
    private static String targets(Scope scope) {
        if(scope.setId()==null) return """
            WITH targets AS (
                SELECT quiz_id,id AS quiz_revision_id,schema_version AS revision_schema_version,NULL AS question_revision_id
                FROM quiz_revisions WHERE quiz_id=UUID_TO_BIN(:quiz) AND id=UUID_TO_BIN(:revision) AND schema_version=:schema
            )
            """;
        return REACHABLE+"""
            , targets AS (
                SELECT DISTINCT m.quiz_id,m.quiz_revision_id,m.revision_schema_version,m.question_revision_id
                FROM reachable r JOIN learning_set_member m ON m.set_id=r.id
                WHERE m.learner_id=UUID_TO_BIN(:learner) AND m.kind IN ('QUIZ','QUESTION')
            )
            """;
    }
    /** Same initial author authority as #692, evaluated for all reachable publications in one query.
     * Retain each positive mutable grant through commit before returning metadata from a later query. */
    private List<Source> lockSources(UUID learner,Scope scope,boolean recursive) {
        return safe(() -> {
            String selected=recursive?targets(scope):"""
                WITH targets AS (
                    SELECT DISTINCT quiz_id,quiz_revision_id,revision_schema_version
                    FROM learning_set_member WHERE learner_id=UUID_TO_BIN(:learner)
                        AND set_id=UUID_TO_BIN(:root) AND kind IN ('QUIZ','QUESTION')
                )
                """;
            return jdbc.query(selected+"""
                SELECT t.quiz_id,t.quiz_revision_id,t.revision_schema_version
                FROM (SELECT DISTINCT quiz_id,quiz_revision_id,revision_schema_version FROM targets) t
                JOIN users u ON u.user_id=UUID_TO_BIN(:learner) AND u.is_active=TRUE AND u.is_deleted=FALSE AND u.username<>'anonymous'
                JOIN quizzes q ON q.quiz_id=t.quiz_id AND q.creator_id=u.user_id AND q.is_deleted=FALSE
                JOIN quiz_publications p ON p.quiz_id=q.quiz_id AND p.revision_id=t.quiz_revision_id AND p.canonical_version=1
                JOIN quiz_revisions r ON r.id=p.revision_id AND r.quiz_id=q.quiz_id AND r.owner_id=u.user_id AND r.schema_version=t.revision_schema_version
                JOIN user_roles ur ON ur.user_id=u.user_id
                JOIN role_permissions rp ON rp.role_id=ur.role_id
                JOIN permissions perm ON perm.permission_id=rp.permission_id
                WHERE perm.permission_name IN ('QUIZ_UPDATE','QUIZ_MODERATE','QUIZ_ADMIN')
                FOR SHARE OF u,q,ur,rp,perm
                """,scope(learner,scope),(r,n)->new Source(1,uuid(r,"quiz_id"),uuid(r,"quiz_revision_id"),r.getInt("revision_schema_version")))
                    .stream().distinct().toList();
        });
    }
    /** Bind the later RC query to exactly the publications whose positive grants we hold.
     * A newly committed grant must wait for a fresh read. One JSON metadata parameter keeps
     * the filter batched without a per-publication query or an expanding placeholder list. */
    private String heldSources(List<Source> held,String alias,MapSqlParameterSource parameters) {
        try { parameters.addValue("heldSources",grantsJson.writeValueAsString(held)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new LearningSessionException(CORRUPT); }
        return """
            EXISTS(SELECT 1 FROM JSON_TABLE(:heldSources,'$[*]' COLUMNS(
                quiz_id CHAR(36) PATH '$.quizId',quiz_revision_id CHAR(36) PATH '$.quizRevisionId',
                revision_schema_version INT PATH '$.revisionSchemaVersion')) held
                WHERE UUID_TO_BIN(held.quiz_id)=%1$s.quiz_id AND UUID_TO_BIN(held.quiz_revision_id)=%1$s.quiz_revision_id
                    AND held.revision_schema_version=%1$s.revision_schema_version)
            """.formatted(alias);
    }
    @Override public List<View> list(UUID learner,int take,UUID after) {
        return safe(() -> jdbc.query("SELECT * FROM learning_set WHERE learner_id=UUID_TO_BIN(:learner) AND deleted=FALSE"
                +(after==null?"":" AND id>UUID_TO_BIN(:after)")+" ORDER BY id LIMIT :take",
                owner(learner).addValue("take",take).addValue("after",after==null?null:after.toString()),(r,n)->view(r)));
    }
    @Override public List<Member> members(UUID learner,UUID set,int take,String after) {
        return safe(() -> {
            // Direct folder contents need only their own grants, never the descendant graph.
            var held=lockSources(learner,new Scope(set,null),false);
            var p=owner(learner).addValue("set",set.toString()).addValue("take",take).addValue("after",after);
            String authorized=heldSources(held,"m",p);
            return jdbc.query("""
            SELECT m.* FROM learning_set_member m
            WHERE m.learner_id=UUID_TO_BIN(:learner) AND m.set_id=UUID_TO_BIN(:set)
                AND (m.kind='SET' OR %s)
            """.formatted(authorized)+(after==null?"":" AND m.member_key>:after")+" ORDER BY m.member_key LIMIT :take",p,
                (r,n)->new Member(Kind.valueOf(r.getString("kind")),optionalUuid(r,"child_set_id"),
                        r.getBytes("quiz_id")==null?null:new Source(1,uuid(r,"quiz_id"),uuid(r,"quiz_revision_id"),r.getInt("revision_schema_version")),
                        optionalUuid(r,"question_revision_id")));
        });
    }
    @Override public List<Item> items(UUID learner,Scope scope,Window window,Instant now,int take,Cursor after,boolean custom) {
        var held=lockSources(learner,scope,true);
        var p=scope(learner,scope).addValue("now",time(now)).addValue("take",take).addValue("manifest",manifest).addValue("custom",custom);
        String authorized=heldSources(held,"t",p);
        String position="";
        if(after!=null) {
            position=" AND (due_at,quiz_id,quiz_revision_id,question_revision_id) > (:afterTime,UUID_TO_BIN(:afterQuiz),UUID_TO_BIN(:afterRevision),UUID_TO_BIN(:afterQuestion))";
            p.addValue("afterTime",time(after.dueAt())).addValue("afterQuiz",after.quizId().toString())
                    .addValue("afterRevision",after.revisionId().toString()).addValue("afterQuestion",after.questionId().toString());
        }
        // Recursive UNION DISTINCT visits each reachable set once, including diamonds. Selection
        // deduplicates slot identity before state joins. No source body, whole history or per-parent
        // query is loaded. Sorting still examines reachable authorized slots, not just LIMIT rows.
        String query=targets(scope)+"""
            , eligible AS (
                SELECT DISTINCT s.*,t.revision_schema_version FROM targets t JOIN learning_schedule_slot s
                    ON s.quiz_id=t.quiz_id AND s.quiz_revision_id=t.quiz_revision_id
                    AND (t.question_revision_id IS NULL OR t.question_revision_id=s.question_revision_id)
                WHERE s.learner_id=UUID_TO_BIN(:learner) AND s.active_cycle_id IS NOT NULL AND %s
            ), candidates AS (
                SELECT s.*,c.strategy_id,c.created_at,
                    CASE WHEN c.strategy_id='custom/v1' THEN cs.sequence ELSE f.sequence END AS sequence,
                    f.state,e.transition,r.result_cycle_version AS initial_version,r.result_state AS initial_state,
                    cs.stage,cs.schedule_version,cs.reviewed_at,cs.time_zone,cs.completed,cp.paused,cv.definition,h.after_json,
                    CASE WHEN c.strategy_id='custom/v1' THEN cs.due_at ELSE
                        CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(f.state,'$.dueAt')),'T',' '),'Z','') AS DATETIME(6)) END AS due_at,
                    EXISTS(SELECT 1 FROM quiz_revision_items i WHERE i.quiz_revision_id=s.quiz_revision_id AND i.question_revision_id=s.question_revision_id) AS member,
                    (cs.cycle_id IS NOT NULL AND cp.id IS NOT NULL AND cv.plan_id IS NOT NULL AND cs.schedule_version>=cs.sequence) AS custom_valid
                FROM eligible s LEFT JOIN learning_schedule_cycle c ON c.id=s.active_cycle_id
                LEFT JOIN learning_fsrs_state f ON f.cycle_id=c.id
                LEFT JOIN learning_review_event e ON e.cycle_id=f.cycle_id AND e.sequence=f.sequence
                LEFT JOIN learning_schedule_receipt r ON r.learner_id=c.learner_id AND r.command_id=c.create_command_id
                LEFT JOIN learning_custom_state cs ON cs.cycle_id=c.id AND cs.learner_id=s.learner_id
                LEFT JOIN learning_plan cp ON cp.id=cs.plan_id AND cp.learner_id=s.learner_id
                LEFT JOIN learning_plan_version cv ON cv.plan_id=cs.plan_id AND cv.version=cs.plan_version
                LEFT JOIN learning_custom_change h ON h.cycle_id=cs.cycle_id AND h.version=cs.schedule_version
            ), checked AS (
                SELECT candidates.*,(due_at IS NULL OR member=0 OR CASE WHEN strategy_id='custom/v1' THEN
                    :custom=FALSE OR NOT custom_valid OR NOT (JSON_EXTRACT(definition,'$.version') <=> CAST('1' AS JSON))
                ELSE NOT (strategy_id <=> 'fsrs/v1') OR state IS NULL
                    OR NOT (JSON_EXTRACT(state,'$.version') <=> CAST('1' AS JSON))
                    OR NOT (JSON_EXTRACT(state,'$.manifest') <=> CAST(:manifest AS JSON)) END) AS invalid
                FROM candidates
            )
            SELECT * FROM checked WHERE invalid OR (due_at %s :now
                AND (strategy_id='fsrs/v1' OR (completed=FALSE AND paused=FALSE)) %s)
            ORDER BY invalid DESC,due_at,quiz_id,quiz_revision_id,question_revision_id LIMIT :take
            """.formatted(authorized,window==Window.DUE?"<=":">",position);
        return safe(() -> jdbc.query(query,p,(r,n)->item(r,learner,custom)));
    }
    private Item item(ResultSet r,UUID learner,boolean custom) throws SQLException {
        Source source=new Source(1,uuid(r,"quiz_id"),uuid(r,"quiz_revision_id"),r.getInt("revision_schema_version"));
        UUID question=uuid(r,"question_revision_id");
        String strategy=r.getString("strategy_id");
        require("fsrs/v1".equals(strategy) || "custom/v1".equals(strategy) && custom,UNSUPPORTED_VERSION);
        // Let the existing decoders distinguish unsupported evidence from corruption. Do not
        // turn an unknown FSRS manifest/custom definition into a generic integrity error.
        LearningSchedule.Snapshot fsrs="fsrs/v1".equals(strategy)?LearningScheduleEvidence.read(r,new Key(learner,source,question)):null;
        LearningPlan.Definition definition=fsrs==null?format.decode(r.getString("definition"),LearningPlan.Definition.class):null;
        require(!r.getBoolean("invalid") && r.getObject("due_at",LocalDateTime.class)!=null,CORRUPT);
        Instant due=r.getObject("due_at",LocalDateTime.class).toInstant(ZoneOffset.UTC);
        LearningSchedule.Expected expected;
        if("fsrs/v1".equals(strategy)) {
            require(fsrs.state().dueAt().equals(due),CORRUPT); expected=fsrs.expected();
        } else {
            var reviewed=r.getObject("reviewed_at",LocalDateTime.class);
            var state=new CustomSchedule.State(r.getLong("schedule_version"),r.getLong("sequence"),r.getInt("stage"),
                    reviewed==null?null:reviewed.toInstant(ZoneOffset.UTC),due,
                    r.getString("time_zone")==null?definition.timeZone():r.getString("time_zone"),r.getBoolean("completed"));
            if(state.version()==0) require(state.sequence()==0 && state.stage()==0 && !state.completed()
                    && due.equals(r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),CORRUPT);
            else require(state.equals(format.decode(r.getString("after_json"),CustomSchedule.State.class)),CORRUPT);
            expected=new LearningSchedule.Expected(uuid(r,"active_cycle_id"),r.getLong("cycle_version"),state.sequence());
        }
        return new Item(source,question,expected,"fsrs/v1".equals(strategy)?LearningPlan.Strategy.FSRS:LearningPlan.Strategy.CUSTOM,due);
    }
    @Override public Optional<Receipt> receipt(UUID learner,UUID command) {
        return safe(() -> jdbc.query("SELECT input_json,result_json FROM learning_set_command WHERE learner_id=UUID_TO_BIN(:learner) AND command_id=UUID_TO_BIN(:command)",
                owner(learner).addValue("command",command.toString()),(r,n)->new Receipt(r.getString(1),format.decode(r.getString(2),Acknowledgement.class))).stream().findFirst());
    }
    @Override public void record(UUID learner,UUID command,String input,Acknowledgement ack,Instant at) {
        safe(() -> jdbc.update("INSERT INTO learning_set_command VALUES(UUID_TO_BIN(:learner),UUID_TO_BIN(:command),:input,:result,:at)",
                owner(learner).addValue("command",command.toString()).addValue("input",input).addValue("result",format.encode(ack)).addValue("at",time(at))));
    }
    private static View view(ResultSet r) throws SQLException {
        return new View(uuid(r,"id"),r.getString("name"),r.getLong("version"),r.getBoolean("deleted"),
                r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
    private static UUID optionalUuid(ResultSet r,String column) throws SQLException { return r.getBytes(column)==null?null:uuid(r,column); }
    private static MapSqlParameterSource owner(UUID learner) { return new MapSqlParameterSource("learner",learner.toString()); }
    private static MapSqlParameterSource scope(UUID learner,Scope scope) {
        var p=owner(learner);
        return scope.setId()==null?p.addValue("quiz",scope.source().quizId().toString()).addValue("revision",scope.source().quizRevisionId().toString())
                .addValue("schema",scope.source().revisionSchemaVersion()):p.addValue("root",scope.setId().toString());
    }
    private static MapSqlParameterSource member(MapSqlParameterSource p,Member member) {
        return p.addValue("key",member.key()).addValue("kind",member.kind().name()).addValue("child",member.childSetId()==null?null:member.childSetId().toString())
                .addValue("quiz",member.source()==null?null:member.source().quizId().toString()).addValue("revision",member.source()==null?null:member.source().quizRevisionId().toString())
                .addValue("schema",member.source()==null?null:member.source().revisionSchemaVersion()).addValue("question",member.questionRevisionId()==null?null:member.questionRevisionId().toString());
    }
    private static LocalDateTime time(Instant at) { return LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
    private static <T> T safe(Supplier<T> work) { try { return work.get(); } catch(DataAccessException failure) { throw SafeDataAccess.sanitize(failure); } }
}
