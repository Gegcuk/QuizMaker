package uk.gegc.quizmaker.features.result.domain.repository;

import java.util.*;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService.*;

public interface ReleaseConfigurationRepository {
    Optional<Publication> current(UUID quiz, UUID revision);
    Optional<Publication> read(UUID quiz, UUID revision, UUID id);
    Optional<Publication> readBound(UUID owner, UUID quiz, UUID revision, UUID id);
    Optional<Receipt> receipt(Command command, UUID actor);
    void insert(Publication publication, UUID actor);
    void pointTo(Publication publication);
    void record(Command command, UUID actor, String requestHash, Publication publication, java.time.Instant recordedAt);
    record Receipt(String requestHash, Publication publication) { }
}
