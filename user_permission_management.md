@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    PermissionName[] value();

    LogicalOperator operator() default LogicalOperator.OR;

    enum LogicalOperator {
        AND, OR
    }
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireResourceOwnership {
    String resourceParam();

    String resourceType();

    String ownerField() default "userId";
}

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    RoleName[] value();

    LogicalOperator operator() default LogicalOperator.OR;

    enum LogicalOperator {
        AND, OR
    }
}

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionAspect {

    private final PermissionEvaluator permissionEvaluator;

    @Before("@annotation(requirePermission)")
    public void checkPermission(JoinPoint joinPoint, RequirePermission requirePermission) {
        PermissionName[] requiredPermissions = requirePermission.value();
        RequirePermission.LogicalOperator operator = requirePermission.operator();

        boolean hasAccess = false;

        if (operator == RequirePermission.LogicalOperator.OR) {
            hasAccess = permissionEvaluator.hasAnyPermission(requiredPermissions);
        } else if (operator == RequirePermission.LogicalOperator.AND) {
            hasAccess = permissionEvaluator.hasAllPermissions(requiredPermissions);
        }

        if (!hasAccess) {
            log.warn("Access denied: User lacks required permissions. Required: {}, Operator: {}",
                    requiredPermissions, operator);
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    @Before("@annotation(requireRole)")
    public void checkRole(JoinPoint joinPoint, RequireRole requireRole) {
        RoleName[] requiredRoles = requireRole.value();
        RequireRole.LogicalOperator operator = requireRole.operator();

        boolean hasAccess = false;

        if (operator == RequireRole.LogicalOperator.OR) {
            hasAccess = permissionEvaluator.hasAnyRole(requiredRoles);
        } else if (operator == RequireRole.LogicalOperator.AND) {
            hasAccess = permissionEvaluator.hasAllRoles(requiredRoles);
        }

        if (!hasAccess) {
            log.warn("Access denied: User lacks required roles. Required: {}, Operator: {}",
                    requiredRoles, operator);
            throw new ForbiddenException("Insufficient roles to access this resource");
        }
    }

    @Before("@annotation(requireResourceOwnership)")
    public void checkResourceOwnership(JoinPoint joinPoint, RequireResourceOwnership requireResourceOwnership) {
        String resourceParam = requireResourceOwnership.resourceParam();
        String resourceType = requireResourceOwnership.resourceType();
        String ownerField = requireResourceOwnership.ownerField();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        UUID resourceOwnerId = null;

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(resourceParam)) {
                Object resourceValue = args[i];

                if (resourceValue instanceof UUID) {
                    resourceOwnerId = (UUID) resourceValue;
                } else if (resourceValue != null) {
                    try {
                        Method ownerMethod = resourceValue.getClass().getMethod("get" +
                                capitalize(ownerField));
                        Object owner = ownerMethod.invoke(resourceValue);
                        if (owner instanceof UUID) {
                            resourceOwnerId = (UUID) owner;
                        }
                    } catch (Exception e) {
                        log.warn("Could not extract owner ID from resource parameter: {}", e.getMessage());
                    }
                }
                break;
            }
        }

        if (resourceOwnerId == null) {
            log.warn("Could not determine resource owner for ownership check");
            throw new UnauthorizedException("Could not verify resource ownership");
        }

        if (!permissionEvaluator.isResourceOwner(resourceOwnerId)) {
            log.warn("Access denied: User is not the owner of the resource. Resource type: {}, Resource owner: {}",
                    resourceType, resourceOwnerId);
            throw new ForbiddenException("You can only access your own " + resourceType);
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}

@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtTokenProvider.validateToken(token)) {
                Authentication authentication = jwtTokenProvider.getAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Successfully authenticated user: {}", authentication.getName());
            } else {
                log.warn("Invalid JWT token received from IP: {}, URI: {}, User-Agent: {}", 
                    request.getRemoteAddr(), 
                    request.getRequestURI(),
                    request.getHeader("User-Agent"));
            }
        }

        filterChain.doFilter(request, response);
    }
}

@Component
@RequiredArgsConstructor
@Getter
@Slf4j
public class JwtTokenProvider {

    private final QuizUserDetailsService quizUserDetailsService;
    private final UserDetailsService userDetailsService;

    @Value("${jwt.secret}")
    private String base64secret;

    @Value("${jwt.access-expiration-ms}")
    private long accessTokenValidityInMs;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshTokenValidityInMs;

    private SecretKey key;

    @PostConstruct
    public void init() {
        byte[] keyBytes = Decoders.BASE64.decode(base64secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Authentication authentication) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenValidityInMs);

