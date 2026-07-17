# Spring Boot Annotation Study Guide For QuizMaker

## 1. Configuration And Dependency Injection

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@SpringBootApplication` | The application entry point; combines configuration, auto-configuration, and component scanning. | Keep it near the root package so feature packages are scanned. |
| `@Configuration` and `@Bean` | Explicit infrastructure configuration and third-party beans. | Do not put feature business rules here. |
| `@ConfigurationProperties` | Cohesive, validated groups of configuration values. | Keep secrets out of defaults and logs. |
| `@Component`, `@Service`, `@Repository` | Spring-managed components; use the most specific stereotype. | Prefer constructor injection, not field injection. |
| `@RequiredArgsConstructor`, `@Qualifier`, `@Primary`, `@Lazy` | Constructor generation and explicit bean selection. | `@Lazy` solves cycles only when the dependency direction is otherwise sound. |
| `@Profile`, `@ConditionalOnProperty`, `@ConditionalOnBean`, `@ConditionalOnMissingBean` | Environment- or configuration-dependent wiring. | Make the disabled behaviour explicit and test it when it affects a public feature. |

## 2. REST, Validation, And Error Handling

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping` | REST endpoint classes and routes. | Controllers should validate, delegate to a service interface, and return DTOs. |
| `@RequestBody`, `@RequestParam`, `@PathVariable`, `@RequestHeader`, `@RequestPart`, `@ModelAttribute` | Mapping HTTP input to method parameters. | Choose the shape that matches the public contract; validate inputs. |
| `@Valid`, `@Validated`, `@NotBlank`, `@NotEmpty`, `@NotNull`, `@Size`, `@Email`, `@Min`, `@Max`, `@Positive`, `@DecimalMin` | Bean and method validation. | DTO validation does not replace service-level business invariants. |
| `@Constraint` | A reusable custom validator such as a password rule. | Keep validation deterministic and free of database/network side effects. |
| `@RestControllerAdvice`, `@ExceptionHandler`, `@ResponseStatus` | Central error mapping to `ProblemDetail`. | Do not expose stack traces or internal database details. |
| `@ParameterObject`, `@PageableDefault`, `@SortDefault` | Document and bind pagination/sort input. | Specify stable defaults and bounded page sizes. |

## 3. OpenAPI

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@Operation`, `@ApiResponse`, `@ApiResponses` | Endpoint purpose and success/error responses. | Document actual statuses and `ProblemDetail` errors. |
| `@Schema`, `@ArraySchema`, `@Content`, `@ExampleObject` | Named schemas and valid examples. | Examples must validate and not leak sensitive data. |
| `@Parameter`, `@SecurityRequirement`, `@Hidden` | Parameter, security, and visibility metadata. | Keep every public endpoint in one OpenAPI group and discoverable through the API summary. |

## 4. Security, Async, And Events

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@PreAuthorize` | Service-level permission checks. | A permission does not replace owner, visibility, or organisation checks. |
| `@RequirePermission` | The project-native endpoint convention where an existing feature uses it. | Follow the local feature convention; do not duplicate checks without a reason. |
| `@Async`, `@EnableAsync` | Non-blocking work on a named executor. | Use the correct executor, preserve failure observability, and do not rely on request security context in a background task. |
| `@EventListener`, `@TransactionalEventListener` | Decoupled reactions to application/domain events. | Choose the transaction phase deliberately; test idempotency and retry behaviour. |
| `@Scheduled`, `@EnableScheduling` | Time-based maintenance jobs. | Use injected time and make work idempotent. |
| `@Aspect`, `@EnableAspectJAutoProxy` | Cross-cutting concerns such as project permission enforcement. | Keep advice narrow and visible in documentation/tests. |

