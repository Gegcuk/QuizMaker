# Attempt Start Endpoint Improvement Plan

## Overview
Remove `firstQuestion` from the `startAttempt` response and replace it with useful attempt metadata. This eliminates the question ordering inconsistency bug and provides better separation of concerns.

## Problem Statement
- **Bug**: Question ID returned in `startAttempt` response doesn't match what `submitAnswer` expects
- **Root Cause**: `startAttempt` uses collection order while `submitAnswer`/`getCurrentQuestion` use UUID-sorted order
- **Impact**: First answer submission always fails with 409 Conflict error

## Solution
Replace `firstQuestion` with attempt metadata in `startAttempt` response, making `getCurrentQuestion` the single source of truth for question selection.

## Detailed Implementation Plan

### 1. Update DTOs

#### 1.1 Modify StartAttemptResponse
**File**: `src/main/java/uk/gegc/quizmaker/dto/attempt/StartAttemptResponse.java`

**Current**:
```java
public record StartAttemptResponse(
    UUID attemptId,
    QuestionForAttemptDto firstQuestion
) {}
```

**New**:
```java
public record StartAttemptResponse(
    UUID attemptId,
    UUID quizId,
    AttemptMode mode,
    int totalQuestions,
    Integer timeLimitMinutes,  // null if no time limit
    Instant startedAt
) {}
```

#### 1.2 Update AttemptServiceImpl.startAttempt()
**File**: `src/main/java/uk/gegc/quizmaker/service/attempt/impl/AttemptServiceImpl.java`

**Current** (lines 72-74):
```java
Question first = quiz.getQuestions().stream().findFirst().orElse(null);
QuestionForAttemptDto dto = first != null ? safeQuestionMapper.toSafeDto(first) : null;
return new StartAttemptResponse(saved.getId(), dto);
```

**New**:
```java
int totalQuestions = quiz.getQuestions().size();
Integer timeLimitMinutes = quiz.getIsTimerEnabled() ? quiz.getTimerDuration() : null;

return new StartAttemptResponse(
    saved.getId(),
    quiz.getId(),
    mode,
    totalQuestions,
    timeLimitMinutes,
    saved.getStartedAt()
);
```

### 2. Update API Documentation

#### 2.1 Update OpenAPI Documentation
**File**: `src/main/java/uk/gegc/quizmaker/controller/AttemptController.java`

Update the `@ApiResponse` example in the `startAttempt` method:

**Current**:
```java
@ExampleObject(name = "success", value = """
    {
      "attemptId":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "firstQuestion":null
    }
    """)
```

**New**:
```java
@ExampleObject(name = "success", value = """
    {
      "attemptId":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "quizId":"68211ef3-7381-46d9-89e8-cdfb851895c4",
      "mode":"ONE_BY_ONE",
      "totalQuestions":10,
      "timeLimitMinutes":30,
      "startedAt":"2025-08-06T20:27:50Z"
    }
    """)
```

#### 2.2 Update Controller Documentation
**File**: `docs/conroller/03-attempt-controller.md`

Update the StartAttemptResponse schema and examples.

### 3. Update Tests

#### 3.1 Unit Tests

##### 3.1.1 AttemptServiceImplTest
**File**: `src/test/java/uk/gegc/quizmaker/service/attempt/AttemptServiceImplTest.java`

**Add new test**:
```java
@Test
@DisplayName("startAttempt returns correct metadata without firstQuestion")
void startAttempt_returnsMetadataWithoutFirstQuestion() {
    // Given
    String username = "testuser";
    UUID quizId = UUID.randomUUID();
    AttemptMode mode = AttemptMode.ONE_BY_ONE;
    
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    
    Quiz quiz = new Quiz();
    quiz.setId(quizId);
    quiz.setIsTimerEnabled(true);
    quiz.setTimerDuration(30);
    
    Question question1 = new Question();
    question1.setId(UUID.randomUUID());
    Question question2 = new Question();
    question2.setId(UUID.randomUUID());
    quiz.setQuestions(Arrays.asList(question1, question2));
    
    when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
    when(attemptRepository.save(any(Attempt.class))).thenAnswer(invocation -> {
        Attempt attempt = invocation.getArgument(0);
        attempt.setId(UUID.randomUUID());
        attempt.setStartedAt(Instant.now());
        return attempt;
    });
    
    // When
    StartAttemptResponse response = attemptService.startAttempt(username, quizId, mode);
    
    // Then
    assertThat(response.attemptId()).isNotNull();
    assertThat(response.quizId()).isEqualTo(quizId);
    assertThat(response.mode()).isEqualTo(mode);
    assertThat(response.totalQuestions()).isEqualTo(2);
    assertThat(response.timeLimitMinutes()).isEqualTo(30);
    assertThat(response.startedAt()).isNotNull();
}
```

##### 3.1.2 Update Existing Tests
Update any existing tests that expect `firstQuestion` in the response.

#### 3.2 Integration Tests