        return Jwts.builder()
                .subject(authentication.getName())
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", "access")
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Authentication authentication) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenValidityInMs);

        return Jwts.builder()
                .subject(authentication.getName())
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", "refresh")
                .signWith(key)
                .compact();
    }

    public Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String username = claims.getSubject();
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return true;
        } catch (ExpiredJwtException ex) {
            log.debug("JWT token is expired: {}", ex.getMessage());
            return false;
        } catch (MalformedJwtException ex) {
            log.warn("Malformed JWT token received: {}", ex.getMessage());
            return false;
        } catch (SignatureException ex) {
            log.warn("Invalid JWT signature detected: {}", ex.getMessage());
            return false;
        } catch (IllegalArgumentException ex) {
            log.warn("Illegal argument passed to JWT parser: {}", ex.getMessage());
            return false;
        } catch (JwtException ex) {
            log.error("Unexpected JWT exception: {}", ex.getMessage());
            return false;
        }
    }

    public String getUsername(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionEvaluator {

    private final UserRepository userRepository;

    public boolean hasPermission(PermissionName permission) {
        return hasPermission(getCurrentUser(), permission);
    }

    public boolean hasPermission(User user, PermissionName permission) {
        if (user == null) {
            return false;
        }

        return user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .anyMatch(p -> p.getPermissionName().equals(permission.name()));
    }

    public boolean hasAnyPermission(PermissionName... permissions) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }

        for (PermissionName permission : permissions) {
            if (hasPermission(user, permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAllPermissions(PermissionName... permissions) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }

        for (PermissionName permission : permissions) {
            if (!hasPermission(user, permission)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasRole(RoleName roleName) {
        return hasRole(getCurrentUser(), roleName);
    }

    public boolean hasRole(User user, RoleName roleName) {
        if (user == null) {
            return false;
        }

        return user.getRoles().stream()
                .anyMatch(role -> role.getRoleName().equals(roleName.name()));
    }

    public boolean hasAnyRole(RoleName... roleNames) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }

        for (RoleName roleName : roleNames) {
            if (hasRole(user, roleName)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAllRoles(RoleName... roleNames) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }

        for (RoleName roleName : roleNames) {
            if (!hasRole(user, roleName)) {
                return false;
            }
        }
        return true;
    }

    public boolean isResourceOwner(UUID resourceOwnerId) {
        User currentUser = getCurrentUser();
        return currentUser != null && currentUser.getId().equals(resourceOwnerId);
    }

    public boolean canAccessResource(UUID resourceOwnerId, PermissionName adminPermission) {
        return isResourceOwner(resourceOwnerId) || hasPermission(adminPermission);
    }

    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() ||
                "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }

        String username = authentication.getName();
        return userRepository.findByUsernameWithRolesAndPermissions(username).orElse(null);
    }

    public Set<String> getCurrentUserPermissions() {
        User user = getCurrentUser();
        if (user == null) {
            return Set.of();
        }

        return user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getPermissionName)
                .collect(Collectors.toSet());
    }

    public Set<String> getCurrentUserRoles() {
        User user = getCurrentUser();
        if (user == null) {
            return Set.of();
        }

        return user.getRoles().stream()
                .map(Role::getRoleName)
                .collect(Collectors.toSet());
    }

    public boolean isAdmin() {
        return hasPermission(PermissionName.SYSTEM_ADMIN);
    }

    public boolean isSuperAdmin() {
        return hasRole(RoleName.ROLE_SUPER_ADMIN);
    }
}

@Component
@RequiredArgsConstructor
public class PermissionUtil {

    private final PermissionEvaluator permissionEvaluator;

    public void requirePermission(PermissionName permission) {
        if (!permissionEvaluator.hasPermission(permission)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    public void requireAnyPermission(PermissionName... permissions) {
        if (!permissionEvaluator.hasAnyPermission(permissions)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    public void requireAllPermissions(PermissionName... permissions) {
        if (!permissionEvaluator.hasAllPermissions(permissions)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    public void requireRole(RoleName role) {
        if (!permissionEvaluator.hasRole(role)) {
            throw new ForbiddenException("Insufficient role to access this resource");
        }
    }

    public void requireAnyRole(RoleName... roles) {
        if (!permissionEvaluator.hasAnyRole(roles)) {
            throw new ForbiddenException("Insufficient role to access this resource");
        }
    }

    public void requireResourceOwnershipOrPermission(UUID resourceOwnerId, PermissionName adminPermission) {
        if (!permissionEvaluator.canAccessResource(resourceOwnerId, adminPermission)) {
            throw new ForbiddenException("You can only access your own resources or need admin permissions");
        }
    }

    public void requireResourceOwnership(UUID resourceOwnerId) {
        if (!permissionEvaluator.isResourceOwner(resourceOwnerId)) {
            throw new ForbiddenException("You can only access your own resources");
        }
    }

    public User getCurrentUser() {
        return permissionEvaluator.getCurrentUser();
    }

    public UUID getCurrentUserId() {
        User user = getCurrentUser();
        if (user == null) {
            throw new UnauthorizedException("User not authenticated");
        }
        return user.getId();
    }

    public boolean isAdmin() {
        return permissionEvaluator.isAdmin();
    }

    public boolean isSuperAdmin() {
        return permissionEvaluator.isSuperAdmin();
    }

    public void requireAuthentication() {
        if (getCurrentUser() == null) {
            throw new UnauthorizedException("Authentication required");
        }
    }
}

@Service
@RequiredArgsConstructor
public class QuizUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {

        User user = userRepository.findByUsernameWithRoles(usernameOrEmail)
                .or(() -> userRepository.findByEmailWithRoles(usernameOrEmail))
                .orElseThrow(() -> new UsernameNotFoundException("User not found with username or email: " + usernameOrEmail));

        List<GrantedAuthority> authorities = user.getRoles()
                .stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.getRoleName()))
                .toList();

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getHashedPassword(),
                user.isActive(),
                true,
                true,
                true,
                authorities
        );
    }
}

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@EnableAspectJAutoProxy
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/2fa/setup",
                                "/api/v1/auth/2fa/verify"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tags/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/questions/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers("/api/documents/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource));

        return httpSecurity.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration cfg
    ) throws Exception {
        return cfg.getAuthenticationManager();
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleService roleService;

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting data initialization...");

        try {
            roleService.initializeDefaultRolesAndPermissions();
            log.info("Data initialization completed successfully");
        } catch (Exception e) {
            log.error("Error during data initialization: {}", e.getMessage(), e);
            throw e;
        }
    }
}

@Schema(name = "ChangePasswordRequest", description = "Payload for changing user password")
@DifferentFrom(field = "newPassword", notEqualTo = "currentPassword")
public record ChangePasswordRequest(
        @Schema(description = "Current password", example = "OldP@ssw0rd!")
        @NotBlank(message = "{currentPassword.blank}")
        String currentPassword,

        @Schema(description = "New password", example = "NewP@ssw0rd!")
        @NotBlank(message = "{newPassword.blank}")
        @Size(min = 8, max = 100, message = "{password.length}")
        @ValidPassword
        String newPassword
) {
}

@Schema(name = "ForgotPasswordRequest", description = "Payload for initiating password reset")
public record ForgotPasswordRequest(
        @Schema(description = "Email address associated with the account", example = "user@example.com")
        @NotBlank(message = "{email.blank}")
        @Size(max = 254, message = "{email.max}")
        @Email(message = "{email.invalid}")
        @NoLeadingTrailingSpaces
        String email
) {
}

@Schema(name = "JwtResponse", description = "JSON Web Tokens and expiration information")
public record JwtResponse(
        @Schema(description = "Access token (JWT)", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        String accessToken,

        @Schema(description = "Refresh token (JWT)", example = "dGhpc2lzYXJlZnJlc2h0b2tlbg==")
        String refreshToken,

        @Schema(description = "Access token validity in milliseconds", example = "3600000")
        long accessExpiresInMs,

        @Schema(description = "Refresh token validity in milliseconds", example = "864000000")
        long refreshExpiresInMs
) {
}

@Schema(name = "LoginRequest", description = "Payload for user login")
public record LoginRequest(
        @Schema(description = "Username or email", example = "newUser")
        @NotBlank(message = "{username.blank}")
        String username,

        @Schema(description = "User password", example = "P@ssw0rd!")
        @NotBlank(message = "{password.blank}")
        String password
) {
}

@Schema(name = "RefreshToken", description = "Payload to refresh an access token")
public record RefreshRequest(
        @Schema(description = "Refresh token to exchange", example = "dGhpc2lzYXJlZnJlc2h0b2tlbg==")
        String refreshToken
) {
}

@Schema(name = "RegisterRequest", description = "Payload for user registration")
public record RegisterRequest(
        @Schema(description = "Unique username", example = "newUser")
        @NotBlank(message = "{username.blank}")
        @Size(min = 4, max = 20, message = "{username.length}")
        @NoLeadingTrailingSpaces
        String username,

        @Schema(description = "User email address", example = "user@example.com")
        @NotBlank(message = "{email.blank}")
        @Size(max = 254, message = "{email.max}")
        @Email(message = "{email.invalid}")
        @NoLeadingTrailingSpaces
        String email,

        @Schema(description = "Password for the new account", example = "P@ssw0rd!")
        @NotBlank(message = "{password.blank}")
        @Size(min = 8, max = 100, message = "{password.length}")
        @ValidPassword
        String password
) {
}

@Schema(name = "ResetPasswordRequest", description = "Payload for resetting user password")
public record ResetPasswordRequest(
        @Schema(description = "Password reset token", example = "reset-token-123")
        @NotBlank(message = "{token.blank}")
        @Size(max = 512, message = "{token.max}")
        String token,

        @Schema(description = "New password", example = "NewP@ssw0rd!")
        @NotBlank(message = "{newPassword.blank}")
        @Size(min = 8, max = 100, message = "{password.length}")
        @ValidPassword
        String newPassword
) {
}

public record TwoFaSetupDto() {
}

public record TwoFaVerifyRequest() {
}

public class AvatarUploadDto {
}

public class CreateUserRequest {
}

public class UpdateProfileRequest {
}

public class UpdateUserRequest {
}

@Schema(name = "UserDto", description = "Details of a user")
public record UserDto(
        @Schema(description = "Unique user identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Username", example = "newUser")
        String username,

        @Schema(description = "Email address", example = "user@example.com")
        String email,

        @Schema(description = "Whether the user is active", example = "true")
        boolean isActive,

        @Schema(description = "Assigned roles", example = "[\"ROLE_USER\"]")
        Set<RoleName> roles,

        @Schema(description = "Account creation timestamp", example = "2025-05-21T15:30:00")
        LocalDateTime createdAt,

        @Schema(description = "Last login timestamp", example = "2025-05-21T16:00:00")
        LocalDateTime lastLoginDate,

        @Schema(description = "Last update timestamp", example = "2025-05-21T16:10:00")
        LocalDateTime updatedAt
) {
}

public class UserProfileDto {
}

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "roles")
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "permission_id")
    private Long permissionId;

    @Column(name = "permission_name", nullable = false, unique = true)
    private String permissionName;

    @Column(name = "description")
    private String description;

    @Column(name = "resource")
    private String resource;

    @Column(name = "action")
    private String action;

    @ManyToMany(mappedBy = "permissions", fetch = FetchType.LAZY)
    private Set<Role> roles;
}