## 5. JPA And Hibernate Mapping

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column` | Entity and table mapping. | Migrations, not annotations, are the database change history. |
| `@OneToMany`, `@ManyToOne`, `@ManyToMany`, `@JoinColumn`, `@JoinTable`, `@OrderBy`, `@OnDelete` | Relationships. | Default to lazy loading and maintain both sides of bidirectional associations. |
| `@Embeddable`, `@Embedded`, `@EmbeddedId`, `@AttributeOverride`, `@AttributeOverrides`, `@MapsId` | Value objects and composite keys. | Use when the domain concept belongs with the entity, not simply to reduce tables. |
| `@Enumerated`, `@JdbcTypeCode`, `@Convert`, `@Converter` | Enum, JSON, and custom type mapping. | Prefer string enum storage and validate JSON shapes at the application boundary. |
| `@CreationTimestamp`, `@UpdateTimestamp`, `@CreatedDate`, `@LastModifiedDate` | Auditing timestamps. | Use a consistent project convention and UTC-compatible time handling. |
| `@Version`, `@Lock` | Optimistic concurrency and explicit locking. | Test conflicts and retry/response behaviour; do not swallow a lock failure. |
| `@SQLDelete`, `@SQLRestriction` | Soft-delete mapping and filtering. | Verify that all relevant queries, joins, and unique constraints honour the deletion semantics. |
| `@EntityGraph`, `@BatchSize`, `@Query`, `@Modifying`, `@Param` | Fetch plans and custom queries. | Test query shape, pagination, and N+1 behaviour with realistic data. |

## 6. Transactions

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@Transactional` | A service use case that needs atomic persistence. | Keep transactions at service boundaries, use `readOnly = true` for queries, and avoid remote calls while holding a transaction. |
| `@Rollback` | Test transaction behaviour. | Do not confuse test rollback with production transaction semantics. |

## 7. Jackson And JSON

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@JsonCreator`, `@JsonValue` | Explicit enum/value-object deserialization and serialization. | Keep accepted aliases backward compatible and reject unknown invalid values clearly. |
| `@JsonProperty`, `@JsonIgnore`, `@JsonInclude`, `@JsonFormat` | Property names, secret/derived fields, null policy, and representation. | API JSON changes are public-contract changes; add compatibility tests. |

## 8. Lombok

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@RequiredArgsConstructor` | Reduce mechanical code. | Avoid Lombok `@Data` on JPA entities because generated equality and `toString` can trigger lazy loading or recursion. |
| `@EqualsAndHashCode`, `@ToString` | Carefully controlled generated methods. | Exclude relationships and use stable identity rules. |
| `@Slf4j` | A class logger. | Never log credentials, tokens, or personal data. |

## 9. Testing

| Annotation | Use it for | Watch out for |
| --- | --- | --- |
| `@Test`, `@BeforeEach`, `@AfterEach`, `@BeforeAll`, `@DisplayName`, `@Nested` | JUnit test organisation. | Tests must remain independent and order-agnostic. |
| `@ParameterizedTest`, `@ValueSource`, `@CsvSource`, `@EnumSource`, `@MethodSource`, `@NullAndEmptySource` | Rule matrices and boundaries. | Use clear case names and keep data relevant to one rule. |
| `@WebMvcTest`, `@AutoConfigureMockMvc`, `@WithMockUser` | MVC boundary/security tests. | Mock service interfaces and include relevant CSRF/authorization cases. |
| `@DataJpaTest`, `@AutoConfigureTestDatabase` | Focused JPA tests. | Use a real compatible database where MySQL semantics matter. |
| `@SpringBootTest`, `@ActiveProfiles`, `@TestPropertySource`, `@DirtiesContext`, `@TestConfiguration` | Full-context or custom configuration tests. | Full-context tests are slower; use them only for cross-layer behavior. |
| `@Mock`, `@Spy`, `@InjectMocks`, `@MockitoBean`, `@MockitoSettings`, `@ExtendWith` | Mockito collaborators and extensions. | Do not mock the system under test or impossible collaborator behaviour. |

## How To Study Effectively

1. Find an annotation in the source and read its import.
2. Identify the responsibility it signals: HTTP, security, persistence, serialization, infrastructure, or testing.
3. Read the nearest test that proves its intended behaviour.
4. Change a small isolated example in a branch and observe the test result.
5. Return to this guide to compare the annotation with its common pitfalls.

For implementation decisions, [the development guide](../agents.md) and [testing guide](../testing-guide.md) take precedence over generic study examples.