##### 3.2.1 AttemptControllerIntegrationTest
**File**: `src/test/java/uk/gegc/quizmaker/controller/AttemptControllerIntegrationTest.java`

**Update existing test**:
```java
@Test
@DisplayName("POST /api/v1/attempts/quizzes/{quizId} returns attempt metadata")
void startAttempt_returnsMetadata() throws Exception {
    // Given
    var request = new StartAttemptRequest(AttemptMode.ONE_BY_ONE);
    
    // When & Then
    mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
            .with(user("testuser"))
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptId").exists())
            .andExpect(jsonPath("$.quizId").value(quizId.toString()))
            .andExpect(jsonPath("$.mode").value("ONE_BY_ONE"))
            .andExpect(jsonPath("$.totalQuestions").value(1))
            .andExpect(jsonPath("$.timeLimitMinutes").exists())
            .andExpect(jsonPath("$.startedAt").exists())
            .andExpect(jsonPath("$.firstQuestion").doesNotExist());
}
```

**Add new test**:
```java
@Test
@DisplayName("startAttempt with no time limit returns null timeLimitMinutes")
void startAttempt_noTimeLimit_returnsNullTimeLimit() throws Exception {
    // Given
    Quiz quizWithoutTimer = new Quiz();
    quizWithoutTimer.setId(quizId);
    quizWithoutTimer.setIsTimerEnabled(false);
    quizWithoutTimer.setQuestions(Arrays.asList(question));
    quizWithoutTimer.setCreatedBy(user);
    
    when(quizRepository.findById(quizId)).thenReturn(Optional.of(quizWithoutTimer));
    
    var request = new StartAttemptRequest(AttemptMode.ALL_AT_ONCE);
    
    // When & Then
    mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
            .with(user("testuser"))
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.timeLimitMinutes").isEmpty());
}
```

#### 3.2.2 End-to-End Flow Test
**Add new test** to verify the complete flow works:

```java
@Test
@DisplayName("Complete attempt flow: start -> get current question -> submit answer")
void completeAttemptFlow() throws Exception {
    // Given
    var startRequest = new StartAttemptRequest(AttemptMode.ONE_BY_ONE);
    
    // When & Then - Start attempt
    MvcResult startResult = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
            .with(user("testuser"))
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(startRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptId").exists())
            .andExpect(jsonPath("$.firstQuestion").doesNotExist())
            .andReturn();
    
    String responseJson = startResult.getResponse().getContentAsString();
    JsonNode responseNode = objectMapper.readTree(responseJson);
    UUID attemptId = UUID.fromString(responseNode.get("attemptId").asText());
    
    // When & Then - Get current question
    MvcResult questionResult = mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", attemptId)
            .with(user("testuser")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.question.id").exists())
            .andReturn();
    
    String questionJson = questionResult.getResponse().getContentAsString();
    JsonNode questionNode = objectMapper.readTree(questionJson);
    UUID questionId = UUID.fromString(questionNode.get("question").get("id").asText());
    
    // When & Then - Submit answer
    var answerRequest = new AnswerSubmissionRequest(questionId, objectMapper.createObjectNode());
    
    mockMvc.perform(post("/api/v1/attempts/{attemptId}/answers", attemptId)
            .with(user("testuser"))
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(answerRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.questionId").value(questionId.toString()));
}
```

### 4. Update Frontend Integration Tests

#### 4.1 Update Frontend Tests
If there are any frontend tests that expect `firstQuestion` in the response, update them to:
1. Expect the new metadata structure
2. Call `getCurrentQuestion` after starting the attempt

### 5. Migration Strategy

#### 5.1 Backward Compatibility
Since this is a breaking change, consider:
1. **Version the API**: Use `/api/v2/attempts` for the new structure
2. **Deprecation period**: Keep old endpoint working for a transition period
3. **Client updates**: Update all frontend clients to use new endpoint

#### 5.2 Recommended Approach
Given this is fixing a bug, recommend immediate deployment with frontend updates.

### 6. Validation Checklist

- [ ] StartAttemptResponse DTO updated
- [ ] AttemptServiceImpl.startAttempt() updated
- [ ] Controller documentation updated
- [ ] Unit tests updated and passing
- [ ] Integration tests updated and passing
- [ ] Frontend integration tests updated
- [ ] API documentation updated
- [ ] Manual testing of complete flow
- [ ] Frontend updated to use new response structure

### 7. Expected Benefits

1. **Bug Fix**: Eliminates 409 Conflict error on first answer submission
2. **Clean Architecture**: Single source of truth for question selection
3. **Better UX**: Frontend gets useful metadata upfront
4. **Maintainability**: Easier to modify question ordering logic
5. **Future-Proof**: Easy to add more metadata fields

### 8. Risk Assessment

**Low Risk**:
- Changes are isolated to attempt start endpoint
- Existing question submission logic remains unchanged
- Clear test coverage for all changes

**Mitigation**:
- Comprehensive testing of the complete flow
- Gradual rollout with monitoring
- Rollback plan if issues arise 