package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.RedirectStrategy;

import java.io.IOException;

/** Sends credential-bearing redirects without framework logging of the target URL. */
final class SecretSafeRedirectStrategy implements RedirectStrategy {

    @Override
    public void sendRedirect(
            HttpServletRequest request,
            HttpServletResponse response,
            String url
    ) throws IOException {
        response.sendRedirect(response.encodeRedirectURL(url));
    }
}
