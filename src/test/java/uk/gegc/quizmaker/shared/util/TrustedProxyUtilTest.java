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
}
