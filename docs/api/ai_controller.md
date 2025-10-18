# AI Controller API Reference

Complete frontend integration guide for AI-powered endpoints. This document covers the conversational chat endpoint and admin analysis tools with all DTOs, retry behavior, and error handling needed for integration.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Endpoints](#endpoints)
  - [Chat Endpoints](#chat-endpoints)
  - [Admin Analysis Endpoints](#admin-analysis-endpoints)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Paths**: 
  - Chat: `/api/ai` (conversational AI)
  - Analysis: `/api/v1/ai-analysis` (admin diagnostics)
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization Model**: 
  - Chat: Any authenticated user
  - Analysis: Admin users only (`ROLE_ADMIN`)
* **Content-Type**: `application/json` for requests and responses
* **Retry Behavior**: Built-in exponential backoff with jitter for rate limit handling
* **Error Format**: All errors return `ProblemDetail` or `ErrorResponse` object

---

## Authorization Matrix

AI endpoints have different authorization requirements based on their purpose.

| Capability | Endpoint(s) | Authorization Rule | Notes |
| --- | --- | --- | --- |
| **Chat with AI** | `POST /api/ai/chat` | Authenticated user | Any user with valid token can send messages |
| **Analyze responses** | `POST /api/v1/ai-analysis/analyze` | `ROLE_ADMIN` | Admin-only diagnostic tool |

**Access Control**:
- Chat endpoint: Open to all authenticated users (ensure proper authentication is configured)
- Analysis endpoint: Restricted to admin users via role check
- No ownership concept - stateless request/response

**Usage Limits**:
- Server implements retry logic with exponential backoff
- Rate limits enforced at AI provider level (OpenAI, etc.)
- Maximum retries configurable (default: 5 attempts)

---

## Request DTOs

### ChatRequestDto

**Used by**: `POST /api/ai/chat`

Send a message to the AI assistant.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `message` | string | Yes | Non-blank, max 2000 characters | Message to send to AI |

**Example**:
```json
{
  "message": "Generate five sample compliance questions about data privacy"
}
```

**Validation Rules**:
- Message cannot be null, empty, or only whitespace
- Maximum length: 2000 characters
- Server validates before processing

---

## Response DTOs

### ChatResponseDto

**Returned by**: `POST /api/ai/chat`

| Field | Type | Description |
| --- | --- | --- |
| `message` | string | AI's response text |
| `model` | string | AI model used (e.g., "gpt-3.5-turbo", "gpt-4") |
| `latency` | integer (long) | Response time in milliseconds |
| `tokensUsed` | integer | Tokens consumed (actual or estimated) |
| `timestamp` | ISO 8601 datetime | Server-side generation timestamp |

**Example**:
```json
{
  "message": "Here are five compliance questions about data privacy:\n\n1. What is GDPR?\n2. How long can you retain personal data?\n...",
  "model": "gpt-4",
  "latency": 2340,
  "tokensUsed": 450,
  "timestamp": "2024-01-15T10:30:45.123Z"
}
```

**Field Notes**:
- `model`: Indicates which AI model processed the request (may vary by deployment)
- `latency`: Includes network time, AI processing, and retry delays
- `tokensUsed`: Exact count from provider metadata, or estimated (length ÷ 4) if unavailable
- `timestamp`: Server timestamp when response was generated

---

### AnalysisResultDto

**Returned by**: `POST /api/v1/ai-analysis/analyze`

Simple status response for analysis operations.

| Field | Type | Description |
| --- | --- | --- |
| `status` | string | "success" or "error" |
| `message` | string | Human-readable result message |

**Example (Success)**:
```json
{
  "status": "success",
  "message": "AI response analysis completed. Check application logs for details."
}
```

**Example (Error)**:
```json
{
  "status": "error",
  "message": "Error during analysis: Log file not found"
}
```

**Notes**:
- Analysis results are written to application logs, not returned in response
- This endpoint triggers log parsing and analysis
- Detailed findings must be viewed in server logs

---

## Endpoints

### Chat Endpoints

#### 1. Send Chat Message

```
POST /api/ai/chat
```

**Authorization**: Any authenticated user

**Request Body**: `ChatRequestDto`
```json
{
  "message": "What are the key principles of object-oriented programming?"
}
```

**Success Response**: `200 OK` - `ChatResponseDto`
```json
{
  "message": "The key principles of object-oriented programming are:\n\n1. Encapsulation: Bundling data and methods...\n2. Inheritance: Creating new classes from existing ones...\n3. Polymorphism: Objects of different types responding to the same method...\n4. Abstraction: Hiding complex implementation details...",
  "model": "gpt-4",
  "latency": 2150,
  "tokensUsed": 380,
  "timestamp": "2024-01-15T10:30:45.123Z"
}
```

**Error Responses**:
- `400` - Invalid message (blank, null, or > 2000 characters)
- `401` - Unauthorized (missing or invalid token)
- `429` - Rate limit exceeded after max retries (wrapped as 400 with specific message)
- `500` - AI service unavailable after retries

**Example Errors**:

**Validation Error**:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "detail": "Message cannot be blank"
}
```

**Rate Limit Error**:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "detail": "Rate limit exceeded after 5 attempts. Please try again later."
}
```

**Service Unavailable**:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "detail": "Failed to get AI response after 5 attempts: Connection timeout"
}
```

**Retry Behavior**:
- Server automatically retries up to 5 times (configurable)
- Uses exponential backoff: 1s, 2s, 4s, 8s, 16s (with jitter)
- Maximum delay capped at 60 seconds
- Client sees final response or error after all retries

---

### Admin Analysis Endpoints

#### 2. Analyze AI Responses

```
POST /api/v1/ai-analysis/analyze
```

**Required Role**: `ROLE_ADMIN`

**No Request Body**

Triggers analysis of logged AI responses to identify patterns and issues.

**Success Response**: `200 OK`
```json
{
  "status": "success",
  "message": "AI response analysis completed. Check application logs for details."
}
```

**Error Response**: `500 Internal Server Error`
```json
{
  "status": "error",
  "message": "Error during analysis: Log directory not accessible"
}
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Not an admin user
- `500` - Analysis failed

**What It Does**:
- Scans recent AI response log files
- Analyzes question generation quality
- Identifies non-compliance with instructions
- Detects common issues (missing JSON, extra text, etc.)
- Aggregates statistics by question type and difficulty

**Content Shuffling**:
- AI-generated question content is automatically shuffled at save time to remove positional bias
- Supported question types: MCQ_SINGLE, MCQ_MULTI, ORDERING, MATCHING, COMPLIANCE, HOTSPOT
- ORDERING questions: correctOrder is captured BEFORE shuffling to preserve grading accuracy
- Multiple shuffle points: save time (removes AI bias), attempt time (prevents pattern recognition), export time (prevents answer sharing)
- Shuffling ensures fair presentation of options and reduces AI bias patterns
- Writes findings to application logs

**When to Use**:
- Debugging AI generation quality issues
- Monitoring AI response patterns
- Troubleshooting question generation problems
- Quality assurance checks

**Important Notes**:
- Analysis results are NOT returned in the API response
- Results must be viewed in server application logs
- Endpoint is idempotent - safe to call multiple times
- No-op if no log files exist (doesn't error)
- Intended for admin consoles and internal tooling

---

## Error Handling

### Error Response Format

Chat endpoint uses standard error format:

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "detail": "Message must not exceed 2000 characters"
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `400` | Bad Request | Invalid message, rate limit exceeded, validation errors |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Analysis endpoint accessed without admin role |
| `500` | Internal Server Error | AI service unavailable, unexpected errors |

### Common Error Scenarios

**Empty Message**:
```json
{
  "status": 400,
  "detail": "Message cannot be blank"
}
```

**Message Too Long**:
```json
{
  "status": 400,
  "detail": "Message must not exceed 2000 characters"
}
```

**Rate Limit Exceeded**:
```json
{
  "status": 400,
  "detail": "Rate limit exceeded after 5 attempts. Please try again later."
}
```

**AI Service Down**:
```json
{
  "status": 500,
  "detail": "Failed to get AI response after 5 attempts: Service temporarily unavailable"
}
```

**Analysis Failed**:
```json
{
  "status": 500,
  "error": {
    "status": "error",
    "message": "Error during analysis: Unable to read log files"
  }
}
```

---

## Integration Guide

### Basic Chat Integration

**Simple chat request**:
```javascript
const sendChatMessage = async (message) => {
  try {
    const response = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ message })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.detail || 'Chat request failed');
    }

    const data = await response.json();
    return data;
  } catch (error) {
    console.error('Chat error:', error);
    throw error;
  }
};

// Usage
const result = await sendChatMessage('Explain polymorphism in Java');
console.log('AI response:', result.message);
console.log('Model used:', result.model);
console.log('Response time:', result.latency, 'ms');
console.log('Tokens consumed:', result.tokensUsed);
```

---

### Chat UI with Loading State

**Handle retry delays with loading indicator**:
```javascript
const ChatComponent = () => {
  const [message, setMessage] = useState('');
  const [response, setResponse] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleSendMessage = async () => {
    if (!message.trim()) {
      setError('Message cannot be empty');
      return;
    }

    if (message.length > 2000) {
      setError('Message too long (max 2000 characters)');
      return;
    }

    setLoading(true);
    setError(null);
    setResponse(null);

    try {
      const startTime = Date.now();
      
      const res = await fetch('/api/ai/chat', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${getToken()}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ message })
      });

      const elapsedTime = Date.now() - startTime;

      if (!res.ok) {
        const errorData = await res.json();
        
        if (res.status === 400 && errorData.detail.includes('Rate limit')) {
          setError('AI is currently busy. Please try again in a moment.');
        } else {
          setError(errorData.detail || 'Failed to get response');
        }
        return;
      }

      const data = await res.json();
      setResponse(data);
      
      // Show total time including retries
      console.log(`Response received in ${elapsedTime}ms (AI latency: ${data.latency}ms)`);
      
    } catch (error) {
      setError('Network error. Please check your connection.');
      console.error('Chat error:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <textarea
        value={message}
        onChange={(e) => setMessage(e.target.value)}
        placeholder="Ask the AI assistant..."
        maxLength={2000}
      />
      <p>{message.length}/2000 characters</p>
      
      <button onClick={handleSendMessage} disabled={loading}>
        {loading ? 'Thinking...' : 'Send'}
      </button>

      {error && <div className="error">{error}</div>}
      
      {response && (
        <div className="response">
          <p><strong>AI ({response.model}):</strong></p>
          <p>{response.message}</p>
          <p className="meta">
            Response time: {response.latency}ms | 
            Tokens: {response.tokensUsed}
          </p>
        </div>
      )}
    </div>
  );
};
```

---

### Streaming-Like Experience

**Show typing indicator during potential retries**:
```javascript
const sendWithTypingIndicator = async (message) => {
  // Show typing indicator
  showTypingIndicator();

  const startTime = Date.now();
  let hasShownDelayWarning = false;

  // Set timeout to warn user if response is slow
  const warningTimeout = setTimeout(() => {
    if (!hasShownDelayWarning) {
      showWarning('AI is taking longer than usual, please wait...');
      hasShownDelayWarning = true;
    }
  }, 5000); // Show warning after 5 seconds

  try {
    const response = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ message })
    });

    clearTimeout(warningTimeout);
    hideTypingIndicator();

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.detail);
    }

    const data = await response.json();
    const totalTime = Date.now() - startTime;

    console.log(`Total request time: ${totalTime}ms (AI latency: ${data.latency}ms)`);
    
    // Show if retries occurred (total time >> latency)
    if (totalTime > data.latency * 1.5) {
      console.log('Note: Response included retry delays');
    }

    return data;
  } catch (error) {
    clearTimeout(warningTimeout);
    hideTypingIndicator();
    throw error;
  }
};
```

---

### Admin Analysis

**Trigger AI response analysis**:
```javascript
const analyzeAiResponses = async () => {
  try {
    const response = await fetch('/api/v1/ai-analysis/analyze', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${adminToken}`
      }
    });

    const result = await response.json();

    if (result.status === 'success') {
      console.log('✓ Analysis completed');
      console.log('→ Check server logs for detailed findings');
      alert('Analysis completed. View server logs for results.');
    } else {
      console.error('✗ Analysis failed:', result.message);
      alert(`Analysis failed: ${result.message}`);
    }
  } catch (error) {
    console.error('Network error:', error);
  }
};

