package uk.gegc.quizmaker.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Trusted proxy client IP selection")
class TrustedProxyUtilTest {

    @Test
    @DisplayName("a native-proxy resolved client IP cannot be replaced by leftover spoofed input")
    void resolvedRemoteAddress_winsOverSpoofedForwardedHeader() {
        TrustedProxyUtil trustedProxyUtil = new TrustedProxyUtil();
        ReflectionTestUtils.setField(trustedProxyUtil, "enableForwardedHeaders", true);
        ReflectionTestUtils.setField(trustedProxyUtil, "trustedProxiesConfig", "127.0.0.1,::1,localhost");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.42");
        request.addHeader("X-Forwarded-For", "198.51.100.77");

        assertThat(trustedProxyUtil.getClientIp(request)).isEqualTo("203.0.113.42");
    }

    @Test
    void attackerPrependingHeaderCannotOverrideFirstUntrustedHop() {
        TrustedProxyUtil utility = configured("127.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.77, 203.0.113.42");
        assertThat(utility.getClientIp(request)).isEqualTo("203.0.113.42");
    }

    @Test
    void exactTrustDoesNotTrustAnotherAddressWithMatchingStringPrefix() {
        TrustedProxyUtil utility = configured("10.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "198.51.100.77");
        assertThat(utility.getClientIp(request)).isEqualTo("10.0.0.10");
    }

    @Test
    void explicitCidrAndIpv6LiteralsAreSupportedWithoutDnsResolution() {
        TrustedProxyUtil utility = configured("10.0.0.0/24");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.4");
        request.addHeader("X-Forwarded-For", "2001:0db8:0:0:0:0:0:1");
        assertThat(utility.getClientIp(request)).isEqualTo("2001:db8:0:0:0:0:0:1");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "attacker.invalid");
        assertThat(utility.getClientIp(request)).isEqualTo("10.0.0.4");
    }

    @Test
    void malformedOrOversizedForwardingChainFallsBackToSocketPeer() {
        for (String header : new String[]{"198.51.100.1,,10.0.0.1", "[::1]:80", "a".repeat(2049)}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", header);
            assertThat(configured("127.0.0.1,10.0.0.1").getClientIp(request)).isEqualTo("127.0.0.1");
        }
    }

    private TrustedProxyUtil configured(String peers) {
        TrustedProxyUtil utility = new TrustedProxyUtil();
        ReflectionTestUtils.setField(utility, "enableForwardedHeaders", true);
        ReflectionTestUtils.setField(utility, "trustedProxiesConfig", peers);
        return utility;
    }
}
