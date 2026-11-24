package uk.gegc.quizmaker.features.learning;

import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningSetServiceImpl;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSet.*;
import uk.gegc.quizmaker.features.learning.domain.repository.*;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningSetTest {
    final LearningDueSelectionConfig limits=new LearningDueSelectionConfig();
    final LearningSetRepository repository=mock(LearningSetRepository.class);
    final AuthenticatedUserLookup users=mock(AuthenticatedUserLookup.class);
    final LearningSetService service=new LearningSetServiceImpl(repository,users,List.of(),mock(LearningCommandBudget.class),limits,
            Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),mock(PlatformTransactionManager.class),true,true);
    final UUID id=UUID.randomUUID();
    final LearningSession.Source source=new LearningSession.Source(1,UUID.randomUUID(),UUID.randomUUID(),1);
    @BeforeEach void configured() { limits.setMaxBatchSize(20); }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void exactlyOneTypedTargetIsRequiredAndIdentitySeparatesPublications() {
        var set=new Member(Kind.SET,id,null,null); var quiz=new Member(Kind.QUIZ,null,source,null); var question=new Member(Kind.QUESTION,null,source,id);
        assertThat(Set.of(set.key(),quiz.key(),question.key())).hasSize(3);
        for(Runnable invalid:List.<Runnable>of(()->new Member(null,null,null,null),()->new Member(Kind.SET,null,null,null),
                ()->new Member(Kind.SET,id,source,null),()->new Member(Kind.QUIZ,null,source,id),
                ()->new Member(Kind.QUESTION,null,source,null),()->new Scope(null,null),()->new Scope(id,source)))
            assertThatThrownBy(invalid::run).isInstanceOf(LearningSessionException.class);
        var another=new LearningSession.Source(1,source.quizId(),UUID.randomUUID(),1);
        assertThat(new Member(Kind.QUESTION,null,another,id).key()).isNotEqualTo(question.key());
    }
    @Test void invalidLabelsLimitsCommandsAndCursorsNeverReachIdentityOrPersistence() {
        for(String name:List.of(""," "," leading","trailing ","bad\nname","x".repeat(256)))
            rejected(()->service.create(new LearningSetService.Create(id,name)));
        rejected(()->service.create(new LearningSetService.Create(null,"Valid")));
        rejected(()->service.change(id,new LearningSetService.Change(id,0,new Member(Kind.SET,UUID.randomUUID(),null,null),true)));
        rejected(()->service.delete(id,new LearningSetService.Delete(id,0)));
        for(int limit:new int[]{-1,0,21}) rejected(()->service.items(new Scope(id,null),Window.DUE,limit,null));
        for(String cursor:List.of("malformed","x".repeat(32769))) rejected(()->service.items(new Scope(id,null),Window.DUE,1,cursor));
        var unsafe=new Cursor(1,new Scope(id,null),Window.DUE,1,Instant.MAX,id,id,id,null);
        String unsafeToken=Base64.getUrlEncoder().withoutPadding().encodeToString(new uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat()
                .encode(unsafe).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rejected(()->service.items(new Scope(id,null),Window.DUE,1,unsafeToken));
        limits.setMaxBatchSize(null);
        assertThatThrownBy(()->service.list(1,null)).isInstanceOfSatisfying(LearningSessionException.class,e->assertThat(e.reason()).isEqualTo(LearningSessionException.Reason.PERSISTENCE_UNAVAILABLE));
        verifyNoInteractions(users,repository);
    }
    @Test void incompatibleOrReadOnlyMutationRejectsBeforeIdentityAndCannotWrite() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        for(Integer isolation:Arrays.asList(null,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_READ_UNCOMMITTED)) {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
            assertThatThrownBy(()->service.create(new LearningSetService.Create(id,"Refused"))).isInstanceOfSatisfying(LearningSessionException.class,e->assertThat(e.reason()).isEqualTo(LearningSessionException.Reason.UNSUPPORTED_TRANSACTION));
        }
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(()->service.create(new LearningSetService.Create(id,"Read only"))).isInstanceOfSatisfying(LearningSessionException.class,e->assertThat(e.reason()).isEqualTo(LearningSessionException.Reason.UNSUPPORTED_TRANSACTION));
        verifyNoInteractions(users,repository);
    }
    @Test void resultListsAreDefensiveAndNoModelCarriesSourceContent() {
        var list=new ArrayList<View>(); list.add(new View(id,"Private",1,false,Instant.EPOCH,Instant.EPOCH));
        var page=new SetPage(list,null); list.clear(); assertThat(page.items()).hasSize(1);
        assertThatThrownBy(()->page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(page.items().get(0).toString()).doesNotContain("Private");
    }
    @Test void everySetCommandResponseAndCursorHasAClosedDiagnosticRepresentation() {
        var name="PRIVATE_SET_"+UUID.randomUUID(); var token="PRIVATE_CURSOR_"+UUID.randomUUID();
        var view=new View(id,name,1,false,Instant.EPOCH,Instant.EPOCH);
        var member=new Member(Kind.QUESTION,null,source,id);
        var ack=new Acknowledgement(id,view,1);
        var item=new Item(source,id,new LearningSchedule.Expected(id,1,0),LearningPlan.Strategy.FSRS,Instant.EPOCH);
        var input=new uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat().encode(
                Arrays.asList("create",null,new LearningSetService.Create(id,name)));
        var values=List.of(member,view,ack,new Scope(id,null),new SetPage(List.of(view),id),
                new MemberPage(List.of(member),token),item,new ItemPage(Window.DUE,Instant.EPOCH,List.of(item),token),
                new Cursor(1,new Scope(id,null),Window.DUE,1,Instant.EPOCH,source.quizId(),source.quizRevisionId(),id,token),
                new LearningSetService.Create(id,name),new LearningSetService.Change(id,1,member,true),new LearningSetService.Delete(id,1),
                new LearningSetRepository.Receipt(input,ack),
                new uk.gegc.quizmaker.features.learning.api.LearningSetController.CreateRequest(id,name),
                new uk.gegc.quizmaker.features.learning.api.LearningSetController.ChangeRequest(id,1,member,true),
                new uk.gegc.quizmaker.features.learning.api.LearningSetController.DeleteRequest(id,1));
        assertThat(values).allSatisfy(value->assertThat(value.toString()).contains("[redacted]")
                .doesNotContain(id.toString(),source.quizId().toString(),source.quizRevisionId().toString(),name,token,input));
    }
    static void rejected(Runnable work) { assertThatThrownBy(work::run).isInstanceOfSatisfying(LearningSessionException.class,e->assertThat(e.reason()).isEqualTo(LearningSessionException.Reason.INVALID_INPUT)); }
}