public enum PermissionName {
    QUIZ_READ("quiz", "read", "View quizzes"),
    QUIZ_CREATE("quiz", "create", "Create quizzes"),
    QUIZ_UPDATE("quiz", "update", "Update own quizzes"),
    QUIZ_DELETE("quiz", "delete", "Delete own quizzes"),
    QUIZ_PUBLISH("quiz", "publish", "Publish quizzes"),
    QUIZ_MODERATE("quiz", "moderate", "Moderate any quiz"),
    QUIZ_ADMIN("quiz", "admin", "Full quiz administration"),

    QUESTION_READ("question", "read", "View questions"),
    QUESTION_CREATE("question", "create", "Create questions"),
    QUESTION_UPDATE("question", "update", "Update own questions"),
    QUESTION_DELETE("question", "delete", "Delete own questions"),
    QUESTION_MODERATE("question", "moderate", "Moderate any question"),
    QUESTION_ADMIN("question", "admin", "Full question administration"),

    CATEGORY_READ("category", "read", "View categories"),
    CATEGORY_CREATE("category", "create", "Create categories"),
    CATEGORY_UPDATE("category", "update", "Update categories"),
    CATEGORY_DELETE("category", "delete", "Delete categories"),
    CATEGORY_ADMIN("category", "admin", "Full category administration"),