// Usage in admin panel
<button onClick={analyzeAiResponses}>
  Analyze Recent AI Responses
</button>
```

---

### Token Usage Tracking

**Track AI token consumption**:
```javascript
let totalTokensUsed = 0;
const chatHistory = [];

const sendMessageWithTracking = async (message) => {
  const response = await fetch('/api/ai/chat', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ message })
  });

  if (!response.ok) {
    throw new Error('Chat failed');
  }

  const data = await response.json();

  // Track usage
  totalTokensUsed += data.tokensUsed;
  chatHistory.push({
    userMessage: message,
    aiResponse: data.message,
    model: data.model,
    tokensUsed: data.tokensUsed,
    timestamp: data.timestamp
  });

  // Update UI with usage stats
  updateUsageDisplay(totalTokensUsed, chatHistory.length);

  return data;
};

const updateUsageDisplay = (tokens, messageCount) => {
  document.getElementById('token-counter').textContent = 
    `${tokens} tokens used across ${messageCount} messages`;
};
```

---

### Error Handling with Retry Logic

**Handle rate limits gracefully**:
```javascript
const sendMessageWithRetry = async (message, maxClientRetries = 2) => {
  let clientRetryCount = 0;

  while (clientRetryCount <= maxClientRetries) {
    try {
      const response = await fetch('/api/ai/chat', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ message })
      });

      if (!response.ok) {
        const error = await response.json();

        // Server already retried 5 times, but we can add client-side retry
        if (error.detail && error.detail.includes('Rate limit exceeded')) {
          if (clientRetryCount < maxClientRetries) {
            clientRetryCount++;
            const backoffTime = Math.pow(2, clientRetryCount) * 60000; // 2min, 4min
            
            console.log(`Client-side retry ${clientRetryCount}/${maxClientRetries} after ${backoffTime}ms`);
            showMessage(`Rate limited. Retrying in ${backoffTime/1000}s...`);
            
            await new Promise(resolve => setTimeout(resolve, backoffTime));
            continue;
          }
        }

        throw new Error(error.detail);
      }

      const data = await response.json();
      return data;
      
    } catch (error) {
      if (clientRetryCount >= maxClientRetries) {
        throw error;
      }
      clientRetryCount++;
    }
  }

  throw new Error('Failed after all retries');
};
```

---

### Progressive Loading

**Show progressive latency warnings**:
```javascript
const sendWithProgressiveFeedback = async (message) => {
  const statusElement = document.getElementById('status');
  
  statusElement.textContent = 'Sending to AI...';
  const startTime = Date.now();

  // Progressive timeout warnings
  const timeout3s = setTimeout(() => {
    statusElement.textContent = 'AI is processing (this may take a moment)...';
  }, 3000);

  const timeout10s = setTimeout(() => {
    statusElement.textContent = 'Still processing (server may be retrying)...';
  }, 10000);

  const timeout30s = setTimeout(() => {
    statusElement.textContent = 'Taking longer than usual (please wait)...';
  }, 30000);

  try {
    const response = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ message })
    });

    // Clear all timeouts
    clearTimeout(timeout3s);
    clearTimeout(timeout10s);
    clearTimeout(timeout30s);

    if (!response.ok) {
      const error = await response.json();
      statusElement.textContent = `Error: ${error.detail}`;
      return null;
    }

    const data = await response.json();
    const totalTime = Date.now() - startTime;

    statusElement.textContent = `Response received (${totalTime}ms total, ${data.latency}ms AI processing)`;
    
    return data;
  } catch (error) {
    clearTimeout(timeout3s);
    clearTimeout(timeout10s);
    clearTimeout(timeout30s);
    
    statusElement.textContent = 'Network error occurred';
    throw error;
  }
};
```

---

## Retry Configuration

### Server-Side Retry Settings

The server implements automatic retry with exponential backoff:

| Setting | Default Value | Description |
| --- | --- | --- |
| `maxRetries` | 5 | Maximum retry attempts |
| `baseDelayMs` | 1000 | Initial delay (1 second) |
| `maxDelayMs` | 60000 | Maximum delay cap (60 seconds) |
| `jitterFactor` | 0.25 | Randomization factor (±25%) |

**Retry Delays** (approximate with jitter):
- Attempt 1: Immediate
- Attempt 2: ~1 second
- Attempt 3: ~2 seconds
- Attempt 4: ~4 seconds
- Attempt 5: ~8 seconds
- Attempt 6: ~16 seconds

**Total Maximum Wait**: ~31 seconds (sum of delays)

### Rate Limit Detection

Server automatically detects rate limit errors from AI providers:

**Detected Patterns**:
- HTTP 429 status codes
- Error messages containing: "rate limit", "rate_limit_exceeded", "Too Many Requests"
- Provider-specific codes: "TPM" (tokens per minute), "RPM" (requests per minute)

**Behavior**:
- Rate limit errors trigger exponential backoff
- Non-rate-limit errors trigger immediate retry
- All retries exhausted → error returned to client

---

## Security Considerations

### Authentication & Authorization

1. **Token Required**: All endpoints require valid JWT token
2. **Admin Restriction**: Analysis endpoint restricted to `ROLE_ADMIN`
3. **Token Validation**: Tokens validated on every request
4. **Session Management**: No server-side session state

### Input Validation

1. **Message Length**: Enforced at 2000 characters
2. **Content Sanitization**: Consider sanitizing user input client-side
3. **Injection Prevention**: Server validates and sanitizes before AI calls
4. **XSS Protection**: Sanitize AI responses before displaying in HTML

### AI Response Safety

1. **Content Filtering**: AI provider may filter inappropriate content
2. **Response Validation**: Validate AI responses before displaying
3. **Markdown Safety**: If rendering markdown from AI, use safe renderer
4. **Link Handling**: Sanitize any URLs in AI responses

### Data Privacy

1. **No Persistence**: Chat messages are not stored by default (check implementation)
2. **Logging**: Messages logged for debugging (avoid sending sensitive data)
3. **Token Tracking**: Token usage logged for monitoring
4. **PII Awareness**: Avoid sending personal information to AI

### Rate Limiting

1. **Provider Limits**: Subject to AI provider rate limits
2. **Retry Strategy**: Server handles retries automatically
3. **Backoff Respect**: Client should not spam requests
4. **Cost Awareness**: Token usage incurs costs

### Best Practices

**Frontend Implementation**:
- Validate message length client-side (2000 char limit)
- Show loading indicators during processing
- Handle long delays gracefully (up to 30+ seconds with retries)
- Display model information to users
- Track token usage for transparency
- Implement debouncing for auto-submit features
- Sanitize AI responses before rendering

**Error Handling**:
- Show user-friendly messages for rate limits
- Implement exponential backoff for client retries
- Handle network timeouts appropriately
- Log errors for debugging

**Performance**:
- Debounce user input (don't send on every keystroke)
- Show character count to help users stay under limit
- Consider queueing requests during high load
- Cache responses for identical queries (if appropriate)

**Security**:
- Never send passwords or tokens in messages
- Sanitize user input before submission
- Escape AI responses before HTML rendering
- Implement content security policy
- Monitor for prompt injection attempts

**Token Management**:
- Display token usage to users
- Warn when approaching usage limits
- Consider implementing usage quotas
- Track costs in admin dashboards

**Admin Analysis**:
- Restrict access to admin users only
- Schedule analysis during off-peak hours
- Monitor log file growth
- Implement log rotation

**Testing**:
- Test with various message lengths
- Test with special characters and code blocks
- Simulate rate limit scenarios
- Verify error message display
- Test with slow network conditions
- Verify admin-only access for analysis

---

## Model Information

### Supported AI Models

The `model` field in responses indicates which AI model processed the request:

| Model | Description | Typical Use Case |
| --- | --- | --- |
| `gpt-3.5-turbo` | Fast, cost-effective | General queries, simple questions |
| `gpt-4` | Advanced reasoning | Complex explanations, detailed analysis |
| `gpt-4-turbo` | Latest GPT-4 variant | Balance of speed and capability |

**Notes**:
- Model may vary based on server configuration
- Model selection is server-side (not configurable via API)
- Different models have different token costs
- Model information useful for debugging response quality

---

## Usage Tips

### Character Limit Optimization

**Maximize message quality within 2000 characters**:
```javascript
const optimizeMessage = (userInput) => {
  // Trim whitespace
  let message = userInput.trim();
  
  // If over limit, truncate intelligently
  if (message.length > 2000) {
    // Try to truncate at sentence boundary
    const truncated = message.substring(0, 1997);
    const lastPeriod = truncated.lastIndexOf('.');
    const lastQuestion = truncated.lastIndexOf('?');
    const lastExclamation = truncated.lastIndexOf('!');
    
    const lastSentence = Math.max(lastPeriod, lastQuestion, lastExclamation);
    
    if (lastSentence > 1800) {
      // Good truncation point found
      message = message.substring(0, lastSentence + 1);
    } else {
      // Just truncate with ellipsis
      message = truncated + '...';
    }
  }
  
  return message;
};
```

---

### Token Estimation

**Estimate tokens before sending** (for UI display):
```javascript
const estimateTokens = (text) => {
  // Rough estimation: ~4 characters per token
  return Math.ceil(text.length / 4);
};

