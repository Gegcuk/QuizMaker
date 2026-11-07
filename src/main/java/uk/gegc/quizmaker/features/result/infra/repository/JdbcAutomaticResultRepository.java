package uk.gegc.quizmaker.features.result.infra.repository;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;
import uk.gegc.quizmaker.features.result.domain.repository.AutomaticResultRepository;
import static uk.gegc.quizmaker.features.result.domain.model.AutomaticResultException.*;
import static uk.gegc.quizmaker.features.result.domain.model.AutomaticResultException.Reason.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcAutomaticResultRepository implements AutomaticResultRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAutomaticResultRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Optional<AutomaticResult> find(UUID submissionId) {
        var p=new MapSqlParameterSource("submission",submissionId.toString());
        return jdbc.query("""
                SELECT *,BIN_TO_UUID(id) rid,BIN_TO_UUID(attempt_id) aid,BIN_TO_UUID(form_id) fid,
                    BIN_TO_UUID(policy_snapshot_id) pid,BIN_TO_UUID(quiz_revision_id) qid
                FROM attempt_automatic_result WHERE submission_id=UUID_TO_BIN(:submission) FOR SHARE
                """,p,(r,n)-> {
            require(AutomaticResult.CONTRACT.equals(r.getString("scoring_contract")),UNSUPPORTED);
            UUID id=id(r,"rid"); p.addValue("id",id.toString());
            var items=jdbc.query("""
                    SELECT *,BIN_TO_UUID(form_item_id) iid,BIN_TO_UUID(question_revision_id) qid,BIN_TO_UUID(response_id) aid
                    FROM attempt_automatic_grade WHERE result_id=UUID_TO_BIN(:id) ORDER BY position FOR SHARE
                    """,p,(g,i)->new AutomaticResult.Item(id(g,"iid"),id(g,"qid"),nullableId(g,"aid"),g.getInt("position"),
                    new ContractVersion(g.getString("response_contract_id"),g.getInt("response_contract_version")),
                    new ContractVersion(g.getString("scoring_contract_id"),g.getInt("scoring_contract_version")),g.getInt("earned_points")));
            var events=jdbc.query("""
                    SELECT e.schema_version,e.created_at,t.event_type,t.actor_type,t.reason_code,
                        BIN_TO_UUID(t.attempt_id) aid,BIN_TO_UUID(t.form_id) fid
                    FROM attempt_result_created e JOIN attempt_trace_event t ON t.event_id=e.result_id
                    WHERE e.result_id=UUID_TO_BIN(:id) FOR SHARE
                    """,p,(e,i)->e.getInt("schema_version")==1 && "AUTOMATIC_GRADE_PRODUCED".equals(e.getString("event_type"))
                    && "SYSTEM".equals(e.getString("actor_type")) && "GRADED".equals(e.getString("reason_code"))
                    && id(r,"aid").equals(id(e,"aid")) && id(r,"fid").equals(id(e,"fid"))
                    && r.getObject("created_at",LocalDateTime.class).equals(e.getObject("created_at",LocalDateTime.class)));
            require(events.equals(List.of(true)),CORRUPT);
            return new AutomaticResult(id,r.getInt("schema_version"),submissionId,id(r,"aid"),id(r,"fid"),id(r,"pid"),id(r,"qid"),
                    r.getString("policy_hash"),new AutomaticResult.Score(r.getLong("earned_points"),r.getLong("possible_points"),
                    r.getInt("basis_points"),r.getInt("pass_threshold"),r.getBoolean("passed")),
                    r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),items);
        }).stream().findFirst();
    }
    @Override public void insert(AutomaticResult r) {
        var p=parameters(r).addValue("submission",r.submissionId().toString()).addValue("attempt",r.attemptId().toString())
                .addValue("form",r.formId().toString()).addValue("policy",r.policySnapshotId().toString())
                .addValue("revision",r.quizRevisionId().toString()).addValue("hash",r.policyHash())
                .addValue("earned",r.score().earned()).addValue("possible",r.score().possible()).addValue("bps",r.score().basisPoints())
                .addValue("threshold",r.score().thresholdBasisPoints()).addValue("passed",r.score().passed());
        jdbc.update("""
                INSERT INTO attempt_automatic_result(id,schema_version,scoring_contract,submission_id,attempt_id,form_id,
                    policy_snapshot_id,quiz_revision_id,policy_hash,earned_points,possible_points,basis_points,pass_threshold,passed,created_at)
                VALUES(UUID_TO_BIN(:id),1,'binary-result/v1',UUID_TO_BIN(:submission),UUID_TO_BIN(:attempt),UUID_TO_BIN(:form),
                    UUID_TO_BIN(:policy),UUID_TO_BIN(:revision),:hash,:earned,:possible,:bps,:threshold,:passed,:at)
                """,p);
        var rows=r.items().stream().map(i->new MapSqlParameterSource("result",r.id().toString()).addValue("form",r.formId().toString())
                .addValue("item",i.formItemId().toString()).addValue("question",i.questionRevisionId().toString())
                .addValue("answer",i.responseId()==null?null:i.responseId().toString()).addValue("position",i.position())
                .addValue("responseContract",i.responseContract().id()).addValue("responseVersion",i.responseContract().version())
                .addValue("scoringContract",i.scoringContract().id()).addValue("scoringVersion",i.scoringContract().version())
                .addValue("earned",i.earned())).toList();
        // Bounded JDBC statement batching, not a quiz-size or retry policy.
        for(int start=0;start<rows.size();start+=500) jdbc.batchUpdate("""
                INSERT INTO attempt_automatic_grade(result_id,form_id,form_item_id,question_revision_id,response_id,position,
                    response_contract_id,response_contract_version,scoring_contract_id,scoring_contract_version,earned_points)
                VALUES(UUID_TO_BIN(:result),UUID_TO_BIN(:form),UUID_TO_BIN(:item),UUID_TO_BIN(:question),UUID_TO_BIN(:answer),
                    :position,:responseContract,:responseVersion,:scoringContract,:scoringVersion,:earned)
                """,rows.subList(start,Math.min(start+500,rows.size())).toArray(MapSqlParameterSource[]::new));
    }
    @Override public void enqueue(AutomaticResult r) {
        jdbc.update("INSERT INTO attempt_result_created(result_id,schema_version,created_at) VALUES(UUID_TO_BIN(:id),1,:at)",parameters(r));
    }
    @Override public List<UUID> pending(int limit) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(i.submission_id) id FROM attempt_finalization_intent i
                LEFT JOIN attempt_automatic_result r ON r.submission_id=i.submission_id
                WHERE r.id IS NULL ORDER BY i.created_at,i.submission_id LIMIT :limit
                """,Map.of("limit",limit),(r,n)->id(r,"id"));
    }
    private static MapSqlParameterSource parameters(AutomaticResult r) {
        return new MapSqlParameterSource("id",r.id().toString()).addValue("at",LocalDateTime.ofInstant(r.createdAt(),ZoneOffset.UTC));
    }
    private static UUID id(ResultSet r,String column)throws SQLException { return UUID.fromString(r.getString(column)); }
    private static UUID nullableId(ResultSet r,String column)throws SQLException { var v=r.getString(column); return v==null?null:UUID.fromString(v); }
}