    TAG_READ("tag", "read", "View tags"),
    TAG_CREATE("tag", "create", "Create tags"),
    TAG_UPDATE("tag", "update", "Update tags"),
    TAG_DELETE("tag", "delete", "Delete tags"),
    TAG_ADMIN("tag", "admin", "Full tag administration"),

    USER_READ("user", "read", "View user profiles"),
    USER_UPDATE("user", "update", "Update own profile"),
    USER_DELETE("user", "delete", "Delete own account"),
    USER_MANAGE("user", "manage", "Manage other users"),
    USER_ADMIN("user", "admin", "Full user administration"),

    COMMENT_READ("comment", "read", "View comments"),
    COMMENT_CREATE("comment", "create", "Create comments"),
    COMMENT_UPDATE("comment", "update", "Update own comments"),
    COMMENT_DELETE("comment", "delete", "Delete own comments"),
    COMMENT_MODERATE("comment", "moderate", "Moderate any comment"),

    ATTEMPT_CREATE("attempt", "create", "Take quizzes"),
    ATTEMPT_READ("attempt", "read", "View own attempts"),
    ATTEMPT_READ_ALL("attempt", "read_all", "View all attempts"),
    ATTEMPT_DELETE("attempt", "delete", "Delete attempts"),

    BOOKMARK_CREATE("bookmark", "create", "Create bookmarks"),
    BOOKMARK_READ("bookmark", "read", "View bookmarks"),
    BOOKMARK_DELETE("bookmark", "delete", "Delete bookmarks"),
    FOLLOW_CREATE("follow", "create", "Follow users"),
    FOLLOW_DELETE("follow", "delete", "Unfollow users"),

    ROLE_READ("role", "read", "View roles"),
    ROLE_CREATE("role", "create", "Create roles"),
    ROLE_UPDATE("role", "update", "Update roles"),
    ROLE_DELETE("role", "delete", "Delete roles"),
    ROLE_ASSIGN("role", "assign", "Assign roles to users"),

    PERMISSION_READ("permission", "read", "View permissions"),
    PERMISSION_CREATE("permission", "create", "Create permissions"),
    PERMISSION_UPDATE("permission", "update", "Update permissions"),
    PERMISSION_DELETE("permission", "delete", "Delete permissions"),

    AUDIT_READ("audit", "read", "View audit logs"),
    SYSTEM_ADMIN("system", "admin", "Full system administration"),
    NOTIFICATION_READ("notification", "read", "View notifications"),
    NOTIFICATION_CREATE("notification", "create", "Create notifications"),
    NOTIFICATION_ADMIN("notification", "admin", "Manage all notifications");

    private final String resource;
    private final String action;
    private final String description;

    PermissionName(String resource, String action, String description) {
        this.resource = resource;
        this.action = action;
        this.description = description;
    }

    public String getResource() {
        return resource;
    }

    public String getAction() {
        return action;
    }

    public String getDescription() {
        return description;
    }

    public String getPermissionName() {
        return this.name();
    }
}

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"users", "permissions"})
@Table(name = "roles")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "role_name", nullable = false, unique = true)
    private String roleName;

    @Column(name = "description")
    private String description;

    @Column(name = "is_default")
    @Builder.Default
    private boolean isDefault = false;

    @ManyToMany(mappedBy = "roles", fetch = FetchType.LAZY)
    private Set<User> users;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions;
}

public enum RoleName {
    ROLE_USER,
    ROLE_QUIZ_CREATOR,
    ROLE_MODERATOR,
    ROLE_ADMIN,
    ROLE_SUPER_ADMIN
}

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Table(name = "users")
public class User implements Persistable<UUID> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "username", unique = true, nullable = false)
    private String username;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password", nullable = false)
    private String hashedPassword;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login")
    private LocalDateTime lastLoginDate;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles;

    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public boolean isNew() {
        return this.isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.isNew = true;
    }

    @PreUpdate
    void preUpdate() {
        if (!this.isDeleted) {
            this.updatedAt = LocalDateTime.now();
        } else {
            this.deletedAt = LocalDateTime.now();
        }
    }
}

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByPermissionName(String permissionName);

    List<Permission> findByResource(String resource);

    boolean existsByPermissionName(String permissionName);

    List<Permission> findByResourceAndAction(String resource, String action);
}

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByRoleName(String roleName);

    boolean existsByRoleName(String roleName);

    Optional<Role> findByIsDefaultTrue();

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.roleName = :roleName")
    Optional<Role> findByRoleNameWithPermissions(@Param("roleName") String roleName);

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.roleId = :roleId")
    Optional<Role> findByIdWithPermissions(@Param("roleId") Long roleId);

    @Query("SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.permissions")
    List<Role> findAllWithPermissions();

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.isDefault = true")
    Optional<Role> findByIsDefaultTrueWithPermissions();
}

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.username = :username")
    Optional<User> findByUsernameWithRoles(@Param("username") String username);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.email = :email")
    Optional<User> findByEmailWithRoles(@Param("email") String email);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.id = :id")
    Optional<User> findByIdWithRoles(@Param("id") UUID id);

    @Query("SELECT DISTINCT u FROM User u " +
           "LEFT JOIN FETCH u.roles r " +
           "LEFT JOIN FETCH r.permissions " +
           "WHERE u.username = :username")
    Optional<User> findByUsernameWithRolesAndPermissions(@Param("username") String username);

    @Query("SELECT DISTINCT u FROM User u " +
           "LEFT JOIN FETCH u.roles r " +
           "LEFT JOIN FETCH r.permissions " +
           "WHERE u.id = :id")
    Optional<User> findByIdWithRolesAndPermissions(@Param("id") UUID id);

}

