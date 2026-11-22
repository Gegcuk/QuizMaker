package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.*;
import uk.gegc.quizmaker.features.learning.config.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.repository.*;
import uk.gegc.quizmaker.features.learning.infra.repository.*;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.LearningPlanTest.definition;
import static uk.gegc.quizmaker.features.learning.LearningScheduleMySqlIntegrationTest.login;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Real plan/cycle/receipt writes and current author checks. Publication facts are committed
 * source fixtures, not proof of #700 publication. Custom review/finish remains #840. */
@Tag("db-serial")
@Timeout(180)
class LearningPlanMySqlIntegrationTest {
    static final String SCHEMA="learning_plans_"+UUID.randomUUID().toString().replace("-","");
    static final UUID ANNA=UUID.randomUUID(),BOB=UUID.randomUUID();
    static final Instant TIME=Instant.parse("2026-11-22T09:00:00Z");
    static JdbcTemplate admin,sql;
    static LearningScheduleMySqlIntegrationTest.ObservedSource source;
    static DataSourceTransactionManager manager;
    static QuizRevision revision;
    static LearningSession.Source ref;
    static boolean created;
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    final AtomicReference<Instant> instant=new AtomicReference<>(TIME);
    final Clock clock=new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant.get(); }
    };
    JdbcLearningPlanRepository repository;
    LearningPlanService service;
    LearningScheduleRepository schedules;
    LearningScheduleService fsrs;
    LearningSessionService sessions;
    AuthenticatedUserLookup users;
    LearningSessionSourceVerifier authority;
    LearningCommandBudget budget;
    LearningDueSelectionConfig limits;
    final Set<UUID> completedSessions=ConcurrentHashMap.newKeySet();
    @BeforeAll static void database() {
        String port=System.getenv().getOrDefault("TEST_MYSQL_PORT","3306");
        if(!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base="jdbc:mysql://127.0.0.1:"+port+"/", options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user=System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME","root"),password=System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD","root");
        admin=new JdbcTemplate(new DriverManagerDataSource(base+options,user,password));
        admin.execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); created=true;
        source=new LearningScheduleMySqlIntegrationTest.ObservedSource(new DriverManagerDataSource(base+SCHEMA+options,user,password));
        sql=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source); Flyway.configure().dataSource(source).load().migrate();
        for(var entry:Map.of(ANNA,"anna",BOB,"bob").entrySet()) sql.update("""
            INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version)
            VALUES(UUID_TO_BIN(?),?,?,'not-a-login',1,0,0,'2026-01-01','2026-01-01',0)
            """,entry.getKey().toString(),entry.getValue(),entry.getValue()+"@example.invalid");
        sql.update("INSERT INTO roles(role_name,is_default) VALUES ('plan-author',FALSE)");
        sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name='plan-author' AND p.permission_name='QUIZ_UPDATE'");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name='plan-author'",ANNA.toString());
        UUID category=UUID.randomUUID(),quiz=UUID.randomUUID();
        sql.update("INSERT INTO categories(category_id,category_name) VALUES(UUID_TO_BIN(?),'Plan source')",category.toString());
        sql.update("INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'Owned source','PRIVATE','EASY','PUBLISHED',1,0,0)",quiz.toString(),ANNA.toString(),category.toString());
        var items=new ArrayList<QuizRevision.Item>();
        for(int i=0;i<4;i++) items.add(new QuizRevision.Item(UUID.randomUUID(),i,new QuestionRevision(UUID.randomUUID(),UUID.randomUUID(),1,QuestionType.TRUE_FALSE,Difficulty.EASY,"Immutable question "+i,"{\"answer\":true}",null,null,null,null,TIME,List.of())));
        revision=new QuizRevision(UUID.randomUUID(),quiz,ANNA,1,"Plan source",null,TIME,items);
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(sql)).insert(revision);
            for(var item:items) {
                var binding=new QuestionContractBinding(item.question().id(),new ContractVersion("question.true-false.response",1),new ContractVersion("question.true-false.binary",1));
                sql.update("INSERT INTO question_revision_contract_bindings VALUES(UUID_TO_BIN(?),'question.true-false.response',1,'question.true-false.binary',1,?)",item.question().id().toString(),binding.fingerprint());
            }
        });
        sql.update("INSERT INTO quiz_publications VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?)",revision.id().toString(),quiz.toString(),ANNA.toString(),"a".repeat(64),java.sql.Timestamp.from(TIME));
        ref=new LearningSession.Source(1,quiz,revision.id(),1);
    }
    @AfterAll static void drop() { if(created) admin.execute("DROP DATABASE "+SCHEMA); }
    @BeforeEach void setup() {
        source.reset(); login("anna");
        users=new AuthenticatedUserLookup() {
            public Optional<UUID> findActiveUserId(String name) { throw new AssertionError("locking lookup required"); }
            public Optional<UUID> lockActiveUserId(String name) { return sql.query("SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE FOR SHARE",(r,n)->UUID.fromString(r.getString(1)),name).stream().findFirst(); }
        };
        authority=new JdbcPublishedLearningSourceVerifier(sql); schedules=new JdbcLearningScheduleRepository(new NamedParameterJdbcTemplate(sql));
        sessions=new LearningSessionServiceImpl(new JdbcLearningSessionRepository(new NamedParameterJdbcTemplate(sql)),(a,b)->{},users,List.of(authority),clock,metrics,manager);
        fsrs=new LearningScheduleServiceImpl(schedules,users,List.of(authority),List.of(s->completedSessions.contains(s.id())),sessions,new FsrsSchedulingStrategyImpl(),clock,metrics,manager);
        var rate=new LearningCommandRateConfig(); budget=new JdbcLearningCommandBudget(sql,clock,rate);
        limits=new LearningDueSelectionConfig(); limits.setMaxBatchSize(100);
        repository=new JdbcLearningPlanRepository(new NamedParameterJdbcTemplate(sql)); service=service(repository);
    }
    @AfterEach void clear() {
        source.reset(); SecurityContextHolder.clearContext();
        for(String t:List.of("learning_plan_command","learning_plan_question_override","learning_plan_quiz_default","learning_custom_state","learning_cycle_session","learning_review_event","learning_fsrs_state")) sql.update("DELETE FROM "+t);
        sql.update("UPDATE learning_schedule_slot SET active_cycle_id=NULL,cycle_version=0");
        for(String t:List.of("learning_schedule_cycle","learning_schedule_slot","learning_schedule_receipt","learning_form_option","learning_form_item","learning_form","learning_session_create_receipt","learning_session","learning_plan_version","learning_plan","learning_command_budget")) sql.update("DELETE FROM "+t);
        sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE");
        sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?),is_deleted=FALSE",ANNA.toString());
        assertThat(count("spaced_repetition_entry")).isZero(); assertThat(count("repetition_review_log")).isZero();
        assertThat(count("attempt_automatic_result")).isZero(); assertThat(count("attempts")).isZero(); metrics.close();
    }
    LearningPlanService service(LearningPlanRepository store) { return new LearningPlanServiceImpl(store,schedules,fsrs,new FsrsSchedulingStrategyImpl(),users,List.of(authority),budget,limits,clock,metrics,manager); }
    static UUID question(int i) { return revision.items().get(i).question().id(); }
    static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    static TransactionTemplate tx(int isolation) { var t=new TransactionTemplate(manager); t.setIsolationLevel(isolation); return t; }
    View plan(String name) { return service.create(new LearningPlanService.Save(UUID.randomUUID(),0,definition(name,1,3,7))).plan(); }
    void defaults(View plan) { service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,null,0,plan.planId(),null)); }
    LearningPlanService.Change enroll(int index) { return new LearningPlanService.Change(UUID.randomUUID(),ref,question(index),Action.ENROLL,Strategy.CUSTOM,null,false,null,null); }
    Cycle change(Cycle old,Strategy target,Action action) {
        Binding binding=null;
        if(target==Strategy.CUSTOM) {
            binding=service.binding(ref,old.questionRevisionId());
            if(binding.planId()==null) { var defaults=service.binding(ref,null); binding=new Binding(defaults.version(),defaults.planId(),service.read(defaults.planId()).planVersion()); }
        }
        return service.change(new LearningPlanService.Change(UUID.randomUUID(),ref,old.questionRevisionId(),action,target,old.expected(),true,
                binding==null?null:binding.planId(),binding==null?null:binding.planVersion())).cycle();
    }
    static void rejected(Runnable command,LearningSessionException.Reason reason) { assertThatThrownBy(command::run).isInstanceOfSatisfying(LearningSessionException.class,e->assertThat(e.reason()).isEqualTo(reason)); }

    @Test void defaultEditOverrideAndNewEnrollmentCaptureDifferentImmutableVersions() {
        var plan=plan("Anatomy"); defaults(plan);
        service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,question(1),0,plan.planId(),1L));
        var first=service.change(enroll(0)).cycle();
        var edit=new LearningPlanService.Save(UUID.randomUUID(),1,definition("Anatomy revised",2,4,8));
        var ack=service.edit(plan.planId(),edit); assertThat(ack.plan().planVersion()).isEqualTo(2);
        assertThat(service.change(enroll(0)).cycle()).isEqualTo(first);
        assertThat(service.change(enroll(1)).cycle().planVersion()).isEqualTo(1);
        assertThat(service.change(enroll(2)).cycle().planVersion()).isEqualTo(2);
        assertThat(service.cycle(ref,question(0)).dueAt()).isEqualTo(first.dueAt());
        assertThat(service.history(plan.planId(),10,0).items()).extracting(Version::version).containsExactly(1L,2L);
        assertThat(service(repository).edit(plan.planId(),edit)).isEqualTo(ack);
        rejected(()->service.edit(plan.planId(),new LearningPlanService.Save(edit.commandId(),1,definition("Changed",3))),CONFLICT);
        assertThat(count("learning_plan_version")).isEqualTo(2);
    }
    @Test void pauseForAMonthPreservesOneOverdueItemAndPagingHasNoDuplicates() {
        var p=plan("Paused"); defaults(p); var first=service.change(enroll(0)).cycle(); service.change(enroll(1));
        var page=service.cycles(ref,Window.DUE,1,null); assertThat(page.items()).hasSize(1); assertThat(page.next()).isNotNull();
        var next=service.cycles(ref,Window.DUE,1,page.next()); assertThat(next.items()).hasSize(1);
        assertThat(next.items().get(0).questionRevisionId()).isNotEqualTo(page.items().get(0).questionRevisionId());
        var paused=service.pause(p.planId(),new LearningPlanService.Pause(UUID.randomUUID(),1,true));
        instant.set(TIME.plusSeconds(31L*86400));
        assertThat(service.cycles(ref,Window.DUE,10,null).items()).isEmpty();
        assertThat(service.cycles(ref,Window.ALL,10,null).items()).hasSize(2).allMatch(Cycle::paused);
        service.pause(p.planId(),new LearningPlanService.Pause(UUID.randomUUID(),paused.plan().version(),false));
        for(int i=0;i<3;i++) assertThat(service.cycles(ref,Window.DUE,10,null).items()).hasSize(2).allMatch(c->c.sequence()==0 && c.dueAt().equals(TIME));
        assertThat(service.cycle(ref,question(0)).cycleId()).isEqualTo(first.cycleId());
        assertThat(service.history(p.planId(),10,0).items()).hasSize(1); assertThat(count("learning_review_event")).isZero();
    }
    @Test void switchUsesOneSharedAuthorityPreservesFsrsReceiptAndOldCommandsCannotAdvanceReplacement() {
        var p=plan("Switch"); defaults(p);
        var original=fsrs.execute(new LearningScheduleService.Enroll(1,UUID.randomUUID(),ref,question(0),FsrsManifest.approved())).snapshot();
        var current=service.cycle(ref,question(0)); var request=new LearningPlanService.Change(UUID.randomUUID(),ref,question(0),Action.SWITCH,Strategy.CUSTOM,current.expected(),true,p.planId(),1L);
        var ack=service.change(request); assertThat(ack.cycle().cycleVersion()).isEqualTo(2);
        var restored=change(ack.cycle(),Strategy.FSRS,Action.SWITCH); assertThat(restored.cycleVersion()).isEqualTo(3);
        assertThat(fsrs.read(ref,question(0)).expected()).isEqualTo(restored.expected());
        assertThat(service(repository).change(request)).isEqualTo(ack);
        assertThatThrownBy(()->fsrs.execute(new LearningScheduleService.Rate(1,UUID.randomUUID(),ref,question(0),original.expected(),LearningSchedule.Rating.GOOD))).isInstanceOf(LearningScheduleException.class);
        assertThat(fsrs.read(ref,question(0)).state().sequence()).isZero();
        assertThat(count("learning_schedule_slot")).isOne(); assertThat(count("learning_schedule_cycle")).isEqualTo(3); assertThat(count("learning_review_event")).isZero();
    }
    @Test void capturedSessionBlocksReplacementUntilCanonicalRelease() {
        var p=plan("Captured"); defaults(p);
        var snapshot=fsrs.execute(new LearningScheduleService.Enroll(1,UUID.randomUUID(),ref,question(0),FsrsManifest.approved())).snapshot();
        var session=sessions.create(new LearningSessionService.Create(1,UUID.randomUUID(),ref,FsrsManifest.approved())).session();
        var forms=new LearningFormServiceImpl(sessions,new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql)),
                List.of((s,items)->items.stream().map(i->new LearningFormContent.PresentedItem(i,"Immutable question",new ParticipantContent.TrueFalse())).toList()),clock,metrics,manager);
        var item=revision.items().get(0);
        forms.materialize(new LearningFormService.Materialize(1,session.id(),List.of(new LearningForm.Item(item.id(),item.question().id(),1,new ContractVersion("question.true-false.response",1),List.of()))));
        fsrs.execute(new LearningScheduleService.Capture(1,UUID.randomUUID(),ref,question(0),snapshot.expected(),session.id()));
        var request=new LearningPlanService.Change(UUID.randomUUID(),ref,question(0),Action.SWITCH,Strategy.CUSTOM,snapshot.expected(),true,p.planId(),1L);
        rejected(()->service.change(request),CONFLICT); assertThat(count("learning_custom_state")).isZero();
        // Named completion-contract fixture, not proof of #650's response or custom review.
        completedSessions.add(session.id());
        fsrs.execute(new LearningScheduleService.Release(1,UUID.randomUUID(),ref,question(0),snapshot.expected(),session.id()));
        assertThat(service.change(request).cycle().strategy()).isEqualTo(Strategy.CUSTOM);
    }
    @ParameterizedTest @ValueSource(strings={"INSERT INTO learning_custom_state","INSERT INTO learning_plan_command"})
    void reachedCycleFailuresRollbackSlotStateReceiptAndCreditThenSameCommandSucceeds(String checkpoint) {
        var p=plan("Rollback"); defaults(p); var request=enroll(0);
        var before=sql.queryForList("SELECT credit_units,accounted_us FROM learning_command_budget");
        source.fault.set(checkpoint); rejected(()->service.change(request),PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne();
        assertThat(count("learning_schedule_slot")).isZero(); assertThat(count("learning_custom_state")).isZero();
        assertThat(count("learning_plan_command")).isEqualTo(2);
        assertThat(sql.queryForList("SELECT credit_units,accounted_us FROM learning_command_budget")).isEqualTo(before);
        assertThat(service.change(request).cycle().cycleVersion()).isOne();
    }
    @Test void lostCommitAcknowledgementReconcilesAfterServiceReconstruction() {
        var command=new LearningPlanService.Save(UUID.randomUUID(),0,definition("Lost response",1,3));
        source.loseCommit.set(true); rejected(()->service.create(command),PERSISTENCE_UNAVAILABLE);
        assertThat(source.lostCommits.get()).isOne(); assertThat(count("learning_plan")).isOne();
        var first=service(repository).create(command); assertThat(service.create(command)).isEqualTo(first);
        assertThat(count("learning_plan_command")).isOne(); assertThat(count("learning_plan_version")).isOne();
    }
    @Test void currentIdentityOwnershipAndPublicationAuthorityApplyToReadsWritesAndReplay() {
        var p=plan("Private"); defaults(p); var command=enroll(0); var ack=service.change(command);
        login("bob"); rejected(()->service.read(p.planId()),UNAVAILABLE);
        rejected(()->service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,null,0,p.planId(),null)),SOURCE_UNAVAILABLE);
        assertThat(service.list(10,null).items()).isEmpty();
        login("anna"); sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?)",BOB.toString());
        rejected(()->service.change(command),SOURCE_UNAVAILABLE);
        sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?)",ANNA.toString()); assertThat(service.change(command)).isEqualTo(ack);
        sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name='plan-author'");
        rejected(()->service.cycle(ref,question(0)),SOURCE_UNAVAILABLE);
        sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name='plan-author' AND p.permission_name='QUIZ_UPDATE'");
        sql.update("UPDATE users SET is_active=FALSE WHERE username='anna'"); rejected(()->service.read(p.planId()),UNAVAILABLE);
        SecurityContextHolder.clearContext(); rejected(()->service.read(p.planId()),UNAUTHENTICATED);
        assertThat(count("learning_custom_state")).isOne();
    }
    @Test void incompatibleCallerRejectsBeforeWorkAndOuterRcRollbackOwnsAllWrites() {
        source.reset();
        for(int isolation:List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE,TransactionDefinition.ISOLATION_DEFAULT))
            tx(isolation).executeWithoutResult(s->rejected(()->plan("Denied"),UNSUPPORTED_TRANSACTION));
        var readOnly=tx(TransactionDefinition.ISOLATION_READ_COMMITTED); readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(s->rejected(()->plan("Read only"),UNSUPPORTED_TRANSACTION));
        assertThat(source.statements.get()).isEmpty();
        assertThat(count("learning_plan")).isZero();
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(s->{ plan("Provisional"); assertThat(count("learning_plan")).isOne(); s.setRollbackOnly(); });
        assertThat(count("learning_plan")).isZero(); assertThat(count("learning_plan_command")).isZero();
    }
    @Test void anotherUsersPlanIsDeniedThroughAnOtherwiseAuthorizedBindingPath() {
        login("bob"); var foreign=plan("Bob private"); login("anna");
        rejected(()->service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,null,0,foreign.planId(),null)),UNAVAILABLE);
        assertThat(count("learning_plan_quiz_default")).isZero();
        var own=plan("Anna private"); defaults(own); assertThat(service.binding(ref,null).planId()).isEqualTo(own.planId());
        rejected(()->service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,null,0,own.planId(),null)),CONFLICT);
        var clear=new LearningPlanService.Bind(UUID.randomUUID(),ref,null,1,null,null);
        assertThat(service.bind(clear).binding()).isEqualTo(new Binding(2,null,null));
        assertThat(service(repository).bind(clear).binding()).isEqualTo(new Binding(2,null,null));
        rejected(()->service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,null,1,own.planId(),null)),CONFLICT);
    }
    @Test void enrollmentWhilePausedCapturesSettingsWithoutMakingTheItemDue() {
        var p=plan("Already paused"); defaults(p);
        service.pause(p.planId(),new LearningPlanService.Pause(UUID.randomUUID(),1,true));
        var cycle=service.change(enroll(0)).cycle(); assertThat(cycle.paused()).isTrue();
        assertThat(service.cycles(ref,Window.DUE,10,null).items()).isEmpty();
        assertThat(service.cycles(ref,Window.ALL,10,null).items()).containsExactly(cycle);
        service.pause(p.planId(),new LearningPlanService.Pause(UUID.randomUUID(),2,false));
        assertThat(service.cycles(ref,Window.DUE,10,null).items()).hasSize(1).allMatch(c->c.dueAt().equals(cycle.dueAt()) && c.sequence()==0);
    }
    @Test void completedCustomContractFixtureCanRestartButActiveCycleCannot() {
        var p=plan("Finite"); defaults(p); var current=service.change(enroll(0)).cycle();
        rejected(()->change(current,Strategy.CUSTOM,Action.RESTART),CONFLICT);
        // #840 supplies the actual progression/completion writer. This fixture proves only the
        // consuming management transition from its completed snapshot, never a custom review.
        sql.update("UPDATE learning_custom_state SET sequence=3,stage=2,completed=TRUE WHERE cycle_id=UUID_TO_BIN(?)",current.cycleId().toString());
        var finished=service.cycle(ref,question(0)); var restarted=change(finished,Strategy.CUSTOM,Action.RESTART);
        assertThat(restarted.cycleVersion()).isEqualTo(2); assertThat(restarted.completed()).isFalse(); assertThat(restarted.sequence()).isZero();
        assertThat(count("learning_custom_state")).isEqualTo(2);
        assertThat(service.cycles(ref,Window.ALL,10,null).items()).containsExactly(restarted);
    }
    @Test void exhaustedSharedBudgetAllowsExactReplayAndRefillsWithoutPartialWrite() {
        var command=new LearningPlanService.Save(UUID.randomUUID(),0,definition("First",1));
        var original=service.create(command);
        for(int i=1;i<20;i++) plan("Plan "+i);
        assertThatThrownBy(()->plan("Over budget")).isInstanceOf(uk.gegc.quizmaker.shared.exception.RateLimitExceededException.class);
        assertThat(count("learning_plan")).isEqualTo(20); assertThat(service.create(command)).isEqualTo(original);
        instant.set(TIME.plusSeconds(1)); plan("Refilled"); assertThat(count("learning_plan")).isEqualTo(21);
    }
    @Test void reachedEditFailureMakesCaughtOuterRcTransactionRollbackOnly() {
        var p=plan("Original"); source.fault.set("INSERT INTO learning_plan_command");
        assertThatThrownBy(()->tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(s->{
            rejected(()->service.edit(p.planId(),new LearningPlanService.Save(UUID.randomUUID(),1,definition("Never committed",2))),PERSISTENCE_UNAVAILABLE);
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(source.reached.get()).isOne(); assertThat(service.read(p.planId())).isEqualTo(p);
        assertThat(service.history(p.planId(),10,0).items()).hasSize(1);
    }
    @Test void competingSwitchesHaveOneCommittedWinnerAndOneStaleConflict() throws Exception {
        var p=plan("Race"); defaults(p); var original=service.change(enroll(0)).cycle();
        CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1);
        var hooked=new JdbcLearningPlanRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void record(UUID learner,UUID command,String input,Acknowledgement result,Instant now) {
                super.record(learner,command,input,result,now);
                if(result.cycle()!=null) { reached.countDown(); try { if(!release.await(120,TimeUnit.SECONDS)) throw new AssertionError("holder release missing"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
            }
        };
        var first=new LearningPlanService.Change(UUID.randomUUID(),ref,question(0),Action.SWITCH,Strategy.FSRS,original.expected(),true,null,null);
        var second=new LearningPlanService.Change(UUID.randomUUID(),ref,question(0),Action.SWITCH,Strategy.FSRS,original.expected(),true,null,null);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<Acknowledgement> winner=workers.submit(()->asAnna(()->service(hooked).change(first)));
            assertThat(reached.await(120,TimeUnit.SECONDS)).isTrue();
            Future<?> loser=workers.submit(()->asAnna(()->{ rejected(()->service.change(second),CONFLICT); return null; }));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(sql.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class)==0) {
                if(loser.isDone()) { loser.get(); fail("competitor did not reach a database wait"); }
                if(System.nanoTime()>deadline) fail("database wait not reached"); Thread.sleep(10);
            }
            release.countDown(); assertThat(winner.get(120,TimeUnit.SECONDS).cycle().strategy()).isEqualTo(Strategy.FSRS); loser.get(120,TimeUnit.SECONDS);
            assertThat(count("learning_schedule_slot")).isOne(); assertThat(count("learning_schedule_cycle")).isEqualTo(2);
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(120,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void planAndCyclePagesUseBoundedSqlAcrossMultipleParents() {
        var p=plan("One"); defaults(p); service.change(enroll(0)); source.reset();
        assertThat(service.list(10,null).items()).hasSize(1); int reads=source.reads.get().size(); int rows=source.rows.get();
        var second=plan("Two"); var third=plan("Three");
        service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,question(1),0,second.planId(),1L));
        service.bind(new LearningPlanService.Bind(UUID.randomUUID(),ref,question(2),0,third.planId(),1L));
        service.change(enroll(1)); service.change(enroll(2)); source.reset();
        assertThat(service.list(10,null).items()).hasSize(3); assertThat(source.reads.get()).hasSize(reads); assertThat(source.rows.get()).isEqualTo(rows+2);
        source.reset(); assertThat(service.cycles(ref,Window.DUE,2,null).items()).hasSize(2);
        assertThat(source.statements.get().stream().filter(s->s.startsWith("WITH selected")).count()).isOne();
        assertThat(source.rows.get()).isEqualTo(5); // Account + authority + three SQL candidates for a two-item page.
        assertThat(source.statements.get()).noneMatch(s->s.contains("SELECT question") || s.contains("question_revisions.*"));
        System.out.println("PLAN_SQL list selects="+reads+", rows="+rows+" then "+(rows+2)+"; custom page CTE=1, SQL candidates=3, page items=2");
    }
    @Test void confirmationCannotSilentlyAdoptAPlanVersionEditedAfterTheUserChoseIt() {
        var p=plan("Chosen"); defaults(p); var original=service.change(enroll(0)).cycle();
        service.edit(p.planId(),new LearningPlanService.Save(UUID.randomUUID(),1,definition("Version two",2,4)));
        var confirmation=new LearningPlanService.Change(UUID.randomUUID(),ref,question(0),Action.SWITCH,Strategy.CUSTOM,original.expected(),true,p.planId(),2L);
        service.edit(p.planId(),new LearningPlanService.Save(UUID.randomUUID(),2,definition("Version three",3,6)));
        rejected(()->service.change(confirmation),CONFLICT);
        assertThat(service.cycle(ref,question(0))).isEqualTo(original); assertThat(count("learning_schedule_cycle")).isOne();
        assertThat(change(original,Strategy.CUSTOM,Action.SWITCH).planVersion()).isEqualTo(3);
    }
    @Test void invalidRetainedReceiptAndUnknownConfigurationFailClosedWithoutRepairingHistory() {
        var command=new LearningPlanService.Save(UUID.randomUUID(),0,definition("Retained",1,3));
        var ack=service.create(command);
        String receipt=sql.queryForObject("SELECT result_json FROM learning_plan_command",String.class);
        sql.update("UPDATE learning_plan_command SET result_json=JSON_SET(result_json,'$.plan',CAST('null' AS JSON))");
        rejected(()->service.create(command),CORRUPT); assertThat(count("learning_plan")).isOne();
        sql.update("UPDATE learning_plan_command SET result_json=?",receipt); assertThat(service.create(command)).isEqualTo(ack);
        String definition=sql.queryForObject("SELECT definition FROM learning_plan_version",String.class);
        sql.update("UPDATE learning_plan_version SET definition=JSON_SET(definition,'$.version',2)");
        rejected(()->service.read(ack.plan().planId()),UNSUPPORTED_VERSION);
        sql.update("UPDATE learning_plan_version SET definition=?",definition); assertThat(service.read(ack.plan().planId())).isEqualTo(ack.plan());
        assertThat(count("learning_plan_command")).isOne();
    }
    static <T> T asAnna(Supplier<T> work) { login("anna"); try { return work.get(); } finally { SecurityContextHolder.clearContext(); } }
}
