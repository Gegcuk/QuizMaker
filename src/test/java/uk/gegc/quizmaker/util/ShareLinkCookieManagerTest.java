package uk.gegc.quizmaker.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareLinkCookieManagerTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private ShareLinkCookieManager cookieManager;

    @BeforeEach
    void setUp() {
        cookieManager = new ShareLinkCookieManager();
        ReflectionTestUtils.setField(cookieManager, "cookieTtlSeconds", 3600);
    }

    @Test
    @DisplayName("setShareLinkCookie: sets secure cookie with correct attributes")
    void setShareLinkCookie_setsSecureCookie() {
        String token = "test-token-12345678901234567890123456789012345678901";
        UUID quizId = UUID.randomUUID();

        cookieManager.setShareLinkCookie(response, token, quizId);

        verify(response).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("getShareLinkToken: returns token when cookie exists")
    void getShareLinkToken_returnsTokenWhenExists() {
        String expectedToken = "test-token-12345678901234567890123456789012345678901";
        Cookie cookie = new Cookie("share_token", expectedToken);
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});

        Optional<String> result = cookieManager.getShareLinkToken(request);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(expectedToken);
    }

    @Test
    @DisplayName("getShareLinkToken: returns empty when no cookies")
    void getShareLinkToken_returnsEmptyWhenNoCookies() {
        when(request.getCookies()).thenReturn(null);

        Optional<String> result = cookieManager.getShareLinkToken(request);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getShareLinkToken: returns empty when share_token cookie not found")
    void getShareLinkToken_returnsEmptyWhenShareTokenNotFound() {
        Cookie otherCookie = new Cookie("other_cookie", "value");
        when(request.getCookies()).thenReturn(new Cookie[]{otherCookie});

        Optional<String> result = cookieManager.getShareLinkToken(request);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("clearShareLinkCookie: clears cookie with correct attributes")
    void clearShareLinkCookie_clearsCookie() {
        cookieManager.clearShareLinkCookie(response);

        verify(response).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("invalidateCookiesForQuiz: invalidates cookies for specific quiz")
    void invalidateCookiesForQuiz_invalidatesCookies() {
        UUID quizId = UUID.randomUUID();

        cookieManager.invalidateCookiesForQuiz(response, quizId);

        verify(response).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("getCookieTtlSeconds: returns configured TTL")
    void getCookieTtlSeconds_returnsConfiguredTtl() {
        int result = cookieManager.getCookieTtlSeconds();

        assertThat(result).isEqualTo(3600);
    }

    @Test
    @DisplayName("setShareLinkCookie: handles null token gracefully")
    void setShareLinkCookie_handlesNullToken() {
        UUID quizId = UUID.randomUUID();

        cookieManager.setShareLinkCookie(response, null, quizId);

        verify(response).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("setShareLinkCookie: handles empty token gracefully")
    void setShareLinkCookie_handlesEmptyToken() {
        UUID quizId = UUID.randomUUID();

        cookieManager.setShareLinkCookie(response, "", quizId);

        verify(response).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("getShareLinkToken: handles empty cookies array")
    void getShareLinkToken_handlesEmptyCookiesArray() {
        when(request.getCookies()).thenReturn(new Cookie[0]);

        Optional<String> result = cookieManager.getShareLinkToken(request);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getShareLinkToken: finds correct cookie among multiple cookies")
    void getShareLinkToken_findsCorrectCookieAmongMultiple() {
        String expectedToken = "test-token-12345678901234567890123456789012345678901";
        Cookie shareTokenCookie = new Cookie("share_token", expectedToken);
        Cookie otherCookie1 = new Cookie("other_cookie1", "value1");
        Cookie otherCookie2 = new Cookie("other_cookie2", "value2");
        
        when(request.getCookies()).thenReturn(new Cookie[]{otherCookie1, shareTokenCookie, otherCookie2});

        Optional<String> result = cookieManager.getShareLinkToken(request);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(expectedToken);
    }
}
