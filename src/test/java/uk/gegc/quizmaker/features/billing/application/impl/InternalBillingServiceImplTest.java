package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalBillingServiceImplTest {

    @Mock
    private BillingServiceImpl billingServiceImpl;

    private InternalBillingService internalBillingService;

    @BeforeEach
    void setUp() {
        internalBillingService = new InternalBillingServiceImpl(billingServiceImpl);
    }

    @Test
    void reserveDelegatesToBillingServiceImpl() {
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        ReservationDto reservation = new ReservationDto(
                reservationId,
                userId,
                ReservationState.ACTIVE,
                120L,
                0L,
                LocalDateTime.now().plusMinutes(30),
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(billingServiceImpl.reserveInternal(eq(userId), eq(120L), eq("quiz"), eq("idem"))).thenReturn(reservation);

        ReservationDto result = internalBillingService.reserve(userId, 120L, "quiz", "idem");

        assertThat(result).isSameAs(reservation);
        verify(billingServiceImpl).reserveInternal(userId, 120L, "quiz", "idem");
    }

    @Test
    void commitDelegatesToBillingServiceImpl() {
        UUID reservationId = UUID.randomUUID();
        CommitResultDto commit = new CommitResultDto(reservationId, 80L, 20L);
        when(billingServiceImpl.commitInternal(eq(reservationId), eq(80L), eq("quiz"), eq("commit-key"))).thenReturn(commit);

        CommitResultDto result = internalBillingService.commit(reservationId, 80L, "quiz", "commit-key");

        assertThat(result).isSameAs(commit);
        verify(billingServiceImpl).commitInternal(reservationId, 80L, "quiz", "commit-key");
    }

    @Test
    void releaseDelegatesToBillingServiceImpl() {
        UUID reservationId = UUID.randomUUID();
        ReleaseResultDto release = new ReleaseResultDto(reservationId, 40L);
        when(billingServiceImpl.releaseInternal(eq(reservationId), eq("reason"), eq("quiz"), eq("release-key"))).thenReturn(release);

        ReleaseResultDto result = internalBillingService.release(reservationId, "reason", "quiz", "release-key");

        assertThat(result).isSameAs(release);
        verify(billingServiceImpl).releaseInternal(reservationId, "reason", "quiz", "release-key");
    }

    @Test
    void creditPurchaseDelegatesToBillingServiceImpl() {
        UUID userId = UUID.randomUUID();

        internalBillingService.creditPurchase(userId, 500L, "idem", "ref", "meta");

        verify(billingServiceImpl).creditPurchase(userId, 500L, "idem", "ref", "meta");
    }

    @Test
    void deductTokensDelegatesToBillingServiceImpl() {
        UUID userId = UUID.randomUUID();

        internalBillingService.deductTokens(userId, 250L, "idem", "ref", "meta");

        verify(billingServiceImpl).deductTokens(userId, 250L, "idem", "ref", "meta");
    }
}
