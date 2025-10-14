# Result Controller API Reference

Complete frontend integration guide for quiz analytics endpoints exposed under `/api/v1/quizzes/{quizId}`. This document is self-contained and includes all DTOs, authorization requirements, endpoint behavior, error semantics, and practical integration examples needed to build quiz analytics features.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Response DTOs](#response-dtos)
- [Endpoints](#endpoints)
  - [Get Quiz Result Summary](#get-quiz-result-summary)
  - [Get Quiz Leaderboard](#get-quiz-leaderboard)
- [Error Handling](#error-handling)
- [Data Freshness & Caching](#data-freshness--caching)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/quizzes/{quizId}`
* **Primary Endpoints**:
  * `GET /results` – Aggregated quiz analytics with per-question statistics
  * `GET /leaderboard` – Top performers ranked by best score
* **Authentication**: Context-sensitive (see Authorization Matrix)
* **Authorization Model**: Hybrid - Public quizzes allow anonymous access, private quizzes require ownership or moderator permissions
* **Content-Type**: `application/json` for all responses
* **Error Format**: Standardized `ErrorResponse` or `ProblemDetail` body
* **Caching**: Analytics data is cached with configurable TTL (default: 10 minutes)

---

## Authorization Matrix

Analytics access is governed by quiz visibility and ownership:

| Quiz Visibility/Status | Who Can Access? | Required Permission(s) | Notes |
| --- | --- | --- | --- |
| **Public & Published** | Anyone (authentication optional) | None | Anonymous access allowed |
| **Private / Draft / Archived** | Quiz owner | Authenticated user must own the quiz | Ownership verified |
| **Private / Draft / Archived** | Moderators/Admins | `QUIZ_MODERATE` OR `QUIZ_ADMIN` | Override ownership requirement |
| **All other cases** | Denied | - | Returns `403 Forbidden` |

**Authorization Flow**:
1. Check quiz visibility
2. If public & published → allow access
3. If private/draft/archived:
   - Verify user is authenticated (else `401`)
   - Check if user owns the quiz (allow if yes)
   - Check for `QUIZ_MODERATE` or `QUIZ_ADMIN` permissions (allow if yes)
   - Otherwise → `403 Forbidden`

---

## Response DTOs

### QuizResultSummaryDto

**Returned by**: `GET /quizzes/{quizId}/results`

| Field | Type | Description |
| --- | --- | --- |
| `quizId` | UUID | Quiz identifier |
| `attemptsCount` | long | Total number of completed attempts |
| `averageScore` | number | Mean score across all attempts (0-100) |
| `bestScore` | number | Highest score achieved (0-100) |
| `worstScore` | number | Lowest score achieved (0-100) |
| `passRate` | number | Percentage of attempts with score ≥ 50% |
| `questionStats` | array of `QuestionStatsDto` | Per-question performance metrics |

**Example**:
```json
{
  "quizId": "ad516c2c-6bb9-4ef2-8f54-b1f58e5d2d8d",
  "attemptsCount": 128,
  "averageScore": 73.5,
  "bestScore": 98.0,
  "worstScore": 45.0,
  "passRate": 62.5,
  "questionStats": [
    {
      "questionId": "f0a5a2d6-3f06-4fcb-8d73-04cdd2f1cfa1",
      "timesAsked": 128,
      "timesCorrect": 90,
      "correctRate": 70.3125
    },
    {
      "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "timesAsked": 128,
      "timesCorrect": 115,
      "correctRate": 89.84375
    }
  ]
}
```

**Notes**:
- When `attemptsCount` is 0, all score fields default to `0.0`
- Pass rate counts attempts with at least 50% correct answers
- Question stats are calculated fresh on each request for accuracy

### QuestionStatsDto

**Used in**: `QuizResultSummaryDto.questionStats`

| Field | Type | Description |
| --- | --- | --- |
| `questionId` | UUID | Question identifier |
| `timesAsked` | long | Number of times this question was answered |
| `timesCorrect` | long | Number of correct answers |
| `correctRate` | number | Percentage of correct answers (0-100) |

**Calculation**: `correctRate = (timesCorrect / timesAsked) × 100`

**Example**:
```json
{
  "questionId": "f0a5a2d6-3f06-4fcb-8d73-04cdd2f1cfa1",
  "timesAsked": 128,
  "timesCorrect": 90,
  "correctRate": 70.3125
}
```

**Notes**:
- `correctRate` is `0.0` when `timesAsked` is 0
- All questions in the quiz are included, even if never attempted
- Useful for identifying difficult questions (low `correctRate`)

### LeaderboardEntryDto

**Returned by**: `GET /quizzes/{quizId}/leaderboard`

| Field | Type | Description |
| --- | --- | --- |
| `userId` | UUID | User identifier |
| `username` | string | Display username |
| `bestScore` | number | Highest score achieved by this user (0-100) |

**Example**:
```json
{
  "userId": "be77a1b4-0d6e-4c04-9ce1-25bd8a8c2e15",
  "username": "quizmaster",
  "bestScore": 98.0
}
```

**Notes**:
- Leaderboard shows each user only once with their best score
- Entries are sorted by `bestScore` in descending order
- Only completed attempts are considered

---

## Endpoints

### Get Quiz Result Summary

```
GET /api/v1/quizzes/{quizId}/results
```

**Purpose**: Retrieve comprehensive analytics for a quiz, including aggregate statistics and per-question performance data.

**Path Parameters**:
- `{quizId}` (UUID, required) - Quiz identifier

**Authentication & Authorization**:
- **Public quizzes**: No authentication required
- **Private quizzes**: Requires authentication AND one of:
  - Quiz ownership (creator)
  - `QUIZ_MODERATE` permission
  - `QUIZ_ADMIN` permission

**Success Response**: `200 OK` - `QuizResultSummaryDto`

```json
{
  "quizId": "ad516c2c-6bb9-4ef2-8f54-b1f58e5d2d8d",
  "attemptsCount": 128,
  "averageScore": 73.5,
  "bestScore": 98.0,
  "worstScore": 45.0,
  "passRate": 62.5,
  "questionStats": [
    {
      "questionId": "f0a5a2d6-3f06-4fcb-8d73-04cdd2f1cfa1",
      "timesAsked": 128,
      "timesCorrect": 90,
      "correctRate": 70.3125
    },
    {
      "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "timesAsked": 128,
      "timesCorrect": 115,
      "correctRate": 89.84375
    },
    {
      "questionId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "timesAsked": 128,
      "timesCorrect": 64,
      "correctRate": 50.0
    }
  ]
}
```

**Empty State Example** (no attempts):
```json
{
  "quizId": "ad516c2c-6bb9-4ef2-8f54-b1f58e5d2d8d",
  "attemptsCount": 0,
  "averageScore": 0.0,
  "bestScore": 0.0,
  "worstScore": 0.0,
  "passRate": 0.0,
  "questionStats": []
}
```

**Behavior Notes**:
- Aggregate fields (attempts count, scores, pass rate) are served from a cached snapshot
- Per-question stats are recalculated fresh on each request for accuracy
- Pass rate counts attempts with score ≥ 50%
- Questions with zero attempts show `correctRate: 0.0`
- Cache TTL is configurable (default: 10 minutes)

**Error Responses**:
- `400 Bad Request` - Invalid UUID format
- `401 Unauthorized` - Missing/expired token for private quiz
- `403 Forbidden` - User lacks access to private quiz
- `404 Not Found` - Quiz does not exist
- `500 Internal Server Error` - Unexpected server error

---

### Get Quiz Leaderboard

```
GET /api/v1/quizzes/{quizId}/leaderboard
```

**Purpose**: Retrieve top participants ranked by their highest score on the quiz.

**Path Parameters**:
- `{quizId}` (UUID, required) - Quiz identifier

**Query Parameters**:
- `top` (integer, optional, default: `10`) - Maximum number of entries to return
  - Values ≤ 0 return empty array
  - Recommend capping at 100 for performance

**Authentication & Authorization**:
- Same rules as summary endpoint (see Authorization Matrix)
- **Public quizzes**: No authentication required
- **Private quizzes**: Requires ownership or moderator permissions

**Success Response**: `200 OK` - Array of `LeaderboardEntryDto`

```json
[
  {
    "userId": "be77a1b4-0d6e-4c04-9ce1-25bd8a8c2e15",
    "username": "quizmaster",
    "bestScore": 98.0
  },
  {
    "userId": "ce51ec53-f251-462a-91eb-02ecaa8c2de0",
    "username": "runnerup",
    "bestScore": 95.5
  },
  {
    "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "username": "learner123",
    "bestScore": 87.0
  }
]
```

**Empty State Example**:
```json
[]
```

**Behavior Notes**:
- Results sorted by `bestScore` in descending order
- Each user appears only once with their best score
- Only completed attempts are considered
- If fewer than `top` users have completed the quiz, fewer entries are returned
- Results are deterministic and safe to cache

**Error Responses**:
- `400 Bad Request` - Invalid UUID format or malformed parameters
- `401 Unauthorized` - Missing/expired token for private quiz
- `403 Forbidden` - User lacks access to private quiz
- `404 Not Found` - Quiz does not exist
- `500 Internal Server Error` - Unexpected server error

---

## Error Handling

All errors return a standardized `ErrorResponse` or `ProblemDetail` format:

```json
{
  "timestamp": "2025-06-01T10:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "details": ["You do not have permission to access this quiz's analytics"]
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `400` | Bad Request | Invalid UUID format, malformed parameters |
| `401` | Unauthorized | Missing or invalid authentication token (private quiz) |
| `403` | Forbidden | User lacks ownership or moderator permissions |
| `404` | Not Found | Quiz does not exist or is not accessible |
| `500` | Internal Server Error | Unexpected server error |

### Common Error Scenarios

**Private Quiz Without Authentication**:
```json
{
  "timestamp": "2025-06-01T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "details": ["Authentication required to access this quiz"]
}
```

**Private Quiz Without Permission**:
```json
{
  "timestamp": "2025-06-01T10:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "details": ["You do not have permission to view analytics for this quiz"]
}
```

**Quiz Not Found**:
```json
{
  "timestamp": "2025-06-01T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "details": ["Quiz with ID 'ad516c2c-6bb9-4ef2-8f54-b1f58e5d2d8d' not found"]
}
```

---

## Data Freshness & Caching

Analytics data is optimized with an intelligent caching strategy:

### Caching Strategy

**Aggregate Data (Cached)**:
- Attempts count, average/best/worst scores, pass rate
- Stored in snapshot table
- TTL: 10 minutes (configurable via `quizmaker.analytics.snapshot.max-age-seconds`)
- Auto-refreshed when an attempt completes (event-driven)
- Stale snapshots recomputed on-demand

**Per-Question Stats (Real-Time)**:
- Always calculated fresh from latest completed attempts
- Ensures accuracy for recently completed attempts
- No caching applied

### Performance Implications

**Fast Reads**:
- Repeated calls within TTL window are very fast (cached)
- No database aggregation needed for cached data

**Stale Data Handling**:
- First request after TTL expiry triggers recomputation
- Subsequent requests served from fresh cache
- Minimal latency impact (typically < 100ms for recompute)

**Event-Driven Updates**:
- Snapshots auto-update when attempts complete
- No manual refresh needed
- Optimistic locking prevents concurrent update conflicts

### Polling Recommendations

**Avoid Aggressive Polling**:
- Don't poll more frequently than every 5-10 minutes
- Analytics auto-refresh on completion events
- Excessive polling provides no benefit due to TTL

**Use WebSocket for Real-Time** (if available):
- Subscribe to quiz completion events
- Refresh analytics only when new data available
- More efficient than polling

---

## Integration Guide

### Fetch Quiz Analytics

**Display comprehensive quiz analytics**:

```javascript
const fetchQuizAnalytics = async (quizId) => {
  const response = await fetch(
    `/api/v1/quizzes/${quizId}/results`,
    {
      headers: {
        'Authorization': `Bearer ${token}` // Optional for public quizzes
      }
    }
  );

  if (!response.ok) {
    const error = await response.json();
    if (response.status === 401) {
      console.log('Authentication required');
      redirectToLogin();
      return;
    } else if (response.status === 403) {
      console.log('Access denied:', error.details);
      showAccessDeniedMessage();
      return;
    }
    throw error;
  }

  const analytics = await response.json();
  
  // Display summary stats
  console.log(`Total Attempts: ${analytics.attemptsCount}`);
  console.log(`Average Score: ${analytics.averageScore.toFixed(1)}%`);
  console.log(`Pass Rate: ${analytics.passRate.toFixed(1)}%`);
  
  // Identify difficult questions
  const difficultQuestions = analytics.questionStats
    .filter(q => q.correctRate < 50)
    .sort((a, b) => a.correctRate - b.correctRate);
  
  console.log('Most Difficult Questions:', difficultQuestions);
  
  return analytics;
};
```

---

### Display Leaderboard

**Show top performers with pagination**:

```javascript
const fetchLeaderboard = async (quizId, topN = 10) => {
  const response = await fetch(
    `/api/v1/quizzes/${quizId}/leaderboard?top=${topN}`,
    {
      headers: {
        'Authorization': `Bearer ${token}` // Optional for public quizzes
      }
    }
  );

  if (!response.ok) {
    const error = await response.json();
    console.error('Failed to fetch leaderboard:', error);
    return [];
  }

  const leaderboard = await response.json();
  return leaderboard;
};

// React component example
const LeaderboardComponent = ({ quizId }) => {
  const [leaderboard, setLeaderboard] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const loadLeaderboard = async () => {
      setLoading(true);
      try {
        const data = await fetchLeaderboard(quizId, 20);
        setLeaderboard(data);
      } catch (error) {
        console.error('Error loading leaderboard:', error);
      } finally {
        setLoading(false);
      }
    };

    loadLeaderboard();
  }, [quizId]);

  if (loading) return <div>Loading leaderboard...</div>;
  
  if (leaderboard.length === 0) {
    return <div>No one has completed this quiz yet. Be the first!</div>;
  }

  return (
    <div className="leaderboard">
      <h2>Top Performers</h2>
      <ol>
        {leaderboard.map((entry, index) => (
          <li key={entry.userId}>
            <span className="rank">#{index + 1}</span>
            <span className="username">{entry.username}</span>
            <span className="score">{entry.bestScore.toFixed(1)}%</span>
          </li>
        ))}
      </ol>
    </div>
  );
};
```

---

### Handle Empty States

**Gracefully handle quizzes with no attempts**:

```javascript
const displayQuizStats = async (quizId) => {
  const analytics = await fetchQuizAnalytics(quizId);

  if (analytics.attemptsCount === 0) {
    return (
      <div className="empty-state">
        <p>No one has taken this quiz yet.</p>
        <button onClick={() => startQuiz(quizId)}>
          Be the first to take it!
        </button>
      </div>
    );
  }

  // Display full analytics
  return (
    <div className="quiz-analytics">
      <h2>Quiz Performance</h2>
      <div className="stats-grid">
        <StatCard 
          label="Total Attempts" 
          value={analytics.attemptsCount} 
        />
        <StatCard 
          label="Average Score" 
          value={`${analytics.averageScore.toFixed(1)}%`} 
        />
        <StatCard 
          label="Pass Rate" 
          value={`${analytics.passRate.toFixed(1)}%`} 
        />
        <StatCard 
          label="Best Score" 
          value={`${analytics.bestScore.toFixed(1)}%`} 
        />
      </div>

      <QuestionDifficultyChart 
        questions={analytics.questionStats} 
      />
    </div>
  );
};
```

---

### Identify Difficult Questions

**Highlight questions students struggle with**:

```javascript
const analyzeQuestionDifficulty = (questionStats) => {
  // Categorize questions by difficulty
  const categories = {
    easy: [],      // > 80% correct
    medium: [],    // 50-80% correct
    hard: [],      // 20-50% correct
    veryHard: []   // < 20% correct
  };

  questionStats.forEach(question => {
    const { correctRate } = question;
    
    if (correctRate > 80) {
      categories.easy.push(question);
    } else if (correctRate >= 50) {
      categories.medium.push(question);
    } else if (correctRate >= 20) {
      categories.hard.push(question);
    } else {
      categories.veryHard.push(question);
    }
  });

  // Display insights
  console.log('Question Difficulty Distribution:');
  console.log(`Easy (>80%): ${categories.easy.length}`);
  console.log(`Medium (50-80%): ${categories.medium.length}`);
  console.log(`Hard (20-50%): ${categories.hard.length}`);
  console.log(`Very Hard (<20%): ${categories.veryHard.length}`);

  // Flag questions that need review
  if (categories.veryHard.length > 0) {
    console.warn('Questions needing review:');
    categories.veryHard.forEach(q => {
      console.warn(`- Question ${q.questionId}: ${q.correctRate.toFixed(1)}% correct`);
    });
  }

  return categories;
};
```

---

### Caching Strategy

**Implement efficient client-side caching**:

```javascript
const analyticsCache = new Map();
const CACHE_TTL = 10 * 60 * 1000; // 10 minutes

const getCachedAnalytics = async (quizId, forceRefresh = false) => {
  const now = Date.now();
  const cached = analyticsCache.get(quizId);

  // Return cached data if fresh and not forcing refresh
  if (!forceRefresh && cached && (now - cached.timestamp) < CACHE_TTL) {
    console.log('Returning cached analytics');
    return cached.data;
  }

  // Fetch fresh data
  console.log('Fetching fresh analytics');
  const data = await fetchQuizAnalytics(quizId);

  // Update cache
  analyticsCache.set(quizId, {
    data: data,
    timestamp: now
  });

  return data;
};

// Invalidate cache when user completes an attempt
const onQuizAttemptComplete = (quizId) => {
  console.log('Attempt completed, invalidating cache');
  analyticsCache.delete(quizId);
  
  // Optionally pre-fetch fresh data
  getCachedAnalytics(quizId, true);
};
```

---

### Error Handling

**Comprehensive error handling for analytics**:

```javascript
const handleAnalyticsErrors = async (operation) => {
  try {
    return await operation();
  } catch (error) {
    const status = error.status || 500;
    const errorData = await error.json?.() || error;

    switch (status) {
      case 400:
        console.error('Invalid request:', errorData.details);
        showNotification('Invalid quiz ID', 'error');
        break;

      case 401:
        console.error('Authentication required');
        showNotification('Please log in to view analytics', 'info');
        redirectToLogin();
        break;

      case 403:
        console.error('Access denied:', errorData.details);
        showNotification(
          'You do not have permission to view this quiz analytics',
          'warning'
        );
        break;

      case 404:
        console.error('Quiz not found');
        showNotification('Quiz not found', 'error');
        navigateToQuizList();
        break;

      case 500:
        console.error('Server error:', errorData);
        showNotification(
          'Unable to load analytics. Please try again later.',
          'error'
        );
        break;

      default:
        console.error('Unexpected error:', errorData);
        showNotification('An unexpected error occurred', 'error');
    }
  }
};

// Usage
const safelyFetchAnalytics = async (quizId) => {
  return await handleAnalyticsErrors(() => 
    fetchQuizAnalytics(quizId)
  );
};
```

---

## Security Considerations

### Authorization Best Practices

1. **Public vs Private Quizzes**:
   - Always handle 401/403 errors gracefully
   - Hide analytics links for private quizzes the user doesn't own
   - Show appropriate messaging when access is denied

2. **Token Management**:
   - Include authorization token for all private quiz requests
   - Handle token expiry with automatic refresh
   - Clear tokens on logout

3. **Permission Checks**:
   - Verify user permissions client-side before showing analytics UI
   - Always respect server-side permission denials
   - Cache permission status to reduce API calls

### Data Privacy

1. **User Information**:
   - Leaderboard shows usernames publicly for public quizzes
   - Consider privacy implications when displaying user data
   - Allow users to opt-out of leaderboards (if supported)

2. **Analytics Visibility**:
   - Only quiz owners and moderators can see private quiz analytics
   - Aggregate data doesn't expose individual attempt details
   - Question stats don't reveal individual student performance

### Performance & Resource Management

1. **Avoid Over-Fetching**:
   - Don't poll analytics more frequently than TTL period
   - Use client-side caching to reduce server load
   - Invalidate cache only when necessary (e.g., new attempts)

2. **Leaderboard Limits**:
   - Cap `top` parameter at reasonable value (e.g., 100)
   - Implement pagination for large leaderboards
   - Warn users about performance impact of large requests

3. **Error Recovery**:
   - Implement retry logic with exponential backoff for 500 errors
   - Cache last successful response as fallback
   - Display graceful degradation when analytics unavailable

### Frontend Validation

1. **Input Validation**:
   - Validate quiz ID format before making requests
   - Validate `top` parameter is positive integer
   - Handle malformed responses gracefully

2. **State Management**:
   - Track loading states to prevent duplicate requests
   - Handle race conditions when multiple components fetch same data
   - Implement request cancellation for unmounted components

3. **User Experience**:
   - Show loading indicators during fetch
   - Display meaningful error messages
   - Provide retry options on failures
   - Handle empty states with clear messaging