public interface UserRoleRepository {
}

@Service
public interface UserService {
    public User createUser(User user);
}

@Service
@Getter
@Setter
@AllArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    @Override
    public User createUser(User user) {
        User savedUser = userRepository.save(user);
        return savedUser;
    }
}

public interface AuthService {
    UserDto register(@Valid RegisterRequest request);

    JwtResponse login(LoginRequest loginRequest);

    JwtResponse refresh(RefreshRequest refreshRequest);

    void logout(String token);

    UserDto getCurrentUser(Authentication authentication);
}

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final AuthenticationManager authManager;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public UserDto register(RegisterRequest request) {

        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already in use");
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setHashedPassword(passwordEncoder.encode(request.password()));
        user.setActive(true);

        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found"));
        user.setRoles(Set.of(userRole));

        User saved = userRepository.save(user);
        return userMapper.toDto(saved);
    }

    @Override
    public JwtResponse login(LoginRequest loginRequest) {
        try {
            Authentication authentication = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.username(), loginRequest.password())
            );

            String accessToken = jwtTokenProvider.generateAccessToken(authentication);
            String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);
            long accessExpiresInMs = jwtTokenProvider.getAccessTokenValidityInMs();
            long refreshExpiresInMs = jwtTokenProvider.getRefreshTokenValidityInMs();

            return new JwtResponse(accessToken, refreshToken, accessExpiresInMs, refreshExpiresInMs);
        } catch (AuthenticationException ex) {
            throw new UnauthorizedException("Invalid username or password");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    @Override
    public JwtResponse refresh(RefreshRequest refreshRequest) {

        String token = refreshRequest.refreshToken();

        if (!jwtTokenProvider.validateToken(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String type = jwtTokenProvider.getClaims(token).get("type", String.class);
        if (!"refresh".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token is not a refresh token");
        }

        Authentication authentication = jwtTokenProvider.getAuthentication(token);

        return new JwtResponse(jwtTokenProvider.generateAccessToken(authentication),
                token,
                jwtTokenProvider.getAccessTokenValidityInMs(),
                jwtTokenProvider.getRefreshTokenValidityInMs());
    }

    @Override
    public void logout(String token) {

    }

    @Override
    public UserDto getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User  " + username + " not found"));
        return userMapper.toDto(user);
    }
}

public interface PermissionService {

    Permission createPermission(String permissionName, String description, String resource, String action);

    Permission getPermissionByName(String permissionName);

    List<Permission> getAllPermissions();

    List<Permission> getPermissionsByResource(String resource);

    void assignPermissionToRole(Long roleId, Long permissionId);

    void removePermissionFromRole(Long roleId, Long permissionId);

    Set<Permission> getRolePermissions(Long roleId);

    boolean permissionExists(String permissionName);

    void initializePermissions();

    void deletePermission(Long permissionId);

    Permission updatePermission(Long permissionId, String description, String resource, String action);
}

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PermissionServiceImpl implements PermissionService {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    @Override
    public Permission createPermission(String permissionName, String description, String resource, String action) {
        if (permissionExists(permissionName)) {
            throw new IllegalArgumentException("Permission already exists: " + permissionName);
        }

        Permission permission = Permission.builder()
                .permissionName(permissionName)
                .description(description)
                .resource(resource)
                .action(action)
                .build();

        Permission savedPermission = permissionRepository.save(permission);
        log.info("Created permission: {}", permissionName);
        return savedPermission;
    }

    @Override
    @Transactional(readOnly = true)
    public Permission getPermissionByName(String permissionName) {
        return permissionRepository.findByPermissionName(permissionName)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionName));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Permission> getPermissionsByResource(String resource) {
        return permissionRepository.findByResource(resource);
    }

    @Override
    public void assignPermissionToRole(Long roleId, Long permissionId) {
        Role role = roleRepository.findByIdWithPermissions(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));

        role.getPermissions().add(permission);
        roleRepository.save(role);
        log.info("Assigned permission {} to role {}", permission.getPermissionName(), role.getRoleName());
    }

    @Override
    public void removePermissionFromRole(Long roleId, Long permissionId) {
        Role role = roleRepository.findByIdWithPermissions(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));

        role.getPermissions().remove(permission);
        roleRepository.save(role);
        log.info("Removed permission {} from role {}", permission.getPermissionName(), role.getRoleName());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Permission> getRolePermissions(Long roleId) {
        Role role = roleRepository.findByIdWithPermissions(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        return role.getPermissions();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permissionExists(String permissionName) {
        return permissionRepository.existsByPermissionName(permissionName);
    }

    @Override
    public void initializePermissions() {
        log.info("Initializing permissions from PermissionName enum");

        for (PermissionName permissionName : PermissionName.values()) {
            if (!permissionExists(permissionName.name())) {
                createPermission(
                        permissionName.name(),
                        permissionName.getDescription(),
                        permissionName.getResource(),
                        permissionName.getAction()
                );
            }
        }

        log.info("Permissions initialization completed");
    }

    @Override
    public void deletePermission(Long permissionId) {
        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));

        for (Role role : permission.getRoles()) {
            role.getPermissions().remove(permission);
            roleRepository.save(role);
        }

        permissionRepository.delete(permission);
        log.info("Deleted permission: {}", permission.getPermissionName());
    }

    @Override
    public Permission updatePermission(Long permissionId, String description, String resource, String action) {
        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));

        permission.setDescription(description);
        permission.setResource(resource);
        permission.setAction(action);

        Permission updatedPermission = permissionRepository.save(permission);
        log.info("Updated permission: {}", permission.getPermissionName());
        return updatedPermission;
    }
}

