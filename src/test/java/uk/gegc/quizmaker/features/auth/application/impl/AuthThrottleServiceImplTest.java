package uk.gegc.quizmaker.features.auth.application.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gegc.quizmaker.features.auth.application.AuthRateLimitStore;
import uk.gegc.quizmaker.features.auth.application.AuthRateLimitStore.Quota;
import uk.gegc.quizmaker.features.auth.config.AuthRateLimitProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.AuthRateLimitUnavailableException;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.auth.infra.security.ValidatedJwt;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthThrottleServiceImplTest {
    @Mock AuthRateLimitStore store;
    @Mock UserRepository users;
    @Mock JwtTokenService tokens;
    @Mock TrustedProxyUtil proxies;
    AuthRateLimitProperties properties;
    SimpleMeterRegistry metrics;
    AuthThrottleServiceImpl service;

    @BeforeEach
    void prepare() {
        properties = new AuthRateLimitProperties();
        properties.setKeySecret("synthetic-test-only-key-material-457");
        properties.validate();
        metrics = new SimpleMeterRegistry();
        service = new AuthThrottleServiceImpl(store, properties, users, tokens, proxies, metrics);
    }

    @Test
    void caseSensitiveUsernameAndEmailShareIndependentAccountBudgetAcrossNetworks() {
        UUID account = UUID.randomUUID();
        when(users.findAuthThrottleIdByUsername(anyString())).thenReturn(Optional.empty());
        when(users.findAuthThrottleIdByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findAuthThrottleIdByUsername("Alice")).thenReturn(Optional.of(account));
        when(users.findAuthThrottleIdByEmail("ALICE@example.test")).thenReturn(Optional.of(account));
        service.login("Alice", "198.51.100.1");
        service.login("ALICE@example.test", "2001:db8::1");
        ArgumentCaptor<List<Quota>> capture = ArgumentCaptor.forClass(List.class);
        verify(store, times(2)).admit(capture.capture(), eq(true));
        List<Quota> first = capture.getAllValues().get(0);
        List<Quota> second = capture.getAllValues().get(1);
        assertThat(find(first, "login-account").digest()).isEqualTo(find(second, "login-account").digest());
        assertThat(find(first, "login-account-network").digest()).isNotEqualTo(find(second, "login-account-network").digest());
        assertThat(find(first, "login-account").requests()).isEqualTo(10);
        assertThat(find(first, "login-network").requests()).isEqualTo(60);
        assertThat(first).allSatisfy(quota -> assertThat(quota.digest()).matches("[a-f0-9]{64}"));
        assertThat(first.toString()).doesNotContain("alice", account.toString(), "198.51.100");
        verify(users).findAuthThrottleIdByEmail("Alice");
        verify(users).findAuthThrottleIdByUsername("ALICE@example.test");
    }

    @Test
    void atomicDenialReturnsGenericWaitAcrossAllApplicableDimensions() {
        when(store.admit(anyList(), eq(true))).thenReturn(17L);
        assertThatThrownBy(() -> service.login("unknown@example.test", "198.51.100.1"))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        error -> assertThat(error.getRetryAfterSeconds()).isEqualTo(17))
                .hasMessage("Too many authentication requests. Please retry later.");
        verify(users).findAuthThrottleIdByUsername("unknown@example.test");
        verify(users).findAuthThrottleIdByEmail("unknown@example.test");
        verifyNoInteractions(tokens);
        assertThat(metrics.get("auth.throttle.requests").tag("outcome", "throttled").counter().count()).isEqualTo(1);
    }

    @Test
    void unavailableStoreFailsClosedWithoutFallback() {
        when(store.admit(anyList(), anyBoolean())).thenThrow(new DataAccessResourceFailureException("synthetic outage"));
        assertThatThrownBy(() -> service.login("alice", "198.51.100.1"))
                .isInstanceOf(AuthRateLimitUnavailableException.class).hasMessageNotContaining("synthetic outage");
        verifyNoInteractions(tokens);
        assertThat(metrics.get("auth.throttle.requests").tag("outcome", "store_error").counter().count()).isEqualTo(1);
    }

    @Test
    void verifiedRefreshUsesStableSessionAndUserAndInvalidRefreshUsesOnlyNetwork() {
        var claims = new ValidatedJwt("alice", UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2030-01-01T00:00:00Z"));
        when(tokens.validateRefreshToken("valid-token")).thenReturn(Optional.of(claims));
        service.refresh("valid-token", "198.51.100.1");
        service.refresh("untrusted-token", "198.51.100.1");
        ArgumentCaptor<List<Quota>> capture = ArgumentCaptor.forClass(List.class);
        verify(store, times(2)).admit(capture.capture(), eq(true));
        assertThat(capture.getAllValues().get(0)).extracting(Quota::dimension)
                .containsExactly("refresh-network", "refresh-session", "refresh-user");
        assertThat(capture.getAllValues().get(1)).extracting(Quota::dimension).containsExactly("refresh-network");
        assertThat(capture.getAllValues().toString()).doesNotContain("valid-token", "untrusted-token", claims.userId().toString());
    }

    @Test
    void registrationAppliesEmailAndIdentityAndBothNetworkWindowsTogether() {
        service.registration("Alice@example.test", "Alice", "198.51.100.1");
        ArgumentCaptor<List<Quota>> capture = ArgumentCaptor.forClass(List.class);
        verify(store).admit(capture.capture(), eq(true));
        assertThat(capture.getValue()).extracting(Quota::dimension).containsExactlyInAnyOrder(
                "registration-email", "registration-identity", "registration-network-burst", "registration-network-sustained");
        assertThat(find(capture.getValue(), "registration-network-sustained").requests()).isEqualTo(60);
        assertThat(find(capture.getValue(), "registration-email").window().toSeconds()).isEqualTo(3600);
    }

    @Test
    void providerSignupUsesOpaqueSubjectWithoutLowercasingAndRequiresRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(proxies.getClientIp(request)).thenReturn("198.51.100.1");
        try {
            service.providerRegistration("GOOGLE", "CaseSensitiveID", "alice@example.test");
            service.providerRegistration("GOOGLE", "casesensitiveid", "alice@example.test");
            ArgumentCaptor<List<Quota>> capture = ArgumentCaptor.forClass(List.class);
            verify(store, times(2)).admit(capture.capture(), eq(true));
            assertThat(find(capture.getAllValues().get(0), "registration-identity").digest())
                    .isNotEqualTo(find(capture.getAllValues().get(1), "registration-identity").digest());
        } finally { RequestContextHolder.resetRequestAttributes(); }
        assertThatThrownBy(() -> service.providerRegistration("GOOGLE", "subject", null))
                .isInstanceOf(AuthRateLimitUnavailableException.class);
    }

    @Test
    void configurationOverridesDefaultWithoutChangingFixedRecoverySemantics() {
        properties.getLimits().get("login-account").setRequests(14);
        properties.getLimits().get("login-account").setWindowSeconds(90L);
        service.login("alice", "198.51.100.1");
        service.existing("verify-email", "198.51.100.1");
        ArgumentCaptor<List<Quota>> capture = ArgumentCaptor.forClass(List.class);
        verify(store, times(2)).admit(capture.capture(), eq(true));
        assertThat(find(capture.getAllValues().get(0), "login-account").requests()).isEqualTo(14);
        assertThat(find(capture.getAllValues().get(0), "login-account").window().toSeconds()).isEqualTo(90);
        assertThat(capture.getAllValues().get(1).get(0).rolling()).isFalse();
        assertThat(capture.getAllValues().get(1).get(0).requests()).isEqualTo(10);
    }

    @Test
    void unknownAccountsUseSameQuotaShapeAndBoundedMetricLabels() {
        service.login("missing@example.test", "198.51.100.1");
        ArgumentCaptor<List<Quota>> capture = ArgumentCaptor.forClass(List.class);
        verify(store).admit(capture.capture(), eq(true));
        assertThat(capture.getValue()).hasSize(3);
        verify(users).findAuthThrottleIdByUsername("missing@example.test");
        verify(users).findAuthThrottleIdByEmail("missing@example.test");
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                .doesNotContain("missing", "198.51.100", "@").contains("login", "allowed"));
    }

    static Quota find(List<Quota> quotas, String dimension) {
        return quotas.stream().filter(quota -> quota.dimension().equals(dimension)).findFirst().orElseThrow();
    }
}