const showTokenEstimate = (message) => {
  const estimated = estimateTokens(message);
  document.getElementById('token-estimate').textContent = 
    `Estimated tokens: ~${estimated}`;
};
```

---

### Response Latency Monitoring

**Monitor and log response times**:
```javascript
const monitorLatency = async (message) => {
  const clientStartTime = performance.now();
  
  const response = await fetch('/api/ai/chat', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ message })
  });

  const clientEndTime = performance.now();
  const clientLatency = clientEndTime - clientStartTime;

  const data = await response.json();

  // Compare client-measured vs server-reported latency
  const networkOverhead = clientLatency - data.latency;

  console.log({
    serverLatency: data.latency,
    clientLatency: clientLatency,
    networkOverhead: networkOverhead,
    retryOverhead: clientLatency - data.latency > 5000 ? 'likely' : 'none',
    model: data.model,
    tokensUsed: data.tokensUsed
  });

  // Send metrics to analytics
  trackMetric('ai_chat_latency', {
    serverLatency: data.latency,
    clientLatency: clientLatency,
    model: data.model,
    tokensUsed: data.tokensUsed
  });

  return data;
};
```

---

## Common Use Cases

### 1. Help Documentation Assistant

```javascript
const askDocumentationQuestion = async (question) => {
  const enhancedMessage = `You are a helpful assistant for QuizMaker application. 
Answer the following question concisely: ${question}`;

  return await sendChatMessage(enhancedMessage);
};
```

---

### 2. Question Generation Assistance

```javascript
const generateQuestionIdeas = async (topic, questionType, count) => {
  const message = `Generate ${count} ${questionType} questions about ${topic}. 
Provide clear, educational questions suitable for a quiz.`;

  return await sendChatMessage(message);
};
```

**Note**: When questions are generated and saved to the database, the content is automatically shuffled to remove AI positional bias. This ensures fair presentation of multiple choice options, ordering items, and other question elements.

---

### 3. Content Improvement

```javascript
const improveQuizDescription = async (currentDescription) => {
  const message = `Improve this quiz description to be more engaging and clear: 
"${currentDescription}"
Keep it under 200 characters.`;

  const response = await sendChatMessage(message);
  return response.message;
};
```

---

### 4. Validation Assistance

```javascript
const validateQuestionQuality = async (questionText, questionType) => {
  const message = `Review this ${questionType} question for clarity and educational value: 
"${questionText}"
Provide brief feedback.`;

  const response = await sendChatMessage(message);
  return response.message;
};
```

---

## Troubleshooting

### Common Issues

**1. Slow Responses**
- **Symptom**: Requests taking 10-30+ seconds
- **Cause**: Server retrying due to rate limits or provider delays
- **Solution**: 
  - Show loading indicator
  - Inform user responses may take a moment
  - Consider implementing client-side timeout (60s)

**2. Rate Limit Errors**
- **Symptom**: "Rate limit exceeded" errors
- **Cause**: AI provider rate limits hit
- **Solution**:
  - Show user-friendly message
  - Suggest trying again in 1-2 minutes
  - Implement request queuing

**3. Long Messages Rejected**
- **Symptom**: 400 error for long messages
- **Cause**: Message exceeds 2000 character limit
- **Solution**:
  - Show character counter
  - Validate before sending
  - Help users condense messages

**4. Empty Response**
- **Symptom**: AI returns very short or unclear response
- **Cause**: Poor prompt quality or model limitations
- **Solution**:
  - Help users write better prompts
  - Show example prompts
  - Allow message regeneration

**5. Analysis Returns No Data**
- **Symptom**: Analysis succeeds but no findings
- **Cause**: No log files or no recent AI activity
- **Solution**:
  - Check server logs directly
  - Verify AI logging is enabled
  - Generate some AI activity first

### Debug Checklist

- [ ] Valid authentication token provided
- [ ] Message within 2000 character limit
- [ ] Message not empty or whitespace only
- [ ] Network connection stable
- [ ] Server AI provider configured correctly
- [ ] Check server logs for detailed errors
- [ ] Verify rate limits not exceeded
- [ ] For analysis: Ensure `ROLE_ADMIN` present

---

## API Evolution Notes

### Current Limitations

1. **No Conversation History**: Each request is stateless
2. **No Streaming**: Responses returned complete (no streaming support)
3. **Single Message**: Cannot send multiple messages in one request
4. **No Attachments**: Text-only messages
5. **Analysis Results**: Not returned via API, only in logs

### Potential Future Features

- Conversation history/context support
- Streaming responses (Server-Sent Events or WebSocket)
- Multi-turn conversations with message IDs
- File/image attachment support
- Structured response formats
- Analysis results as structured data
- User-specific rate limiting
- Token usage quotas

### Backward Compatibility

When integrating, be prepared for:
- New optional fields in responses
- Additional model types
- Enhanced error messages
- New analysis endpoints
- Possible authentication model changes