public interface RoleService {

    RoleDto createRole(CreateRoleRequest request);

    RoleDto updateRole(Long roleId, UpdateRoleRequest request);

    void deleteRole(Long roleId);

    RoleDto getRoleById(Long roleId);

    List<RoleDto> getAllRoles();

    Role getRoleByName(RoleName roleName);

    void assignRoleToUser(UUID userId, Long roleId);

    void removeRoleFromUser(UUID userId, Long roleId);

    Set<Role> getUserRoles(UUID userId);

    boolean roleExists(String roleName);

    Role getDefaultRole();

    void initializeDefaultRolesAndPermissions();
}

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final RoleMapper roleMapper;

    @Override
    public RoleDto createRole(CreateRoleRequest request) {
        if (roleExists(request.getRoleName())) {
            throw new IllegalArgumentException("Role already exists: " + request.getRoleName());
        }

        Role role = Role.builder()
                .roleName(request.getRoleName())
                .description(request.getDescription())
                .isDefault(request.isDefault())
                .permissions(new HashSet<>())
                .build();

        Role savedRole = roleRepository.save(role);
        log.info("Created role: {}", request.getRoleName());
        return roleMapper.toDto(savedRole);
    }

    @Override
    public RoleDto updateRole(Long roleId, UpdateRoleRequest request) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        role.setDescription(request.getDescription());
        role.setDefault(request.isDefault());

        Role updatedRole = roleRepository.save(role);
        log.info("Updated role: {}", role.getRoleName());
        return roleMapper.toDto(updatedRole);
    }

    @Override
    public void deleteRole(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        for (User user : role.getUsers()) {
            user.getRoles().remove(role);
            userRepository.save(user);
        }

        roleRepository.delete(role);
        log.info("Deleted role: {}", role.getRoleName());
    }

    @Override
    @Transactional(readOnly = true)
    public RoleDto getRoleById(Long roleId) {
        Role role = roleRepository.findByIdWithPermissions(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        return roleMapper.toDto(role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleDto> getAllRoles() {
        List<Role> roles = roleRepository.findAllWithPermissions();
        return roles.stream()
                .map(roleMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Role getRoleByName(RoleName roleName) {
        return roleRepository.findByRoleNameWithPermissions(roleName.name())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleName));
    }

    @Override
    public void assignRoleToUser(UUID userId, Long roleId) {
        User user = userRepository.findByIdWithRoles(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        user.getRoles().add(role);
        userRepository.save(user);
        log.info("Assigned role {} to user {}", role.getRoleName(), user.getUsername());
    }

    @Override
    public void removeRoleFromUser(UUID userId, Long roleId) {
        User user = userRepository.findByIdWithRoles(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        user.getRoles().remove(role);
        userRepository.save(user);
        log.info("Removed role {} from user {}", role.getRoleName(), user.getUsername());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Role> getUserRoles(UUID userId) {
        User user = userRepository.findByIdWithRoles(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        return user.getRoles();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean roleExists(String roleName) {
        return roleRepository.existsByRoleName(roleName);
    }

    @Override
    @Transactional(readOnly = true)
    public Role getDefaultRole() {
        return roleRepository.findByIsDefaultTrueWithPermissions()
                .orElseGet(() -> getRoleByName(RoleName.ROLE_USER));
    }

    @Override
    public void initializeDefaultRolesAndPermissions() {
        log.info("Initializing default roles and permissions");

        permissionService.initializePermissions();

        createRoleIfNotExists(RoleName.ROLE_USER.name(), "Basic user role", true, getUserPermissions());
        createRoleIfNotExists(RoleName.ROLE_QUIZ_CREATOR.name(), "Quiz creator role", false, getQuizCreatorPermissions());
        createRoleIfNotExists(RoleName.ROLE_MODERATOR.name(), "Moderator role", false, getModeratorPermissions());
        createRoleIfNotExists(RoleName.ROLE_ADMIN.name(), "Administrator role", false, getAdminPermissions());
        createRoleIfNotExists(RoleName.ROLE_SUPER_ADMIN.name(), "Super administrator role", false, getSuperAdminPermissions());

        log.info("Default roles and permissions initialization completed");
    }

    private void createRoleIfNotExists(String roleName, String description, boolean isDefault, Set<String> permissionNames) {
        if (!roleExists(roleName)) {
            Role role = Role.builder()
                    .roleName(roleName)
                    .description(description)
                    .isDefault(isDefault)
                    .permissions(new HashSet<>())
                    .build();

            Role savedRole = roleRepository.save(role);

            for (String permissionName : permissionNames) {
                try {
                    Permission permission = permissionService.getPermissionByName(permissionName);
                    savedRole.getPermissions().add(permission);
                } catch (ResourceNotFoundException e) {
                    log.warn("Permission not found: {}", permissionName);
                }
            }

            roleRepository.save(savedRole);
            log.info("Created role: {} with {} permissions", roleName, savedRole.getPermissions().size());
        }
    }

    private Set<String> getUserPermissions() {
        return Set.of(
                PermissionName.QUIZ_READ.name(),
                PermissionName.QUESTION_READ.name(),
                PermissionName.CATEGORY_READ.name(),
                PermissionName.TAG_READ.name(),
                PermissionName.USER_READ.name(),
                PermissionName.USER_UPDATE.name(),
                PermissionName.USER_DELETE.name(),
                PermissionName.COMMENT_READ.name(),
                PermissionName.COMMENT_CREATE.name(),
                PermissionName.COMMENT_UPDATE.name(),
                PermissionName.COMMENT_DELETE.name(),
                PermissionName.ATTEMPT_CREATE.name(),
                PermissionName.ATTEMPT_READ.name(),
                PermissionName.BOOKMARK_CREATE.name(),
                PermissionName.BOOKMARK_READ.name(),
                PermissionName.BOOKMARK_DELETE.name(),
                PermissionName.FOLLOW_CREATE.name(),
                PermissionName.FOLLOW_DELETE.name(),
                PermissionName.NOTIFICATION_READ.name()
        );
    }

    private Set<String> getQuizCreatorPermissions() {
        Set<String> permissions = new HashSet<>(getUserPermissions());
        permissions.addAll(Set.of(
                PermissionName.QUIZ_CREATE.name(),
                PermissionName.QUIZ_UPDATE.name(),
                PermissionName.QUIZ_DELETE.name(),
                PermissionName.QUIZ_PUBLISH.name(),
                PermissionName.QUESTION_CREATE.name(),
                PermissionName.QUESTION_UPDATE.name(),
                PermissionName.QUESTION_DELETE.name(),
                PermissionName.CATEGORY_CREATE.name(),
                PermissionName.TAG_CREATE.name()
        ));
        return permissions;
    }

    private Set<String> getModeratorPermissions() {
        Set<String> permissions = new HashSet<>(getQuizCreatorPermissions());
        permissions.addAll(Set.of(
                PermissionName.QUIZ_MODERATE.name(),
                PermissionName.QUESTION_MODERATE.name(),
                PermissionName.COMMENT_MODERATE.name(),
                PermissionName.CATEGORY_UPDATE.name(),
                PermissionName.TAG_UPDATE.name(),
                PermissionName.ATTEMPT_READ_ALL.name(),
                PermissionName.USER_MANAGE.name()
        ));
        return permissions;
    }

    private Set<String> getAdminPermissions() {
        Set<String> permissions = new HashSet<>(getModeratorPermissions());
        permissions.addAll(Set.of(
                PermissionName.QUIZ_ADMIN.name(),
                PermissionName.QUESTION_ADMIN.name(),
                PermissionName.CATEGORY_ADMIN.name(),
                PermissionName.TAG_ADMIN.name(),
                PermissionName.USER_ADMIN.name(),
                PermissionName.ROLE_READ.name(),
                PermissionName.ROLE_ASSIGN.name(),
                PermissionName.PERMISSION_READ.name(),
                PermissionName.AUDIT_READ.name(),
                PermissionName.NOTIFICATION_CREATE.name(),
                PermissionName.NOTIFICATION_ADMIN.name(),
                PermissionName.ATTEMPT_DELETE.name()
        ));
        return permissions;
    }

    private Set<String> getSuperAdminPermissions() {
        Set<String> permissions = new HashSet<>(getAdminPermissions());
        permissions.addAll(Set.of(
                PermissionName.SYSTEM_ADMIN.name(),
                PermissionName.ROLE_CREATE.name(),
                PermissionName.ROLE_UPDATE.name(),
                PermissionName.ROLE_DELETE.name(),
                PermissionName.PERMISSION_CREATE.name(),
                PermissionName.PERMISSION_UPDATE.name(),
                PermissionName.PERMISSION_DELETE.name()
        ));
        return permissions;
    }
}

@Tag(name = "Authentication", description = "Endpoints for registering, logging in, refreshing tokens, logout, and fetching current user")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "Register a new user",
            description = "Creates a new user account. Returns the created user's details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User successfully registered"),
            @ApiResponse(responseCode = "400", description = "Validation errors"),
            @ApiResponse(responseCode = "409", description = "Username or email already in use")
    })
    @PostMapping("/register")
    public ResponseEntity<UserDto> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Registration information",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RegisterRequest.class))
            )
            @Valid @RequestBody RegisterRequest request
    ) {
        UserDto createdUser = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @Operation(
            summary = "Log in",
            description = "Authenticates a user and returns access and refresh JWT tokens."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    @PostMapping("/login")
    public ResponseEntity<JwtResponse> login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Username (or email) and password",
                    required = true,
                    content = @Content(schema = @Schema(implementation = LoginRequest.class))
            )
            @Valid @RequestBody LoginRequest request
    ) {
        JwtResponse tokens = authService.login(request);
        return ResponseEntity.ok(tokens);
    }

    @Operation(
            summary = "Refresh tokens",
            description = "Exchanges a valid refresh token for a new access token (and optionally a new refresh token)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens refreshed"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
    })
    @PostMapping("/refresh")
    public ResponseEntity<JwtResponse> refresh(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Refresh token payload",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RefreshRequest.class))
            )
            @RequestBody RefreshRequest refreshRequest
    ) {
        return ResponseEntity.ok(authService.refresh(refreshRequest));
    }

    @Operation(
            summary = "Logout",
            description = "Revokes the provided access token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logout successful"),
            @ApiResponse(responseCode = "401", description = "Invalid or missing token")
    })
    @PostMapping("/logout")
    public void logout(
            @Parameter(
                    description = "Bearer access token",
                    in = ParameterIn.HEADER,
                    name = HttpHeaders.AUTHORIZATION,
                    required = true,
                    schema = @Schema(type = "string", example = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
            )
            @RequestHeader(HttpHeaders.AUTHORIZATION) String header
    ) {
        String token = header.replaceFirst("^Bearer\\s+", "");
        authService.logout(token);
    }

    @Operation(
            summary = "Get current user",
            description = "Returns details of the authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user retrieved"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(
            Authentication authentication
    ) {
        return ResponseEntity.ok(authService.getCurrentUser(authentication));
    }
}

public class UserController {
}

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {

    private final RoleService roleService;
    private final PermissionUtil permissionUtil;

    @GetMapping("/roles")
    @Operation(summary = "Get all roles")
    @RequirePermission(PermissionName.ROLE_READ)
    public ResponseEntity<List<RoleDto>> getAllRoles() {
        List<RoleDto> roles = roleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    @GetMapping("/roles/{roleId}")
    @Operation(summary = "Get role by ID")
    @RequirePermission(PermissionName.ROLE_READ)
    public ResponseEntity<RoleDto> getRoleById(@PathVariable Long roleId) {
        RoleDto role = roleService.getRoleById(roleId);
        return ResponseEntity.ok(role);
    }

    @PostMapping("/roles")
    @Operation(summary = "Create a new role")
    @RequirePermission(PermissionName.ROLE_CREATE)
    public ResponseEntity<RoleDto> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleDto createdRole = roleService.createRole(request);
        return ResponseEntity.ok(createdRole);
    }

    @PutMapping("/roles/{roleId}")
    @Operation(summary = "Update an existing role")
    @RequirePermission(PermissionName.ROLE_UPDATE)
    public ResponseEntity<RoleDto> updateRole(@PathVariable Long roleId,
                                              @Valid @RequestBody UpdateRoleRequest request) {
        RoleDto updatedRole = roleService.updateRole(roleId, request);
        return ResponseEntity.ok(updatedRole);
    }

    @DeleteMapping("/roles/{roleId}")
    @Operation(summary = "Delete a role")
    @RequirePermission(PermissionName.ROLE_DELETE)
    public ResponseEntity<Void> deleteRole(@PathVariable Long roleId) {
        roleService.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    @Operation(summary = "Assign role to user")
    @RequireRole(RoleName.ROLE_ADMIN)
    public ResponseEntity<Void> assignRoleToUser(@PathVariable UUID userId,
                                                 @PathVariable Long roleId) {
        roleService.assignRoleToUser(userId, roleId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @Operation(summary = "Remove role from user")
    @RequireRole(RoleName.ROLE_ADMIN)
    public ResponseEntity<Void> removeRoleFromUser(@PathVariable UUID userId,
                                                   @PathVariable Long roleId) {
        roleService.removeRoleFromUser(userId, roleId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/system/initialize")
    @Operation(summary = "Initialize system roles and permissions")
    public ResponseEntity<String> initializeSystem() {
        permissionUtil.requirePermission(PermissionName.SYSTEM_ADMIN);

        roleService.initializeDefaultRolesAndPermissions();
        return ResponseEntity.ok("System initialized successfully");
    }

    @GetMapping("/system/status")
    @Operation(summary = "Get system status")
    @RequirePermission(value = {PermissionName.SYSTEM_ADMIN, PermissionName.AUDIT_READ},
            operator = RequirePermission.LogicalOperator.OR)
    public ResponseEntity<String> getSystemStatus() {
        if (permissionUtil.isSuperAdmin()) {
            return ResponseEntity.ok("System status: All systems operational (Super Admin view)");
        } else {
            return ResponseEntity.ok("System status: All systems operational (Limited view)");
        }
    }

    @PostMapping("/super/dangerous-operation")
    @Operation(summary = "Perform dangerous operation")
    @RequireRole(RoleName.ROLE_SUPER_ADMIN)
    public ResponseEntity<String> performDangerousOperation() {
        log.warn("Dangerous operation performed by user: {}",
                permissionUtil.getCurrentUser().getUsername());
        return ResponseEntity.ok("Operation completed");
    }
}

@Component
@RequiredArgsConstructor
public class UserMapper {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public UserDto toDto(User user) {
        return new UserDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.isActive(),
                user.getRoles()
                        .stream()
                        .map(Role::getRoleName)
                        .map(RoleName::valueOf)
                        .collect(Collectors.toSet()),
                user.getCreatedAt(),
                user.getLastLoginDate(),
                user.getUpdatedAt()
        );
    }
}

@Component
@RequiredArgsConstructor
public class RoleMapper {

    public RoleDto toDto(Role role) {
        if (role == null) {
            return null;
        }

        return RoleDto.builder()
                .roleId(role.getRoleId())
                .roleName(role.getRoleName())
                .description(role.getDescription())
                .isDefault(role.isDefault())
                .permissions(role.getPermissions() != null ?
                        role.getPermissions().stream()
                                .map(permission -> permission.getPermissionName())
                                .collect(Collectors.toSet()) : null)
                .build();
    }

    public List<RoleDto> toDtoList(List<Role> roles) {
        if (roles == null) {
            return null;
        }

        return roles.stream()
                .map(this::toDto)
                .toList();
    }
}

public class PermissionMapper {
}

public class AuthMapper {
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRoleRequest {

    @NotBlank(message = "Role name is required")
    private String roleName;

    private String description;

    private boolean isDefault;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDto {

    private Long roleId;
    private String roleName;
    private String description;
    private boolean isDefault;
    private Set<String> permissions;
    private int userCount;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRoleRequest {

    private String description;

    private boolean isDefault;
}
