package uk.gegc.quizmaker.features.quiz.domain.model;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class QuizGenerationWorkTest {
    @Test void authorityExpiresAtTheBoundaryAndCannotBeClaimedMoreThanTwice() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 12, 0);
        var work = new QuizGenerationWork(UUID.randomUUID(), 8, now);
        assertThat(work.isDue(now)).isTrue();
        long first = work.claim(now, 120);
        assertThat(work.owns(first, now.plusSeconds(119))).isTrue();
        assertThat(work.owns(first, now.plusSeconds(120))).isFalse();
        assertThat(work.owns(first + 1, now)).isFalse();
        assertThatThrownBy(() -> work.claim(now, 120)).isInstanceOf(IllegalStateException.class);
        long second = work.claim(now.plusSeconds(120), 120);
        assertThat(second).isEqualTo(first + 1);
        assertThat(work.owns(first, now.plusSeconds(120))).isFalse();
        work.heartbeat(now.plusSeconds(150), 120);
        assertThat(work.getLeaseUntil()).isEqualTo(now.plusSeconds(270));
        assertThatThrownBy(() -> work.claim(now.plusSeconds(270), 120)).isInstanceOf(IllegalStateException.class);
        work.finish();
        assertThat(work.isDue(now.plusYears(1))).isFalse();
        assertThat(work.getLeaseUntil()).isNull();
    }
}
