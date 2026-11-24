package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.ActiveCycle;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
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

/** Real nested membership, current author authority and schedule-reader composition. Internal
 * #649 reviews are not the #667 nested-session/#650 activation proof. Publications are committed fixtures. */
@Tag("db-serial")
@Timeout(180)
class LearningSetMySqlIntegrationTest {
    static final String SCHEMA="learning_sets_"+UUID.randomUUID().toString().replace("-","");
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
    LearningSetService sets;
    JdbcLearningSetRepository setRepository;
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
            public Optional<UUID> findActiveUserId(String name) {
                return sql.query("SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE",
                        (r,n)->UUID.fromString(r.getString(1)),name).stream().findFirst();
            }
            public Optional<UUID> lockActiveUserId(String name) { return sql.query("SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE FOR SHARE",(r,n)->UUID.fromString(r.getString(1)),name).stream().findFirst(); }
        };
        authority=new JdbcPublishedLearningSourceVerifier(sql); schedules=new JdbcLearningScheduleRepository(new NamedParameterJdbcTemplate(sql));
        sessions=new LearningSessionServiceImpl(new JdbcLearningSessionRepository(new NamedParameterJdbcTemplate(sql)),org.mockito.Mockito.mock(uk.gegc.quizmaker.features.learning.domain.repository.LearningCommandBudget.class),users,List.of(authority),clock,metrics,manager);
        fsrs=new LearningScheduleServiceImpl(schedules,users,List.of(authority),List.of(s->completedSessions.contains(s.id())),sessions,new FsrsSchedulingStrategyImpl(),clock,metrics,manager);
        var rate=new LearningCommandRateConfig(); budget=new JdbcLearningCommandBudget(sql,clock,rate);
        limits=new LearningDueSelectionConfig(); limits.setMaxBatchSize(100);
        repository=new JdbcLearningPlanRepository(new NamedParameterJdbcTemplate(sql)); service=service(repository);
        setRepository=new JdbcLearningSetRepository(new NamedParameterJdbcTemplate(sql)); sets=setService(setRepository);
    }
    @AfterEach void clear() {
        source.reset(); SecurityContextHolder.clearContext();
        for(String t:List.of("learning_set_command","learning_set_member","learning_set","learning_set_graph")) sql.update("DELETE FROM "+t);
        for(String t:List.of("learning_custom_change","learning_custom_stage")) sql.update("DELETE FROM "+t);
        for(String t:List.of("learning_prepared_publication","learning_plan_command","learning_plan_question_override","learning_plan_quiz_default","learning_custom_state","learning_cycle_session","learning_review_event","learning_fsrs_state")) sql.update("DELETE FROM "+t);
        sql.update("UPDATE learning_schedule_slot SET active_cycle_id=NULL,cycle_version=0");
        for(String t:List.of("learning_schedule_cycle","learning_schedule_slot","learning_schedule_receipt","learning_form_option","learning_form_item","learning_form","learning_session_create_receipt","learning_session","learning_plan_version","learning_plan","learning_command_budget")) sql.update("DELETE FROM "+t);
        sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE");
        sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?),is_deleted=FALSE",ANNA.toString());
        assertThat(count("spaced_repetition_entry")).isZero(); assertThat(count("repetition_review_log")).isZero();
        assertThat(count("attempt_automatic_result")).isZero(); assertThat(count("attempts")).isZero(); metrics.close();
    }
    LearningPlanService service(LearningPlanRepository store) { return new LearningPlanServiceImpl(store,schedules,fsrs,new FsrsSchedulingStrategyImpl(),users,List.of(authority),budget,limits,clock,metrics,manager,this::preparedBindings,new JdbcLearningStudyRepository(sql),false); }
    // This suite starts with committed bindings. The #840 HTTP suite proves real first preparation.
    List<QuestionContractBinding> preparedBindings(UUID quiz,UUID requestedRevision) {
        assertThat(quiz).isEqualTo(ref.quizId()); assertThat(requestedRevision).isEqualTo(ref.quizRevisionId());
        assertThat(count("question_revision_contract_bindings")).isEqualTo(revision.items().size());
        return revision.items().stream().map(item -> new QuestionContractBinding(item.question().id(),
                new ContractVersion("question.true-false.response",1),new ContractVersion("question.true-false.binary",1))).toList();
    }
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

    LearningSetService setService(LearningSetRepository store) {
        return new LearningSetServiceImpl(store,users,List.of(authority),budget,limits,clock,metrics,manager,true,true);
    }
    LearningSet.View root(String name) { return sets.create(new LearningSetService.Create(UUID.randomUUID(),name)).set(); }
    LearningSet.View add(LearningSet.View set,LearningSet.Member member) {
        return sets.change(set.setId(),new LearningSetService.Change(UUID.randomUUID(),set.version(),member,true)).set();
    }
    LearningSet.Member child(LearningSet.View set) { return new LearningSet.Member(LearningSet.Kind.SET,set.setId(),null,null); }
    LearningSet.Member item(int index) { return new LearningSet.Member(LearningSet.Kind.QUESTION,null,ref,question(index)); }
    LearningSet.Member whole() { return new LearningSet.Member(LearningSet.Kind.QUIZ,null,ref,null); }
    LearningSet.Scope scope(LearningSet.View set) { return new LearningSet.Scope(set.setId(),null); }
    LearningSet.ItemPage due(LearningSet.View set) { return sets.items(scope(set),LearningSet.Window.DUE,100,null); }
    LearningSchedule.Snapshot fsrs(int index) {
        return fsrs.execute(new LearningScheduleService.Enroll(1,UUID.randomUUID(),ref,question(index),FsrsManifest.approved())).snapshot();
    }
    Map<String,List<Map<String,Object>>> schedules() {
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String t:List.of("learning_schedule_slot","learning_schedule_cycle","learning_fsrs_state","learning_schedule_receipt","learning_review_event","learning_custom_state","learning_command_budget")) {
            var rows=sql.queryForList("SELECT * FROM "+t+" ORDER BY 1,2");
            rows.forEach(row->row.replaceAll((column,value)->value instanceof byte[] bytes?HexFormat.of().formatHex(bytes):value)); result.put(t,rows);
        }
        return result;
    }

    @Test void parentChildAndQuizUseOneCycleAcrossDiamondPathsAndRealInternalReview() {
        var initial=fsrs(0); var parent=root("Biology"); var a=add(root("Anatomy"),item(0));
        var b=add(root("Shared branch"),child(a)); parent=add(add(add(parent,child(a)),child(b)),whole());
        var before=schedules();
        for(var entry:List.of(scope(a),scope(parent),new LearningSet.Scope(null,ref))) {
            var page=sets.items(entry,LearningSet.Window.DUE,10,null);
            assertThat(page.items()).hasSize(1); assertThat(page.items().get(0).expected()).isEqualTo(initial.expected());
            assertThat(page.items().get(0).questionRevisionId()).isEqualTo(question(0)); assertThat(page.next()).isNull();
        }
        assertThat(schedules()).isEqualTo(before);
        // Real #649 internal writer: proves reader composition, not a nested session start or #650 HTTP response.
        var request=new LearningScheduleService.Rate(1,UUID.randomUUID(),ref,question(0),initial.expected(),LearningSchedule.Rating.GOOD);
        var advanced=fsrs.execute(request).snapshot(); assertThat(fsrs.execute(request).snapshot()).isEqualTo(advanced);
        assertThat(advanced.expected().cycleId()).isEqualTo(initial.expected().cycleId());
        assertThat(due(parent).items()).isEmpty(); assertThat(due(a).items()).isEmpty();
        var upcoming=sets.items(scope(parent),LearningSet.Window.UPCOMING,10,null);
        assertThat(upcoming.items()).hasSize(1); assertThat(upcoming.items().get(0).expected()).isEqualTo(advanced.expected());
        assertThat(upcoming.items().get(0).dueAt()).isEqualTo(advanced.state().dueAt());
        assertThat(count("learning_review_event")).isOne(); assertThat(count("learning_schedule_slot")).isOne();
    }
    @Test void equalDueTimesPageWithoutDuplicatesAndReadsPreserveAllStateAndCredit() {
        var root=add(root("Paged"),whole()); for(int i=0;i<4;i++) fsrs(i);
        var before=schedules(); var questions=new ArrayList<UUID>(); String after=null;
        do {
            var page=sets.items(scope(root),LearningSet.Window.DUE,1,after);
            assertThat(page.items()).hasSize(1); questions.add(page.items().get(0).questionRevisionId()); after=page.next();
        } while(after!=null);
        assertThat(questions).containsExactlyInAnyOrderElementsOf(revision.items().stream().map(i->i.question().id()).toList());
        assertThat(questions).doesNotHaveDuplicates(); assertThat(schedules()).isEqualTo(before);
        assertThat(sets.items(scope(root),LearningSet.Window.UPCOMING,2,null).items()).isEmpty();
        assertThat(sets.members(root.setId(),1,null).items()).containsExactly(whole());
    }
    record Published(LearningSession.Source source,UUID question) { }
    Published copiedPublication() {
        UUID category=UUID.randomUUID(),quiz=UUID.randomUUID();
        sql.update("INSERT INTO categories(category_id,category_name) VALUES(UUID_TO_BIN(?),?)",category.toString(),"Copy "+category);
        sql.update("INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,'PRIVATE','EASY','PUBLISHED',1,0,0)",quiz.toString(),ANNA.toString(),category.toString(),"Copied source "+quiz);
        var question=new QuestionRevision(UUID.randomUUID(),UUID.randomUUID(),1,QuestionType.TRUE_FALSE,Difficulty.EASY,"Immutable question 0","{\"answer\":true}",null,null,null,null,TIME,List.of());
        var copy=new QuizRevision(UUID.randomUUID(),quiz,ANNA,1,"Copied source",null,TIME,List.of(new QuizRevision.Item(UUID.randomUUID(),0,question)));
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(sql)).insert(copy));
        sql.update("INSERT INTO quiz_publications VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?)",copy.id().toString(),quiz.toString(),ANNA.toString(),"b".repeat(64),java.sql.Timestamp.from(TIME));
        var source=new LearningSession.Source(1,quiz,copy.id(),1);
        fsrs.execute(new LearningScheduleService.Enroll(1,UUID.randomUUID(),source,question.id(),FsrsManifest.approved()));
        return new Published(source,question.id());
    }
    @Test void multiplePublicationParentsUseFiveReadsBoundedMetadataAndIndependentQuizLocalCycles() {
        var root=add(root("Many parents"),whole()); fsrs(0);
        source.reset(); var single=sets.items(scope(root),LearningSet.Window.DUE,1,null);
        long oneReads=source.statements.get().stream().filter(LearningSetMySqlIntegrationTest::readStatement).count();
        assertThat(single.items()).hasSize(1); assertThat(source.rows.get()).isEqualTo(5);
        var publications=new ArrayList<Published>();
        for(int i=0;i<3;i++) {
            var copy=copiedPublication(); publications.add(copy);
            var member=new LearningSet.Member(LearningSet.Kind.QUIZ,null,copy.source(),null);
            var branch=add(root("Branch "+i),member); root=add(add(root,child(branch)),member);
        }
        source.reset(); var page=sets.items(scope(root),LearningSet.Window.DUE,1,null);
        var queries=List.copyOf(source.statements.get());
        assertThat(queries.stream().filter(LearningSetMySqlIntegrationTest::readStatement).count()).isEqualTo(oneReads).isEqualTo(5);
        assertThat(source.rows.get()).isEqualTo(9); // identity, graph, set, four distinct source grants, two lookahead items
        assertThat(page.items()).hasSize(1); assertThat(page.next()).isNotNull();
        assertThat(queries).allSatisfy(q->assertThat(q.toLowerCase(Locale.ROOT)).doesNotContain("question_revisions","document","content_json"));
        var all=due(root).items(); assertThat(all).hasSize(4);
        assertThat(all).extracting(i->i.expected().cycleId()).doesNotHaveDuplicates();
        assertThat(all).extracting(i->i.source().quizId()).doesNotHaveDuplicates();
        var denied=publications.get(0);
        sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?) WHERE quiz_id=UUID_TO_BIN(?)",BOB.toString(),denied.source().quizId().toString());
        assertThat(due(root).items()).hasSize(3).noneMatch(i->i.source().equals(denied.source()));
        // Parent membership does not turn the inaccessible source into an early-selection grant.
        var retainedRoot=root;
        rejected(()->sets.items(new LearningSet.Scope(null,denied.source()),LearningSet.Window.UPCOMING,10,null),SOURCE_UNAVAILABLE);
        assertThat(sets.members(retainedRoot.setId(),100,null).items()).noneMatch(m->denied.source().equals(m.source()));
    }
    static boolean readStatement(String query) { return query.stripLeading().startsWith("SELECT") || query.stripLeading().startsWith("WITH"); }
    @Test void graphVersionAndScopeBindCursorsAndMutationDoesNotEnroll() {
        var root=add(root("Cursor"),whole()); fsrs(0); fsrs(1);
        var other=root("Other"); String cursor=sets.items(scope(root),LearningSet.Window.DUE,1,null).next();
        rejected(()->sets.items(scope(other),LearningSet.Window.DUE,1,cursor),INVALID_INPUT);
        rejected(()->sets.items(scope(other),LearningSet.Window.UPCOMING,1,cursor),INVALID_INPUT);
        root=add(root,item(0)); var retained=root;
        rejected(()->sets.items(scope(retained),LearningSet.Window.DUE,1,cursor),CONFLICT);
        rejected(()->sets.items(scope(retained),LearningSet.Window.DUE,1,"not-json"),INVALID_INPUT);
        assertThat(count("learning_schedule_slot")).isEqualTo(2);
        assertThat(count("learning_review_event")).isZero();
    }
    @Test void directMemberPagesDoNotLoadDescendantSourceGrants() {
        var child=add(root("Descendant"),whole()); var parent=add(root("Direct contents"),child(child)); fsrs(0);
        source.reset(); var page=sets.members(parent.setId(),1,null);
        assertThat(page.items()).containsExactly(child(child)); assertThat(page.next()).isNull();
        assertThat(source.rows.get()).isEqualTo(4); // identity, graph, set, direct child; no descendant source grant
        assertThat(source.statements.get().stream().filter(LearningSetMySqlIntegrationTest::readStatement).count()).isEqualTo(5);
        assertThat(source.statements.get()).noneMatch(q->q.stripLeading().startsWith("WITH RECURSIVE"));
    }
    @Test void databaseEnforcesOwnerAndCompleteSourceShapeAndServiceChecksExactQuestionMembership() {
        var root=root("Constraints"); var copy=copiedPublication();
        var wrong=new LearningSet.Member(LearningSet.Kind.QUESTION,null,ref,copy.question());
        rejected(()->sets.change(root.setId(),new LearningSetService.Change(UUID.randomUUID(),root.version(),wrong,true)),SOURCE_UNAVAILABLE);
        assertThat(count("learning_set_member")).isZero();
        var correct=new LearningSet.Member(LearningSet.Kind.QUESTION,null,copy.source(),copy.question());
        assertThat(sets.change(root.setId(),new LearningSetService.Change(UUID.randomUUID(),root.version(),correct,true)).set().version()).isEqualTo(2);
        for(Integer schema:Arrays.asList(null,0,4)) {
            assertThatThrownBy(()->sql.update("""
                INSERT INTO learning_set_member(learner_id,set_id,member_key,kind,quiz_id,quiz_revision_id,revision_schema_version)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'invalid-source','QUIZ',UUID_TO_BIN(?),UUID_TO_BIN(?),?)
                """,ANNA.toString(),root.setId().toString(),ref.quizId().toString(),ref.quizRevisionId().toString(),schema))
                    .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,failure ->
                        assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                            sqlFailure -> assertThat(sqlFailure.getErrorCode()).isEqualTo(3819)));
        }
        login("bob"); var foreign=root("Foreign set"); login("anna");
        assertThatThrownBy(()->sql.update("""
            INSERT INTO learning_set_member(learner_id,set_id,member_key,kind,child_set_id)
            VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'foreign-child','SET',UUID_TO_BIN(?))
            """,ANNA.toString(),root.setId().toString(),foreign.setId().toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(sets.members(root.setId(),10,null).items()).containsExactly(correct);
    }
    @Test void realCustomEnrollmentPauseAndResumeShareTheSameMixedSelector() {
        var parent=add(root("Mixed"),whole()); fsrs(0); var p=plan("Custom"); defaults(p);
        var custom=service.change(enroll(1)).cycle();
        assertThat(due(parent).items()).extracting(LearningSet.Item::strategy).containsExactlyInAnyOrder(Strategy.FSRS,Strategy.CUSTOM);
        var paused=service.pause(p.planId(),new LearningPlanService.Pause(UUID.randomUUID(),p.version(),true));
        instant.set(TIME.plusSeconds(31L*86400));
        assertThat(due(parent).items()).extracting(LearningSet.Item::strategy).containsExactly(Strategy.FSRS);
        service.pause(p.planId(),new LearningPlanService.Pause(UUID.randomUUID(),paused.plan().version(),false));
        assertThat(due(parent).items()).hasSize(2);
        assertThat(due(parent).items().stream().filter(i->i.strategy()==Strategy.CUSTOM).findFirst().orElseThrow().expected().cycleId()).isEqualTo(custom.cycleId());
        assertThat(service.cycle(ref,question(1)).sequence()).isZero();
        var disabled=new LearningSetServiceImpl(setRepository,users,List.of(authority),budget,limits,clock,metrics,manager,true,false);
        rejected(()->disabled.items(scope(parent),LearningSet.Window.DUE,10,null),UNSUPPORTED_VERSION);
    }
    @Test void completedCustomConsumerFixtureIsExcludedWithoutLosingItsDurableCycle() {
        var parent=add(root("Finite consumer"),whole());
        var plan=service.create(new LearningPlanService.Save(UUID.randomUUID(),0,definition("One step",1))).plan(); defaults(plan);
        var cycle=service.change(enroll(0)).cycle(); var customStore=new JdbcCustomScheduleRepository(new NamedParameterJdbcTemplate(sql));
        // Reachable #840 state fixture through its real calculator and durable state/event writer.
        // This proves only #841's reader; no #650 session/receipt or nested activation is claimed.
        for(int i=0;i<2;i++) {
            var current=service.cycle(ref,question(0));
            var key=new Key(ANNA,ref,question(0));
            tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                schedules.lockCycle(key).orElseThrow();
                var before=customStore.state(key,current,plan.definition());
                var transition=new CustomSchedulingStrategyImpl().review(plan.definition(),before,LearningSchedule.Rating.GOOD,instant.get(),stage->customStore.stage(current.cycleId(),stage));
                customStore.change(key,current,before,transition.state(),UUID.randomUUID(),"GOOD",transition.generated(),instant.get());
                instant.set(transition.state().dueAt());
            });
        }
        assertThat(service.cycle(ref,question(0)).completed()).isTrue();
        assertThat(due(parent).items()).isEmpty(); assertThat(sets.items(scope(parent),LearningSet.Window.UPCOMING,10,null).items()).isEmpty();
        assertThat(service.cycle(ref,question(0)).cycleId()).isEqualTo(cycle.cycleId());
        assertThat(count("learning_custom_change")).isEqualTo(2);
        sql.update("DELETE FROM learning_custom_change");
    }
    @Test void exactReplayAtExhaustedCreditPreservesOriginalIdentityAndChangedInputConflicts() {
        var original=new LearningSetService.Create(UUID.randomUUID(),"Original"); var ack=sets.create(original);
        for(int i=1;i<LearningCommandRateConfig.MAX_BURST;i++) sets.create(new LearningSetService.Create(UUID.randomUUID(),"Fill "+i));
        assertThatThrownBy(()->sets.create(new LearningSetService.Create(UUID.randomUUID(),"Exhausted")))
                .isInstanceOf(uk.gegc.quizmaker.shared.exception.RateLimitExceededException.class);
        var before=schedules(); assertThat(setService(setRepository).create(original)).isEqualTo(ack);
        rejected(()->sets.create(new LearningSetService.Create(original.commandId(),"Changed")),CONFLICT);
        assertThat(schedules()).isEqualTo(before); assertThat(count("learning_set")).isEqualTo(LearningCommandRateConfig.MAX_BURST);
    }
    @Test void currentPermissionLossAndInactiveAccountDenyWithoutLeakingMemberCounts() {
        var root=root("Access changes"); var add=new LearningSetService.Change(UUID.randomUUID(),root.version(),whole(),true);
        sets.change(root.setId(),add); fsrs(0);
        sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)",ANNA.toString());
        try {
            assertThat(due(root).items()).isEmpty(); assertThat(sets.members(root.setId(),10,null).items()).isEmpty();
            rejected(()->sets.change(root.setId(),add),SOURCE_UNAVAILABLE);
        } finally {
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name='plan-author'",ANNA.toString());
        }
        assertThat(due(root).items()).hasSize(1);
        sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)",ANNA.toString());
        rejected(()->sets.read(root.setId()),UNAVAILABLE);
    }
    @Test void unknownAndCorruptEvidenceAndReachedSqlFailureKeepTheirExplicitErrorSemantics() {
        var root=add(root("Evidence"),whole()); var initial=fsrs(0);
        String state=sql.queryForObject("SELECT state FROM learning_fsrs_state WHERE cycle_id=UUID_TO_BIN(?)",String.class,initial.expected().cycleId().toString());
        sql.update("UPDATE learning_fsrs_state SET state=JSON_SET(state,'$.version',2) WHERE cycle_id=UUID_TO_BIN(?)",initial.expected().cycleId().toString());
        rejected(()->due(root),UNSUPPORTED_VERSION);
        sql.update("UPDATE learning_fsrs_state SET state='{}' WHERE cycle_id=UUID_TO_BIN(?)",initial.expected().cycleId().toString());
        rejected(()->due(root),CORRUPT);
        sql.update("UPDATE learning_fsrs_state SET state=? WHERE cycle_id=UUID_TO_BIN(?)",state,initial.expected().cycleId().toString());
        source.fault.set("WITH RECURSIVE reachable"); rejected(()->due(root),PERSISTENCE_UNAVAILABLE);
        assertThat(source.reached.get()).isOne(); source.reset();
        assertThat(due(root).items().get(0).expected()).isEqualTo(initial.expected());
    }
    @Test void supportedOuterReadCommittedRollbackRetainsNoSetMemberOrReceipt() {
        fsrs(0); var before=schedules();
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            var provisional=add(root("Provisional"),whole()); assertThat(due(provisional).items()).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(sets.list(10,null).items()).isEmpty(); assertThat(count("learning_set_member")).isZero();
        assertThat(count("learning_set_command")).isZero(); assertThat(schedules()).isEqualTo(before);
    }
    @Test void ownerAndCurrentSourceAuthorityApplyToReadsAndExactAddReplay() {
        var root=root("Private"); var command=new LearningSetService.Change(UUID.randomUUID(),root.version(),whole(),true);
        sets.change(root.setId(),command); fsrs(0);
        login("bob"); rejected(()->sets.read(root.setId()),UNAVAILABLE);
        rejected(()->sets.items(scope(root),LearningSet.Window.DUE,10,null),UNAVAILABLE);
        assertThat(sets.list(10,null).items()).isEmpty();
        var bob=root("Bob"); rejected(()->sets.change(bob.setId(),new LearningSetService.Change(UUID.randomUUID(),bob.version(),child(root),true)),UNAVAILABLE);
        rejected(()->sets.change(bob.setId(),new LearningSetService.Change(UUID.randomUUID(),bob.version(),whole(),true)),SOURCE_UNAVAILABLE);
        login("anna"); sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?) WHERE quiz_id=UUID_TO_BIN(?)",BOB.toString(),ref.quizId().toString());
        assertThat(due(root).items()).isEmpty(); assertThat(sets.members(root.setId(),10,null).items()).isEmpty();
        rejected(()->sets.change(root.setId(),command),SOURCE_UNAVAILABLE);
        rejected(()->sets.items(new LearningSet.Scope(null,ref),LearningSet.Window.DUE,10,null),SOURCE_UNAVAILABLE);
        var current=sets.read(root.setId());
        assertThat(sets.change(root.setId(),new LearningSetService.Change(UUID.randomUUID(),current.version(),whole(),false)).set().version()).isEqualTo(current.version()+1);
        SecurityContextHolder.clearContext(); rejected(()->sets.read(root.setId()),UNAUTHENTICATED);
    }
    @Test void lostCommitAcknowledgementAndReconstructedServiceReplayOneMutation() {
        var request=new LearningSetService.Create(UUID.randomUUID(),"Durable");
        source.loseCommit.set(true); rejected(()->sets.create(request),PERSISTENCE_UNAVAILABLE);
        assertThat(source.lostCommits.get()).isOne(); source.reset();
        assertThat(metrics.counter("quizmaker.learning.sets","operation","create","outcome","unknown","version","1").count()).isOne();
        assertThat(metrics.counter("quizmaker.learning.sets","operation","create","outcome","rolled_back","version","1").count()).isZero();
        var restored=setService(setRepository).create(request);
        assertThat(sets.create(request)).isEqualTo(restored); assertThat(count("learning_set")).isOne();
        assertThat(count("learning_set_command")).isOne();
        rejected(()->sets.create(new LearningSetService.Create(request.commandId(),"Changed")),CONFLICT);
    }
    @Test void failureAfterReachedMemberAndReceiptWritesRollsBackGraphCreditAndCaller() {
        var root=root("Rollback"); var request=new LearningSetService.Change(UUID.randomUUID(),root.version(),whole(),true);
        var reached=new AtomicInteger();
        var failing=new JdbcLearningSetRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void record(UUID learner,UUID command,String input,LearningSet.Acknowledgement ack,Instant at) {
                super.record(learner,command,input,ack,at); reached.incrementAndGet(); throw new LearningSessionException(PERSISTENCE_UNAVAILABLE);
            }
        };
        var before=schedules(); long graph=sql.queryForObject("SELECT version FROM learning_set_graph WHERE learner_id=UUID_TO_BIN(?)",Long.class,ANNA.toString());
        assertThatThrownBy(()->tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            sql.update("UPDATE users SET email_verified=TRUE WHERE user_id=UUID_TO_BIN(?)",ANNA.toString());
            rejected(()->setService(failing).change(root.setId(),request),PERSISTENCE_UNAVAILABLE);
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(reached.get()).isOne(); assertThat(sets.read(root.setId()).version()).isEqualTo(root.version());
        assertThat(count("learning_set_member")).isZero(); assertThat(count("learning_set_command")).isOne();
        assertThat(sql.queryForObject("SELECT email_verified FROM users WHERE user_id=UUID_TO_BIN(?)",Boolean.class,ANNA.toString())).isFalse();
        assertThat(sql.queryForObject("SELECT version FROM learning_set_graph WHERE learner_id=UUID_TO_BIN(?)",Long.class,ANNA.toString())).isEqualTo(graph);
        assertThat(schedules()).isEqualTo(before); assertThat(sets.change(root.setId(),request).set().version()).isEqualTo(2);
    }
    @Test void deletionDetachesIncomingLinksAndPreservesChildrenSchedulesAndReplay() {
        var child=add(root("Child"),whole()); var parent=add(root("Parent"),child(child)); fsrs(0);
        var before=schedules(); var request=new LearningSetService.Delete(UUID.randomUUID(),child.version());
        var ack=sets.delete(child.setId(),request); assertThat(ack.set().deleted()).isTrue();
        assertThat(sets.delete(child.setId(),request)).isEqualTo(ack);
        rejected(()->sets.read(child.setId()),UNAVAILABLE); assertThat(sets.read(parent.setId()).version()).isEqualTo(parent.version()+1);
        assertThat(sets.members(parent.setId(),10,null).items()).isEmpty(); assertThat(due(parent).items()).isEmpty();
        // Only the deletion mutation debits credit; compare the actual schedule tables independently.
        var after=schedules(); before.remove("learning_command_budget"); after.remove("learning_command_budget"); assertThat(after).isEqualTo(before);
        var descendant=root("Surviving descendant"); var holder=add(root("Holder"),child(descendant));
        sets.delete(holder.setId(),new LearningSetService.Delete(UUID.randomUUID(),holder.version()));
        assertThat(sets.read(descendant.setId()).deleted()).isFalse();
    }
    @Test void oppositeEdgesSerializeInMysqlAndOnlyOneCommits() throws Exception {
        var a=root("A"); var b=root("B");
        rejected(()->sets.change(a.setId(),new LearningSetService.Change(UUID.randomUUID(),a.version(),child(a),true)),CONFLICT);
        CountDownLatch written=new CountDownLatch(1),release=new CountDownLatch(1);
        var holding=new JdbcLearningSetRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void record(UUID learner,UUID command,String input,LearningSet.Acknowledgement ack,Instant at) {
                super.record(learner,command,input,ack,at); written.countDown(); await(release);
            }
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->asAnna(()->setService(holding).change(a.setId(),new LearningSetService.Change(UUID.randomUUID(),a.version(),child(b),true))));
            await(written);
            var second=pool.submit(()->asAnna(()->sets.change(b.setId(),new LearningSetService.Change(UUID.randomUUID(),b.version(),child(a),true))));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(sql.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class)==0) {
                if(second.isDone()) second.get(); if(System.nanoTime()>end) throw new AssertionError("Competing edge did not reach a MySQL wait"); Thread.onSpinWait();
            }
            assertThat(second.isDone()).isFalse(); release.countDown(); assertThat(first.get(60,TimeUnit.SECONDS).set().version()).isEqualTo(2);
            assertThatThrownBy(()->second.get(60,TimeUnit.SECONDS)).hasCauseInstanceOf(LearningSessionException.class);
            assertThat(count("learning_set_member")).isOne(); assertThat(sets.members(a.setId(),10,null).items()).containsExactly(child(b));
            assertThat(sets.members(b.setId(),10,null).items()).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(30,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void concurrentIdenticalCreatesReturnOneDurableAcknowledgement() throws Exception {
        var request=new LearningSetService.Create(UUID.randomUUID(),"Concurrent replay");
        CountDownLatch written=new CountDownLatch(1),release=new CountDownLatch(1);
        var holding=new JdbcLearningSetRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void record(UUID learner,UUID command,String input,LearningSet.Acknowledgement ack,Instant at) {
                super.record(learner,command,input,ack,at); written.countDown(); await(release);
            }
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->asAnna(()->setService(holding).create(request))); await(written);
            var replay=pool.submit(()->asAnna(()->sets.create(request)));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(sql.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class)==0) {
                if(replay.isDone()) replay.get(); if(System.nanoTime()>end) throw new AssertionError("Replay did not reach a MySQL wait"); Thread.onSpinWait();
            }
            assertThat(replay.isDone()).isFalse(); release.countDown();
            var original=first.get(60,TimeUnit.SECONDS); assertThat(replay.get(60,TimeUnit.SECONDS)).isEqualTo(original);
            assertThat(setService(setRepository).create(request)).isEqualTo(original);
            assertThat(count("learning_set")).isOne(); assertThat(count("learning_set_command")).isOne();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(30,TimeUnit.SECONDS)).isTrue(); }
    }
    @ParameterizedTest @ValueSource(ints={TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_READ_UNCOMMITTED})
    void incompatibleCallersAreRejectedBeforeIdentityOrDomainSql(int isolation) {
        tx(isolation).executeWithoutResult(status -> {
            source.reset(); rejected(()->sets.create(new LearningSetService.Create(UUID.randomUUID(),"Refused")),UNSUPPORTED_TRANSACTION);
            assertThat(source.statements.get()).isEmpty();
        });
        assertThat(count("learning_set")).isZero();
    }
    static <T> T asAnna(Supplier<T> work) { login("anna"); try { return work.get(); } finally { SecurityContextHolder.clearContext(); } }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(90,TimeUnit.SECONDS)) throw new AssertionError("Barrier not reached"); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
