package uk.gegc.quizmaker.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.SAME_THREAD)
public class JwtTokenServiceTest {

    private final long accessTokenValidityInMs = 15 * 60 * 1000;
    private final long refreshTokenValidityInMs = 7 * 24 * 60 * 60 * 1000;
    private JwtTokenService jwtTokenService;
    private UserRepository userRepository;
    private SecretKey secretKey;
    private String base64Secret;
    private ListAppender<ILoggingEvent> logWatcher;
    private Logger jwtProviderLogger;
    private final Map<String, LocalDateTime> passwordVersionStore = new ConcurrentHashMap<>();
    private final Map<String, UUID> userIds = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        secretKey = Jwts.SIG.HS256.key().build();
        base64Secret = Base64.getEncoder().encodeToString(secretKey.getEncoded());

        passwordVersionStore.clear();
        userIds.clear();
        userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername(anyString()))
                .thenAnswer(invocation -> {
                    String username = invocation.getArgument(0);
                    LocalDateTime passwordChangedAt = passwordVersionStore.computeIfAbsent(
                            username,
                            key -> LocalDateTime.now().minusMinutes(5)
                    );
                    uk.gegc.quizmaker.features.user.domain.model.User user = new uk.gegc.quizmaker.features.user.domain.model.User();
                    user.setUsername(username);
                    user.setId(userIds.computeIfAbsent(username, key -> UUID.nameUUIDFromBytes(key.getBytes())));
                    user.setPasswordChangedAt(passwordChangedAt);
                    return java.util.Optional.of(user);
                });

        jwtTokenService = new JwtTokenService(null, userRepository);
        ReflectionTestUtils.setField(jwtTokenService, "base64secret", base64Secret);
        ReflectionTestUtils.setField(jwtTokenService, "accessTokenValidityInMs", accessTokenValidityInMs);
        ReflectionTestUtils.setField(jwtTokenService, "refreshTokenValidityInMs", refreshTokenValidityInMs);

        jwtTokenService.init();
        
        // Set up log capture
        jwtProviderLogger = (Logger) LoggerFactory.getLogger(JwtTokenService.class);
        jwtProviderLogger.setLevel(Level.DEBUG); // Ensure we capture all log levels
        logWatcher = new ListAppender<>();
        logWatcher.start();
        jwtProviderLogger.addAppender(logWatcher);
    }

    @AfterEach
    void tearDown() {
        if (jwtProviderLogger != null && logWatcher != null) {
            jwtProviderLogger.detachAppender(logWatcher);
        }
        if (logWatcher != null) {
            logWatcher.stop();
        }
    }

    @Test
    @DisplayName("generateAccessToken: valid Authentication produces JWT with type=access, correct subject & TTL")
    void generateAccessToken_happyPath_containsAccessTypeAndSubjectAndExpiry() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "Alice", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        UUID sessionId = UUID.randomUUID();
        String token = jwtTokenService.generateAccessToken(authentication, sessionId);
        assertThat(token).isNotBlank();

        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("Alice");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        assertThat(claims.get("sid", String.class)).isEqualTo(sessionId.toString());
        assertThat(claims.get("uid", String.class)).isEqualTo(userIds.get("Alice").toString());

        Date issuedAt = claims.getIssuedAt();
        Date expirationDate = claims.getExpiration();

        assertThat(expirationDate.getTime() - issuedAt.getTime()).isEqualTo(accessTokenValidityInMs);
        assertThat(issuedAt).isBeforeOrEqualTo(new Date());
    }

    @Test
    @DisplayName("generateAccessToken: two quick calls yield different issuedAt & expiration timestamps")
    void generateAccessToken_edge_twoCalls_differentIssuedAtAndExpiry() throws InterruptedException {
        Authentication authentication = new UsernamePasswordAuthenticationToken("Bob", null, List.of());

        UUID sessionId = UUID.randomUUID();
        String token1 = jwtTokenService.generateAccessToken(authentication, sessionId);
        Thread.sleep(1000);
        String token2 = jwtTokenService.generateAccessToken(authentication, sessionId);

        assertThat(token1).isNotEqualTo(token2);

        Claims claims1 = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token1).getPayload();
        Claims claims2 = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token2).getPayload();

        assertThat(claims2.getIssuedAt()).isAfter(claims1.getIssuedAt());
        assertThat(claims2.getExpiration()).isAfter(claims1.getExpiration());
    }

    @Test
    @DisplayName("generateRefreshToken: valid Authentication produces JWT with type=refresh, correct subject & TTL")
    void generateRefreshToken_happyPath_containsRefreshTypeAndSubjectAndExpiry() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("Carol", null, List.of());

        UUID sessionId = UUID.randomUUID();
        String token = jwtTokenService.generateRefreshToken(
                authentication,
                sessionId,
                new Date(System.currentTimeMillis() + refreshTokenValidityInMs)
        );
        assertThat(token).isNotBlank();

        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("Carol");
        assertThat(claims.get("type")).isEqualTo("refresh");
        assertThat(claims.get("sid", String.class)).isEqualTo(sessionId.toString());

        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        assertThat(expiration.getTime() - issuedAt.getTime()).isEqualTo(refreshTokenValidityInMs);
        assertThat(issuedAt).isBeforeOrEqualTo(new Date());
    }

    @Test
    @DisplayName("validateToken: valid access token returns true")
    void validateToken_happyPath_validTokenReturnsTrue() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("John", null, List.of());
        String validToken = jwtTokenService.generateAccessToken(authentication, UUID.randomUUID());

        boolean result = jwtTokenService.validateToken(validToken);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("purpose validation accepts only the token type requested by the caller")
    void validateTokenForPurpose_rejectsOppositeTokenType() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("Purpose", null, List.of());
        UUID sessionId = UUID.randomUUID();
        String accessToken = jwtTokenService.generateAccessToken(authentication, sessionId);
        String refreshToken = jwtTokenService.generateRefreshToken(
                authentication,
                sessionId,
                new Date(System.currentTimeMillis() + refreshTokenValidityInMs)
        );

        assertThat(jwtTokenService.validateAccessToken(accessToken)).isPresent();
        assertThat(jwtTokenService.validateRefreshToken(accessToken)).isEmpty();
        assertThat(jwtTokenService.validateRefreshToken(refreshToken)).isPresent();
        assertThat(jwtTokenService.validateAccessToken(refreshToken)).isEmpty();
    }

    @Test
    @DisplayName("access validation rejects a correctly signed legacy token without a session identity")
    void validateAccessToken_legacyTokenWithoutSessionIdentity_returnsEmpty() {
        Date issuedAt = new Date();
        String legacyAccessToken = Jwts.builder()
                .subject("Legacy")
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + accessTokenValidityInMs))
                .claim("type", "access")
                .claim("pwdChangedAt", passwordVersionStore
                        .computeIfAbsent("Legacy", ignored -> LocalDateTime.now().minusMinutes(5))
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli())
                .signWith(secretKey)
                .compact();

        assertThat(jwtTokenService.validateAccessToken(legacyAccessToken)).isEmpty();
    }

    @Test
    @DisplayName("validateToken: rejects token if passwordChangedAt is newer than claim")
    void validateToken_passwordChangedAfterIssuance_returnsFalse() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("Versioned", null, List.of());
        String token = jwtTokenService.generateAccessToken(authentication, UUID.randomUUID());

        passwordVersionStore.put("Versioned", LocalDateTime.now().plusMinutes(1));

        assertThat(jwtTokenService.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("validateToken: malformed token returns false")
    void validateToken_sad_malformedTokenReturnsFalse() {
        String badToken = "not.a.token";
        assertThat(jwtTokenService.validateToken(badToken)).isFalse();
    }

    @Test
    @DisplayName("validateToken: expired token returns false")
    void validateToken_sad_expiredTokenReturnsFalse() {
        Date past = new Date(System.currentTimeMillis() - 1000);
        String expired = Jwts.builder()
                .subject("Eve")
                .issuedAt(past)
                .expiration(past)
                .claim("type", "access")
                .signWith(secretKey)
                .compact();

        assertThat(jwtTokenService.validateToken(expired)).isFalse();
    }

    @Test
    @DisplayName("validateToken: token signed with wrong key returns false")
    void validateToken_sad_wrongSignatureReturnsFalse() {
        SecretKey wrongKey = Jwts.SIG.HS256.key().build();
        Date now = new Date();
        String badSignature = Jwts.builder()
                .subject("Frank")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenValidityInMs))
                .claim("type", "access")
                .signWith(wrongKey)
                .compact();

        assertThat(jwtTokenService.validateToken(badSignature)).isFalse();
    }

    @Test
    @DisplayName("getUsername: valid token returns correct subject")
    void getUsername_happyPath_returnsCorrectUsername() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("Lenny", null, List.of());

        String token = jwtTokenService.generateRefreshToken(
                authentication,
                UUID.randomUUID(),
                new Date(System.currentTimeMillis() + refreshTokenValidityInMs)
        );
        String username = jwtTokenService.getUsername(token);
        assertThat(username).isEqualTo("Lenny");
    }

    @Test
    @DisplayName("getUsername: invalid token throws JwtException")
    void getUsername_sad_invalidTokenThrows() {
        String badToken = "not.a.token";
        assertThatThrownBy(() -> jwtTokenService.getUsername(badToken)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("getAuthentication: valid token returns Authentication with principal and authorities")
    void getAuthentication_happyPath_returnsAuthToken() {
        UserDetailsService userDetailsService = username -> new User(username, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        JwtTokenService provider = new JwtTokenService(userDetailsService, userRepository);
        ReflectionTestUtils.setField(provider, "base64secret", base64Secret);
        ReflectionTestUtils.setField(provider, "accessTokenValidityInMs", accessTokenValidityInMs);
        ReflectionTestUtils.setField(provider, "refreshTokenValidityInMs", refreshTokenValidityInMs);
        provider.init();

        Authentication initial = new UsernamePasswordAuthenticationToken("Bill", null, List.of());
        String token = provider.generateAccessToken(initial, UUID.randomUUID());

        Authentication result = provider.getAuthentication(token);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Bill");
        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("getAuthentication: invalid token throws JwtException")
    void getAuthentication_sad_invalidTokenThrows() {
        UserDetailsService userDetailsService = username -> new User(username, "", List.of());
        JwtTokenService provider = new JwtTokenService(userDetailsService, userRepository);
        ReflectionTestUtils.setField(provider, "base64secret", base64Secret);
        ReflectionTestUtils.setField(provider, "accessTokenValidityInMs", accessTokenValidityInMs);
        ReflectionTestUtils.setField(provider, "refreshTokenValidityInMs", refreshTokenValidityInMs);
        provider.init();

        String badToken = "not.a.token";

        assertThatThrownBy(() -> jwtTokenService.getAuthentication(badToken)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("validateToken: expired token logs appropriate debug message")
    void validateToken_expiredToken_logsDebugMessage() {
        // Create a token that was valid but is now expired
        Date past = new Date(System.currentTimeMillis() - 10000); // 10 seconds ago
        Date expiration = new Date(System.currentTimeMillis() - 5000); // 5 seconds ago
        String expired = Jwts.builder()
                .subject("ExpiredUser")
                .issuedAt(past)
                .expiration(expiration)
                .claim("type", "access")
                .signWith(secretKey)
                .compact();

        boolean result = jwtTokenService.validateToken(expired);
        
        assertThat(result).isFalse();
        
        // Debug: Print all captured logs
        System.out.println("Captured logs: " + logWatcher.list.size());
        logWatcher.list.forEach(event -> 
            System.out.println("Log: " + event.getLevel() + " - " + event.getMessage())
        );
        
        assertThat(logWatcher.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getMessage)
                .anyMatch(tuple -> tuple.toList().equals(List.of(Level.DEBUG, "JWT token is expired: {}")));
    }

    @Test 
    @DisplayName("validateToken: malformed token logs appropriate warn message")
    void validateToken_malformedToken_logsWarnMessage() {
        String malformedToken = "not.a.valid.jwt.token";
        
        boolean result = jwtTokenService.validateToken(malformedToken);
        
        assertThat(result).isFalse();
        assertThat(logWatcher.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getMessage)
                .anyMatch(tuple -> tuple.toList().equals(List.of(Level.WARN, "Malformed JWT token received: {}")));
    }

    @Test
    @DisplayName("validateToken: invalid signature logs appropriate warn message") 
    void validateToken_invalidSignature_logsWarnMessage() {
        SecretKey wrongKey = Jwts.SIG.HS256.key().build();
        Date now = new Date();
        String invalidSignatureToken = Jwts.builder()
                .subject("TestUser")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenValidityInMs))
                .claim("type", "access")
                .signWith(wrongKey)
                .compact();

        boolean result = jwtTokenService.validateToken(invalidSignatureToken);
        
        assertThat(result).isFalse();
        assertThat(logWatcher.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getMessage)
                .anyMatch(tuple -> tuple.toList().equals(List.of(Level.WARN, "Invalid JWT signature detected: {}")));
    }
}
