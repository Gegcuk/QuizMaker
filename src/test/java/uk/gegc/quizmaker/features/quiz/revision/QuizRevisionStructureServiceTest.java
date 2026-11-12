package uk.gegc.quizmaker.features.quiz.revision;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionStructureServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;

class QuizRevisionStructureServiceTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    @AfterEach void closeRegistry() { registry.close(); }
    @Test
    void legacyNeverQueriesOrReconstructsMutableAuthoringData() {
        var repository = mock(QuizRevisionRepository.class);
        var result = new QuizRevisionStructureServiceImpl(repository, registry).read(OWNER, new RevisionReference(null));
        assertThat(result).contains(RevisionStructure.legacy());
        verifyNoInteractions(repository);
    }

    @Test
    void absentAndWrongOwnerReadAreIndistinguishableAndMetricsContainNoIds() {
        var repository = mock(QuizRevisionRepository.class);
        var revision = QuizRevisionSectionsTest.structured("read");
        when(repository.findById(OWNER, revision.id())).thenReturn(Optional.of(revision));
        when(repository.findById(id("other"), revision.id())).thenReturn(Optional.empty());
        when(repository.findById(OWNER, id("missing"))).thenReturn(Optional.empty());
        var service = new QuizRevisionStructureServiceImpl(repository, registry);
        assertThat(service.read(OWNER, new RevisionReference(revision.id()))).contains(RevisionStructure.from(revision));
        assertThat(service.read(id("other"), new RevisionReference(revision.id()))).isEmpty();
        assertThat(service.read(OWNER, new RevisionReference(id("missing")))).isEmpty();
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("outcome", "version");
            assertThat(meter.getId().toString()).doesNotContain(revision.id().toString(), OWNER.toString());
        });
    }

    @Test
    void invalidInputAndPersistenceFailureAreBoundedAndTelemetryCannotReplaceSuccess() {
        var repository = mock(QuizRevisionRepository.class);
        var metrics = mock(MeterRegistry.class);
        var revision = quiz("flat");
        when(repository.findById(OWNER, revision.id())).thenReturn(Optional.of(revision));
        var service = new QuizRevisionStructureServiceImpl(repository, metrics);
        assertThat(service.read(OWNER, new RevisionReference(revision.id()))).contains(RevisionStructure.from(revision));
        assertThatThrownBy(() -> service.read(null, new RevisionReference(revision.id()))).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> service.read(OWNER, null)).isInstanceOf(RevisionIntegrityException.class);
        when(repository.findById(OWNER, revision.id())).thenThrow(new DataAccessResourceFailureException("private-driver-detail"));
        assertThatThrownBy(() -> service.read(OWNER, new RevisionReference(revision.id())))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("PERSISTENCE_UNAVAILABLE").hasNoCause();
    }
}
