package uk.gegc.quizmaker.features.assignment;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublication.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;

class AssignmentPublicationTest {
    Snapshot snapshot() { return new Snapshot(UUID.randomUUID(),UUID.randomUUID(),1,1,Status.DRAFT,
            OWNER,new Scope(ScopeKind.PERSONAL,null),definition(),TIME); }
    @Test void capturesIdentityWithoutRetainingMutableMapsOrPrintingPrivateFacts() {
        var labels=new HashMap<>(Map.of(MEMBER,"Private designation"));
        var captured=new Captured(snapshot(),labels); labels.put(MEMBER,"Changed");
        assertThat(captured.designations()).containsEntry(MEMBER,"Private designation");
        assertThatThrownBy(()->captured.designations().put(MEMBER,"Changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(captured.toString()).doesNotContain("Private designation",MEMBER.toString(),OWNER.toString());
        assertThat(new Result(1,captured,false).toString()).doesNotContain("Private designation",MEMBER.toString());
    }
    @Test void failsClosedForMissingForeignBlankOrControlParticipantDesignations() {
        for(var labels:List.of(Map.<UUID,String>of(),Map.of(UUID.randomUUID(),"Foreign"),Map.of(MEMBER," "),
                Map.of(MEMBER,"line\nbreak")))
            assertThatThrownBy(()->new Captured(snapshot(),labels)).isInstanceOf(AssignmentPublicationException.class);
        assertThat(new Captured(snapshot(),Map.of(MEMBER,"😀".repeat(80))).designations().get(MEMBER)).hasSize(160);
    }
}
