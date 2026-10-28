package uk.gegc.quizmaker.features.attempt.form;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.FormOptionOrderServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionRevisionContractServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;
import static uk.gegc.quizmaker.features.attempt.form.OptionOrderFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.Reason.*;

/** Real services/registry/Flyway/MySQL; only the separately owned family adapters are component doubles. */
@Tag("db-serial")
class FormOptionOrderMySqlIntegrationTest {
    private static final List<String> TABLES=List.of("attempt_form_option","attempt_form_item_option_order","attempt_form_option_order",
            "attempt_policy_snapshot","attempt_form_item","attempt_form","question_revision_contract_bindings",
            "quiz_revision_items","question_option_revisions","quiz_revisions","question_revisions");
    private static FormDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager manager;
    private static QuizRevisionRepository revisions;
    private SimpleMeterRegistry metrics;
    private QuestionRevisionContractService contracts;
    private FormOptionOrderService service;

    @BeforeAll
    static void connect() {
        String port=System.getenv().getOrDefault("TEST_MYSQL_PORT","3306");
        if(!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        source=new FormDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port
                +"/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME","bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD","bestuser")));
        sql=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        revisions=proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)));
        dropSchema();
        sql.execute("CREATE TABLE option_legacy_fixture (id INT PRIMARY KEY, marker VARCHAR(20))");
        sql.update("INSERT INTO option_legacy_fixture VALUES (1,'legacy')");
        migrate("option_identity_history","80","82");
        migrate("option_form_history","83","85");
        // An existing #519 form stays explicitly without order; V89 must not synthesize one.
        var revision=quiz("before-migration",question("before",QuestionType.MCQ_SINGLE,3));
        revisions.insert(revision);
        var form=OptionOrderFixtures.form("before-form",revision);
        new TransactionTemplate(manager).executeWithoutResult(status->fixedForms().createOrReplay(OWNER,form));
        migrate("option_order_history","88","89");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form_option_order",Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form",Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT marker FROM option_legacy_fixture",String.class)).isEqualTo("legacy");
    }

    private static void migrate(String history,String baseline,String target) {
        Flyway.configure().dataSource(source).table(history).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion(baseline).target(target).load().migrate();
    }

    @BeforeEach
    void prepare() { reset(); metrics=new SimpleMeterRegistry(); service=service(OptionOrderFixtures::layout); }
    @AfterEach
    void cleanup() { reset(); if (metrics != null) metrics.close(); }
    private static void reset() {
        source.reset();
        for(String table:TABLES) if (!table.equals("attempt_policy_snapshot")) sql.update("DELETE FROM "+table);
        source.reset();
    }
    @AfterAll
    static void dropSchema() {
        if(source==null) return; source.reset();
        for(String table:TABLES) sql.execute("DROP TABLE IF EXISTS "+table);
        for(String table:List.of("option_identity_history","option_form_history","option_order_history","option_legacy_fixture"))
            sql.execute("DROP TABLE IF EXISTS "+table);
    }

    @Test
    void fiveFamiliesAndOptionalFillGapOptionsPersistExactOrderAcrossReplayAndRestart() {
        var revision=saved("families",question("single",QuestionType.MCQ_SINGLE,4),question("multi",QuestionType.MCQ_MULTI,4),
                question("boolean",QuestionType.TRUE_FALSE,0),question("gap",QuestionType.FILL_GAP,3),
                question("gap-free",QuestionType.FILL_GAP,0),question("ordering",QuestionType.ORDERING,5));
        var form=OptionOrderFixtures.form("family-form",revision);
        var expected=service.createOrReplay(OWNER,form,SHUFFLED);
        assertThat(expected.items()).hasSize(6);
        assertThat(service.createOrReplay(OWNER,form,SHUFFLED)).isEqualTo(expected);
        var restarted=service(OptionOrderFixtures::layout);
        assertThat(restarted.findByIds(OWNER,List.of(form.id()))).containsExactly(expected);
        assertThat(restarted.createOrReplay(OWNER,form,SHUFFLED)).isEqualTo(expected);
        for(var item:expected.items()) {
            var q=revision.items().stream().filter(i->i.id().equals(item.sourceItemId())).findFirst().orElseThrow().question();
            assertThat(item.optionIds()).containsExactlyInAnyOrderElementsOf(q.options().stream().map(QuestionRevision.Option::id).toList());
            assertThat(item.optionIds()).isEqualTo(AnswerOptionOrder.order(form.id(),item.formItemId(),SHUFFLED,q,layout(q)));
        }
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form_item_option_order WHERE option_count=0",Integer.class)).isEqualTo(2);
        assertThat(expected.toString()).doesNotContain("privateCorrectId","private explanation","key-");
    }

    @Test
    void repeatedQuestionOccurrencesKeepSeparateOptionOrdersAndIdentity() {
        var q=question("reused",QuestionType.MCQ_SINGLE,7); var revision=saved("reuse",q,q);
        var form=OptionOrderFixtures.form("reuse-form",revision);
        var result=service.createOrReplay(OWNER,form,SHUFFLED);
        assertThat(result.items()).hasSize(2).extracting(i->i.binding().questionRevisionId()).containsOnly(q.id());
        assertThat(result.items()).extracting(FormOptionOrder.Item::formItemId).doesNotHaveDuplicates();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form_option",Integer.class)).isEqualTo(14);
    }

    @Test
    void wrongOwnerNullIdentityMissingAndForeignItemsNeverCreateOrDiscloseOrder() {
        var revision=saved("access",question("access-q",QuestionType.MCQ_SINGLE,3));
        var form=OptionOrderFixtures.form("access-form",revision); var expected=service.createOrReplay(OWNER,form,AUTHORED);
        assertThat(service.findByIds(OTHER_OWNER,List.of(form.id()))).isEmpty();
        assertThatThrownBy(()->service.findByIds(null,List.of(form.id()))).hasMessage("Fixed form rejected: FORM_UNAVAILABLE");
        assertThatThrownBy(()->service.createOrReplay(OTHER_OWNER,form,AUTHORED)).hasMessage("Fixed form rejected: REVISION_UNAVAILABLE");
        assertThatThrownBy(()->service.createOrReplay(null,form,AUTHORED)).hasMessage("Fixed form rejected: REVISION_UNAVAILABLE");
        var foreign=new FixedForm(id("bad-form"),1,revision.id(),List.of(new FixedForm.Item(id("bad-item"),id("foreign-source"),0)));
        rejected(()->service.createOrReplay(OWNER,foreign,AUTHORED),INVALID_ORDER);
        assertThat(service.findByIds(OWNER,List.of(form.id(),id("absent"),form.id()))).containsExactly(expected);
        assertThat(count("attempt_form")).isEqualTo(1);
    }

    @Test
    void unknownUnavailableAndMalformedAdaptersFailBeforeAnyFormPersistence() {
        var revision=saved("unsupported",question("unsupported-q",QuestionType.MCQ_SINGLE,3));
        var form=OptionOrderFixtures.form("unsupported-form",revision);
        var unavailable=service(q->{ throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT); });
        assertThatThrownBy(()->unavailable.createOrReplay(OWNER,form,AUTHORED)).hasMessage("Question contract rejected: UNSUPPORTED_CONTRACT");
        var invalid=service(q->new OptionLayout(1,true,List.of()));
        rejected(()->invalid.createOrReplay(OWNER,form,AUTHORED),INVALID_ORDER);
        assertThat(count("attempt_form")).isZero(); assertThat(count("attempt_form_option_order")).isZero();
        // Missing adapter is a real registry failure, not impossible output mocked from the resolver.
        var emptyRegistry=new uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl(List.of(),List.of(),metrics);
        var noContracts=new QuestionRevisionContractServiceImpl(proxy(new JdbcQuestionContractBindingRepository(named())),emptyRegistry,manager);
        var missing=new FormOptionOrderServiceImpl(revisions,noContracts,fixedForms(),orders(),manager,metrics);
        assertThatThrownBy(()->missing.createOrReplay(OWNER,form,AUTHORED)).hasMessage("Question contract rejected: UNSUPPORTED_CONTRACT");
        assertThat(service.createOrReplay(OWNER,form,AUTHORED).items()).hasSize(1);
    }

    @Test
    void policyChangeCannotOverwriteCommittedOrder() {
        var revision=saved("conflict",question("conflict-q",QuestionType.MCQ_SINGLE,5));
        var form=OptionOrderFixtures.form("conflict-form",revision); var expected=service.createOrReplay(OWNER,form,SHUFFLED);
        rejected(()->service.createOrReplay(OWNER,form,AUTHORED),ORDER_CONFLICT);
        assertThat(service.findByIds(OWNER,List.of(form.id()))).containsExactly(expected);
        assertThat(count("attempt_form_option")).isEqualTo(5);
    }

    @Test
    void reachedOptionWritesRollBackTogetherWithFormAndSuccessfulRetryWorks() {
        var revision=saved("rollback",question("rollback-q",QuestionType.MCQ_SINGLE,4));
        var form=OptionOrderFixtures.form("rollback-form",revision); var reached=new AtomicBoolean();
        source.beforeCommit=connection->{
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM attempt_form_option")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(4); reached.set(true);
            }
            // A confirmed server rollback, rather than an ambiguous still-usable connection
            // that JDBC cleanup could commit when restoring autoCommit=true.
            connection.rollback();
            throw new SQLException("Synthetic failure after actual option inserts","08006");
        };
        rejected(()->service.createOrReplay(OWNER,form,SHUFFLED),PERSISTENCE_UNAVAILABLE);
        source.reset(); assertThat(reached).isTrue();
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            assertThat(count("attempt_form")).isZero(); assertThat(count("attempt_form_item")).isZero();
            assertThat(count("attempt_form_option_order")).isZero(); assertThat(count("attempt_form_option")).isZero();
        });
        assertThat(service.createOrReplay(OWNER,form,SHUFFLED).items()).hasSize(1);
    }

    @Test
    void lostCommitAcknowledgementReplaysOneDurableOutcomeAfterServiceRestart() {
        var revision=saved("lost",question("lost-q",QuestionType.MCQ_MULTI,4)); var form=OptionOrderFixtures.form("lost-form",revision);
        var committed=new AtomicBoolean();
        source.afterCommit=connection->{ committed.set(true);throw new SQLException("Synthetic lost acknowledgement","08006"); };
        rejected(()->service.createOrReplay(OWNER,form,SHUFFLED),PERSISTENCE_UNAVAILABLE);
        source.reset(); assertThat(committed).isTrue();
        assertThat(metrics.get("quizmaker.attempt.option.order.operations").tags("operation","create_or_replay",
                "outcome","persistence_unavailable").timer().count()).isEqualTo(1);
        assertThat(metrics.find("quizmaker.attempt.option.order.operations").tags("operation","create_or_replay",
                "outcome","rejected").timer()).isNull();
        var restarted=service(OptionOrderFixtures::layout); var existing=restarted.findByIds(OWNER,List.of(form.id())).get(0);
        assertThat(restarted.createOrReplay(OWNER,form,SHUFFLED)).isEqualTo(existing);
        assertThat(count("attempt_form")).isEqualTo(1); assertThat(count("attempt_form_option")).isEqualTo(4);
    }

    @Test
    void outerTransactionOwnsScopeAndFinalCommitRatherThanAnInnerSuccessfulReturn() {
        var revision=saved("outer",question("outer-q",QuestionType.MCQ_SINGLE,3)); var form=OptionOrderFixtures.form("outer-form",revision);
        var tx=new TransactionTemplate(manager);
        assertThatThrownBy(()->tx.executeWithoutResult(status->service.createOrReplay(OWNER,form,AUTHORED)))
                .hasMessage("Question contract rejected: SCOPE_REQUIRED");
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            contracts.declareScope(OWNER,Set.of(revision.id())); service.createOrReplay(OWNER,form,AUTHORED);
            assertThat(count("attempt_form_option")).isEqualTo(3); throw new IllegalStateException("Outer failure");
        })).hasMessage("Outer failure");
        assertThat(count("attempt_form")).isZero();
        tx.executeWithoutResult(status->{contracts.declareScope(OWNER,Set.of(revision.id())); service.createOrReplay(OWNER,form,AUTHORED);});
        assertThat(service.findByIds(OWNER,List.of(form.id()))).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,true","READ_COMMITTED,false","REPEATABLE_READ,true","REPEATABLE_READ,false"})
    void independentTransactionsHaveOneImmutableWinnerAndDefinedReplayOrConflict(String isolation,boolean identical) throws Exception {
        var revision=saved("race",question("race-q",QuestionType.MCQ_SINGLE,6));var form=OptionOrderFixtures.form("race-form",revision);
        var firstWrites=new CountDownLatch(1);var secondLock=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);
        var contenders=new AtomicInteger();var pauseFirst=new AtomicBoolean(true);
        source.beforeSql=(statement,connection)->{
            if(statement.contains("SELECT locked.id")&&statement.contains("FOR UPDATE")&&contenders.incrementAndGet()==2)
                secondLock.countDown();
            if(isOrderComparison(statement)&&pauseFirst.getAndSet(false)) {
                assertThat(count("attempt_form")).isEqualTo(1);
                assertThat(count("attempt_form_option_order")).isEqualTo(1);
                assertThat(count("attempt_form_option")).isEqualTo(6);
                firstWrites.countDown();
                try {if(!releaseFirst.await(10,TimeUnit.SECONDS)) throw new SQLException("First writer release timeout");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new SQLException("Writer interrupted");}
            }
        };
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->race(form,isolation,AUTHORED));
            assertThat(firstWrites.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(()->race(form,isolation,identical?AUTHORED:SHUFFLED));
            assertThat(secondLock.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).as("Second writer cannot finish while the first holds its transaction").isFalse();
            releaseFirst.countDown();
            var results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(contenders.get()).isEqualTo(2);
            assertThat(results.stream().filter(o->o.result()!=null).count()).isEqualTo(identical?2:1);
            if(identical) assertThat(results.get(0).result()).isEqualTo(results.get(1).result());
            else assertThat(results.stream().filter(o->o.failure()!=null).map(Outcome::failure)).containsExactly(ORDER_CONFLICT);
            var winner=results.stream().filter(o->o.result()!=null).findFirst().orElseThrow().result();
            assertThat(service.findByIds(OWNER,List.of(form.id()))).containsExactly(winner);
            assertThat(count("attempt_form_option_order")).isEqualTo(1);assertThat(count("attempt_form_option")).isEqualTo(6);
        } finally {releaseFirst.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();source.reset();}
    }

    @ParameterizedTest
    @ValueSource(strings={"READ_COMMITTED","REPEATABLE_READ"})
    void differentEmptyFormsOfSameRevisionCommitWithoutLockUpgrade(String isolation) throws Exception {
        var revision=saved("empty-independent");
        var firstForm=OptionOrderFixtures.form("empty-first",revision);
        var secondForm=OptionOrderFixtures.form("empty-second",revision);
        var comparisons=new CyclicBarrier(2);
        var connections=ConcurrentHashMap.<Integer>newKeySet();
        source.beforeSql=(statement,connection)->{
            if(isOrderComparison(statement)) {
                // Each real transaction has written its own complete empty form/order, and
                // neither may compare/commit until the other holds the same revision's shared lock.
                assertThat(count("attempt_form")).isEqualTo(1);
                assertThat(count("attempt_form_option_order")).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT @@transaction_isolation",String.class))
                        .isEqualTo(isolation.replace('_','-'));
                assertThat(connections.add(connection)).isTrue();
                try {comparisons.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new SQLException("Writer interrupted");}
                catch(BrokenBarrierException | TimeoutException failure){throw new SQLException("Empty-form comparison barrier failed");}
            }
        };
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->race(firstForm,isolation,AUTHORED));
            var second=workers.submit(()->race(secondForm,isolation,AUTHORED));
            var results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(connections).hasSize(2);
            assertThat(source.sqlErrorCodes).doesNotContain(1213,1205);
            assertThat(results).allSatisfy(outcome->{
                assertThat(outcome.failure()).isNull();
                assertThat(outcome.result()).isNotNull();
                assertThat(outcome.result().items()).isEmpty();
            });
            source.beforeSql=null;
            var restarted=service(OptionOrderFixtures::layout);
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
                assertThat(restarted.findByIds(OWNER,List.of(firstForm.id(),secondForm.id())))
                        .containsExactlyElementsOf(results.stream().map(Outcome::result).toList());
                assertThat(count("attempt_form")).isEqualTo(2);
                assertThat(count("attempt_form_option_order")).isEqualTo(2);
                assertThat(count("attempt_form_item_option_order")).isZero();
                assertThat(count("attempt_form_option")).isZero();
            });
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();source.reset();}
    }

    @Test
    void multipleParentsAndIncreasingOptionsHaveThreeReadQueriesAndIndexedLookups() {
        var ids=new ArrayList<UUID>();
        for(int n=0;n<12;n++) {
            var revision=saved("query-"+n,question("query-q-"+n,QuestionType.MCQ_SINGLE,n+2),question("query-other-"+n,QuestionType.FILL_GAP,n+3));
            var form=OptionOrderFixtures.form("query-form-"+n,revision);service.createOrReplay(OWNER,form,SHUFFLED);ids.add(form.id());
        }
        source.selects.clear(); assertThat(service.findByIds(OWNER,ids.subList(0,2))).hasSize(2); assertThat(source.selects).hasSize(3);
        source.selects.clear(); assertThat(service.findByIds(OWNER,ids)).hasSize(12); assertThat(source.selects).hasSize(3);
        assertThat(source.selects).allSatisfy(query->assertThat(query).doesNotContain("question_text","content","explanation"));
        source.selects.clear(); assertThat(service.findByIds(OWNER,List.of())).isEmpty();assertThat(source.selects).isEmpty();
        var plan=sql.queryForMap("EXPLAIN SELECT * FROM attempt_form_option_order WHERE form_id=?",bytes(ids.get(0)));
        assertThat(plan.get("key")).isEqualTo("PRIMARY");
        var child=sql.queryForMap("EXPLAIN SELECT option_id FROM attempt_form_option WHERE form_id=? ORDER BY item_id,position",bytes(ids.get(0)));
        assertThat(child.get("possible_keys").toString()).contains("PRIMARY","uq_form_option_position");
    }

    @Test
    void foreignKeysPositionsAndMembershipProtectRealStorageAndCorruptReadsFailClosed() {
        var revision=saved("constraints",question("constraints-q",QuestionType.MCQ_SINGLE,3),question("foreign-q",QuestionType.MCQ_SINGLE,2));
        var form=OptionOrderFixtures.form("constraints-form",revision);var expected=service.createOrReplay(OWNER,form,AUTHORED);
        var item=expected.items().get(0);var q=item.binding().questionRevisionId();var option=item.optionIds().get(0);
        assertThatThrownBy(()->sql.update("INSERT INTO attempt_form_option VALUES (?,?,?,?,?)",bytes(form.id()),bytes(item.formItemId()),bytes(q),bytes(id("foreign-option")),9))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->sql.update("UPDATE attempt_form_option SET position=0 WHERE form_id=? AND item_id=? AND option_id=?",
                bytes(form.id()),bytes(item.formItemId()),bytes(item.optionIds().get(1))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->sql.update("UPDATE attempt_form_option SET question_revision_id=? WHERE form_id=? AND item_id=?",
                bytes(id("foreign-question")),bytes(form.id()),bytes(item.formItemId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.findByIds(OWNER,List.of(form.id()))).containsExactly(expected);
        sql.update("UPDATE attempt_form_option SET position=8 WHERE form_id=? AND item_id=? AND option_id=?",bytes(form.id()),bytes(item.formItemId()),bytes(option));
        rejected(()->service.findByIds(OWNER,List.of(form.id())),INVALID_STORED_ORDER);
    }

    @Test
    void emptyFormsAndOldFormsRemainDistinctAndMetricsCannotChangeTheResult() {
        var revision=saved("empty");var form=OptionOrderFixtures.form("empty-form",revision);
        new TransactionTemplate(manager).executeWithoutResult(status->fixedForms().createOrReplay(OWNER,form));
        assertThat(service.findByIds(OWNER,List.of(form.id()))).isEmpty();
        // Force meter type conflict; persistence must still return its actual committed success.
        metrics.counter("quizmaker.attempt.option.order.operations","operation","create_or_replay","outcome","success");
        var expected=service.createOrReplay(OWNER,form,AUTHORED);
        assertThat(expected.items()).isEmpty();assertThat(service.findByIds(OWNER,List.of(form.id()))).containsExactly(expected);
        assertThat(sql.queryForObject("SELECT marker FROM option_legacy_fixture",String.class)).isEqualTo("legacy");
    }

    @Test
    void caughtReachedWriteFailureMarksOuterTransactionRollbackOnly() {
        var revision=saved("caught",question("caught-q",QuestionType.MCQ_SINGLE,4));
        var form=OptionOrderFixtures.form("caught-form",revision);var reached=new AtomicInteger();
        source.beforeSql=(statement,connection)->{
            if(isOrderComparison(statement)) {
                int inserted=count("attempt_form_option");
                assertThat(inserted).isEqualTo(4);
                reached.set(inserted);
                throw new SQLException("Synthetic reached option checkpoint","08006");
            }
        };
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            contracts.declareScope(OWNER,Set.of(revision.id()));
            sql.update("UPDATE option_legacy_fixture SET marker='partial'");
            rejected(()->service.createOrReplay(OWNER,form,SHUFFLED),PERSISTENCE_UNAVAILABLE);
        })).isInstanceOf(UnexpectedRollbackException.class);
        source.reset();assertThat(reached).hasValue(4);
        assertThat(count("attempt_form_option")).isZero();assertThat(count("attempt_form")).isZero();
        assertThat(sql.queryForObject("SELECT marker FROM option_legacy_fixture",String.class)).isEqualTo("legacy");
        assertThat(service.createOrReplay(OWNER,form,SHUFFLED).items()).hasSize(1);
    }

    @Test
    void readOutageIsNotAnEmptyResultAndMissingChildCountFailsClosed() {
        var revision=saved("read-failure",question("read-failure-q",QuestionType.MCQ_SINGLE,2));
        var form=OptionOrderFixtures.form("read-failure-form",revision);service.createOrReplay(OWNER,form,AUTHORED);
        source.failConnection=true;
        rejected(()->service.findByIds(OWNER,List.of(form.id())),PERSISTENCE_UNAVAILABLE);source.reset();
        assertThat(service.findByIds(OWNER,List.of(form.id()))).hasSize(1);
        sql.update("DELETE FROM attempt_form_option WHERE form_id=? AND position=1",bytes(form.id()));
        rejected(()->service.findByIds(OWNER,List.of(form.id())),INVALID_STORED_ORDER);
    }

    @Test
    void completeRevisionLoadingDuringWritesDoesNotSelectPerQuestionOrOption() {
        var small=saved("write-small",question("small-a",QuestionType.MCQ_SINGLE,2),question("small-b",QuestionType.MCQ_MULTI,3));
        var questions=java.util.stream.IntStream.range(0,24).mapToObj(n->question("large-"+n,QuestionType.MCQ_SINGLE,n+2))
                .toArray(QuestionRevision[]::new);
        var large=saved("write-large",questions);
        source.selects.clear();service.createOrReplay(OWNER,OptionOrderFixtures.form("small-form",small),SHUFFLED);
        int selects=source.selects.size();
        source.selects.clear();service.createOrReplay(OWNER,OptionOrderFixtures.form("large-form",large),SHUFFLED);
        assertThat(source.selects).hasSize(selects);
        assertThat(service.findByIds(OWNER,List.of(id("small-form"),id("large-form")))).hasSize(2);
    }

    @Test
    void unavailableTelemetryClockDoesNotPreventTheBusinessWrite() {
        var revision=saved("clock",question("clock-q",QuestionType.MCQ_SINGLE,3));
        var form=OptionOrderFixtures.form("clock-form",revision);var fail=new AtomicBoolean();var attempted=new AtomicBoolean();
        var clock=new io.micrometer.core.instrument.Clock() {
            public long wallTime(){return 0;}
            public long monotonicTime(){
                if(fail.get()){attempted.set(true);throw new IllegalStateException("Synthetic telemetry failure");}
                return 0;
            }
        };
        var broken=new SimpleMeterRegistry(io.micrometer.core.instrument.simple.SimpleConfig.DEFAULT,clock);
        try {
            var boundary=new FormOptionOrderServiceImpl(revisions,contracts,fixedForms(),orders(),manager,broken);
            fail.set(true);var result=boundary.createOrReplay(OWNER,form,AUTHORED);
            assertThat(attempted).isTrue();assertThat(service.findByIds(OWNER,List.of(form.id()))).containsExactly(result);
        } finally {fail.set(false);broken.close();}
    }

    @Test
    void sourceQuestionFkRejectsAnotherExistingBoundQuestionRatherThanOnlyMissingIds() {
        var revision=saved("exact-fk",question("fk-a",QuestionType.MCQ_SINGLE,2),question("fk-b",QuestionType.MCQ_SINGLE,2));
        var form=OptionOrderFixtures.form("exact-fk-form",revision);
        new TransactionTemplate(manager).executeWithoutResult(status->fixedForms().createOrReplay(OWNER,form));
        sql.update("INSERT INTO attempt_form_option_order VALUES (?,1,'AUTHORED',1,2)",bytes(form.id()));
        var first=form.items().get(0);var other=revision.items().get(1).question();var binding=binding(other);
        assertThatThrownBy(()->sql.update("INSERT INTO attempt_form_item_option_order VALUES (?,?,?,?,?,?,?,?,?,0)",
                bytes(form.id()),bytes(first.id()),bytes(revision.id()),bytes(first.sourceItemId()),bytes(other.id()),
                binding.response().id(),1,binding.scoring().id(),1))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("fk_item_option_order_question");
        // The supported complete writer succeeds using the same source/binding prerequisites.
        assertThat(service.createOrReplay(OWNER,form,AUTHORED).items()).hasSize(2);
    }

    private Outcome race(FixedForm form,String isolation,uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy policy) {
        var tx=new TransactionTemplate(manager);tx.setTimeout(15);
        tx.setIsolationLevel(isolation.equals("READ_COMMITTED")?TransactionDefinition.ISOLATION_READ_COMMITTED:TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {return new Outcome(tx.execute(status->{contracts.declareScope(OWNER,Set.of(form.quizRevisionId()));return service.createOrReplay(OWNER,form,policy);}),null);}
        catch(OptionOrderException failure){return new Outcome(null,failure.reason());}
    }
    private FormOptionOrderService service(Function<QuestionRevision,OptionLayout> layout) {
        contracts=new QuestionRevisionContractServiceImpl(proxy(new JdbcQuestionContractBindingRepository(named())),registry(layout),manager);
        return new FormOptionOrderServiceImpl(revisions,contracts,fixedForms(),orders(),manager,metrics);
    }
    private QuizRevision saved(String key,QuestionRevision... questions) {
        var revision=quiz(key,questions);revisions.insert(revision);
        contracts.bind(OWNER,revision.id(),Arrays.stream(questions).map(OptionOrderFixtures::binding).distinct().toList());return revision;
    }
    private static FixedFormRepository fixedForms(){return proxy(new JdbcFixedFormRepository(named()));}
    private static FormOptionOrderRepository orders(){return proxy(new JdbcFormOptionOrderRepository(named()));}
    private static NamedParameterJdbcTemplate named(){return new NamedParameterJdbcTemplate(source);}
    private static boolean isOrderComparison(String statement){
        return statement.startsWith("SELECT o.* FROM attempt_form_option_order o ");
    }
    private static int count(String table){return sql.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private static <T>T proxy(T target){var factory=new ProxyFactory(target);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T result=(T)factory.getProxy();return result;}
    private static void rejected(Runnable action,OptionOrderException.Reason reason){assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(OptionOrderException.class,failure->assertThat(failure.reason()).isEqualTo(reason))
            .hasMessage("Option order rejected: "+reason).hasNoCause();}
    private record Outcome(FormOptionOrder result,OptionOrderException.Reason failure){}
}
