package uk.gegc.quizmaker.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Execution(ExecutionMode.SAME_THREAD)
public class JwtAuthenticationFilterTest {

    @Mock
    HttpServletRequest httpServletRequest;

    @Mock
    HttpServletResponse httpServletResponse;

    @Mock
    FilterChain filterChain;

    @Mock
    JwtTokenService jwtTokenService;

    JwtAuthenticationFilter authenticationFilter;
    private ListAppender<ILoggingEvent> logWatcher;
    private Logger filterLogger;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authenticationFilter = new JwtAuthenticationFilter(jwtTokenService);
        SecurityContextHolder.clearContext();
        
        // Set up log capture
        filterLogger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
        filterLogger.setLevel(Level.DEBUG); // Ensure we capture all log levels
        logWatcher = new ListAppender<>();
        logWatcher.start();
        filterLogger.addAppender(logWatcher);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        filterLogger.detachAppender(logWatcher);
    }

    @Test
    @DisplayName("No authorizetion header -> filterchain invoked, no Authentication set")
    void noHeader_shouldNotSetAuthentication() throws ServletException, IOException {
        when(httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        authenticationFilter.doFilterInternal(httpServletRequest, httpServletResponse, filterChain);

        verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Bearer valid-token → filterChain invoked, Authentication set to returned value")
    void validBearer_shouldSetAuthentication() throws ServletException, IOException {
        String token = "valid-token";
        Authentication authentication = mock(Authentication.class);

        when(httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        when(jwtTokenService.validateToken(token)).thenReturn(true);
        when(jwtTokenService.getAuthentication(token)).thenReturn(authentication);

        authenticationFilter.doFilterInternal(httpServletRequest, httpServletResponse, filterChain);

        verify(jwtTokenService).validateToken(token);
        verify(jwtTokenService).getAuthentication(token);
        verify(filterChain).doFilter(httpServletRequest, httpServletResponse);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isSameAs(authentication);
    }

    @Test
    @DisplayName("Bearer invalid-token → filterChain invoked, no Authentication set")
    void invalidBearer_shouldNotSetAuthentication() throws ServletException, IOException {
        String token = "invalid-token";

        when(httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        when(jwtTokenService.validateToken(token)).thenReturn(false);

        authenticationFilter.doFilterInternal(httpServletRequest, httpServletResponse, filterChain);

        verify(jwtTokenService).validateToken(token);
        verify(jwtTokenService, never()).getAuthentication(anyString());
        verify(filterChain).doFilter(httpServletRequest, httpServletResponse);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Wrong prefix (‘Token abc’) → filterChain invoked, no Authentication set")
    void wrongPrefix_shouldNotSetAuthentication() throws ServletException, IOException {
        when(httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Token abc");

        authenticationFilter.doFilterInternal(httpServletRequest, httpServletResponse, filterChain);

        verify(jwtTokenService, never()).validateToken(anyString());
        verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Bearer valid-token → logs successful authentication at DEBUG level")
    void validBearer_shouldLogSuccessfulAuthentication() throws ServletException, IOException {
        String token = "valid-token";
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("testuser");

        when(httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        when(jwtTokenService.validateToken(token)).thenReturn(true);
        when(jwtTokenService.getAuthentication(token)).thenReturn(authentication);

        authenticationFilter.doFilterInternal(httpServletRequest, httpServletResponse, filterChain);

        // Debug: Print all captured logs
        System.out.println("Filter captured logs: " + logWatcher.list.size());
        logWatcher.list.forEach(event -> 
            System.out.println("Filter Log: " + event.getLevel() + " - " + event.getMessage())
        );

        assertThat(logWatcher.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getMessage)
                .anyMatch(tuple -> tuple.toList().equals(List.of(Level.DEBUG, "Successfully authenticated user: {}")));
    }

    @Test
    @DisplayName("Bearer invalid-token → logs security warning with request details")
    void invalidBearer_shouldLogSecurityWarning() throws ServletException, IOException {
        String token = "invalid-token";
        String clientIp = "192.168.1.100";
        String requestUri = "/api/secure-endpoint";
        String userAgent = "TestClient/1.0";

        when(httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        when(httpServletRequest.getRemoteAddr()).thenReturn(clientIp);
        when(httpServletRequest.getRequestURI()).thenReturn(requestUri);
        when(httpServletRequest.getHeader("User-Agent")).thenReturn(userAgent);
        when(jwtTokenService.validateToken(token)).thenReturn(false);

        authenticationFilter.doFilterInternal(httpServletRequest, httpServletResponse, filterChain);

        assertThat(logWatcher.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getMessage)
                .anyMatch(tuple -> tuple.toList().equals(List.of(Level.WARN, "Invalid JWT token received from IP: {}, URI: {}, User-Agent: {}")));
                
        // Verify we logged the security details
        ILoggingEvent warnEvent = logWatcher.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .findFirst()
                .orElse(null);
        
        assertThat(warnEvent).isNotNull();
        assertThat(warnEvent.getArgumentArray()).containsExactly(clientIp, requestUri, userAgent);
    }
}
