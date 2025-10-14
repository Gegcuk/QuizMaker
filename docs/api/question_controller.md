# Question Controller API Reference

Complete frontend integration guide for `/api/v1/questions` REST endpoints. This document is self-contained and includes all DTOs, validation rules, content schemas, and error semantics needed to integrate question management features.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Enumerations](#enumerations)
- [Question Content Schemas](#question-content-schemas)
- [Endpoints](#endpoints)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/questions`
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization Model**: Hybrid - Permission-based for CRUD + ownership validation for quiz associations.
* **Content-Type**: `application/json` for requests and responses (except `204` responses)
* **Error Format**: All errors return `ErrorResponse` object
* **Content Validation**: Type-specific schemas validated server-side
* **Pagination**: List endpoint supports page-based pagination

---

## Authorization Matrix

Question endpoints use both permission-based and ownership-based authorization.

| Capability | Endpoint(s) | Required Permission(s) | Additional Rules |
| --- | --- | --- | --- |
| **Create question** | `POST /questions` | `QUESTION_CREATE` | Must own all referenced quizzes or be moderator/admin |
| **List questions** | `GET /questions` | None (authenticated) | Results filtered by quiz access |
| **List by quiz** | `GET /questions?quizId=X` | None (authenticated) | Must own quiz or quiz is public+published |
| **Get question** | `GET /questions/{id}` | None (authenticated) | Must own associated quiz or question in public+published quiz |
| **Update question** | `PATCH /questions/{id}` | `QUESTION_UPDATE` | Must own all associated quizzes |
| **Delete question** | `DELETE /questions/{id}` | `QUESTION_DELETE` | Must own all associated quizzes |

**Permission + Ownership Rules**:
- Create/Update/Delete require both permission AND quiz ownership
- Moderators and admins can access any quiz's questions
- Read operations allow access to public+published quiz questions
- Listing without quizId filter requires user to own quizzes

---

## Request DTOs

### CreateQuestionRequest

**Used by**: `POST /questions`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `type` | `QuestionType` enum | Yes | Valid enum value | Question type (determines content schema) |
| `difficulty` | `Difficulty` enum | Yes | Valid enum value | Difficulty level |
| `questionText` | string | Yes | 3-1000 characters | Question prompt text |
| `content` | object | Yes | Type-specific schema | Question-specific content (see schemas below) |
| `hint` | string | No | Max 500 characters | Optional hint text |
| `explanation` | string | No | Max 2000 characters | Answer explanation |
| `attachmentUrl` | string | No | Max 2048 characters | Optional image/media URL |
| `quizIds` | array of UUIDs | No | Must exist, user must own | Quizzes to associate with |
| `tagIds` | array of UUIDs | No | Must exist | Tags to associate with |

**Example (MCQ_SINGLE)**:
```json
{
  "type": "MCQ_SINGLE",
  "difficulty": "MEDIUM",
  "questionText": "What is the capital of France?",
  "content": {
    "options": [
      { "id": "1", "text": "London", "correct": false },
      { "id": "2", "text": "Paris", "correct": true },
      { "id": "3", "text": "Berlin", "correct": false },
      { "id": "4", "text": "Madrid", "correct": false }
    ]
  },
  "hint": "It's known as the City of Light",
  "explanation": "Paris is the capital and largest city of France.",
  "attachmentUrl": null,
  "quizIds": ["quiz-uuid-1", "quiz-uuid-2"],
  "tagIds": ["tag-uuid-1"]
}
```

---

### UpdateQuestionRequest

**Used by**: `PATCH /questions/{id}`

All required fields must be provided (full replacement, not partial update).

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `type` | `QuestionType` enum | Yes | Valid enum value | Question type |
| `difficulty` | `Difficulty` enum | Yes | Valid enum value | Difficulty level |
| `questionText` | string | Yes | 3-1000 characters | Question prompt |
| `content` | object | Yes | Type-specific schema | Question content |
| `hint` | string | No | Max 500 characters | Hint text (null to remove) |
| `explanation` | string | No | Max 2000 characters | Explanation (null to remove) |
| `attachmentUrl` | string | No | Max 2048 characters | Attachment URL (null to remove) |
| `quizIds` | array of UUIDs | No | null keeps existing, array replaces | Quiz associations |
| `tagIds` | array of UUIDs | No | null keeps existing, array replaces | Tag associations |

**Example**:
```json
{
  "type": "MCQ_SINGLE",
  "difficulty": "HARD",
  "questionText": "Which algorithm has O(n log n) complexity?",
  "content": {
    "options": [
      { "id": "1", "text": "Bubble Sort", "correct": false },
      { "id": "2", "text": "Merge Sort", "correct": true },
      { "id": "3", "text": "Selection Sort", "correct": false }
    ]
  },
  "hint": "Think about divide-and-conquer algorithms",
  "explanation": "Merge Sort uses divide-and-conquer strategy with O(n log n) time complexity.",
  "quizIds": ["quiz-uuid-1"],
  "tagIds": null
}
```

**Notes**:
- `quizIds: null` keeps existing quiz associations
- `quizIds: []` clears all quiz associations
- Same pattern for `tagIds`
- All required fields must be present even if unchanged

---

## Response DTOs

### QuestionDto

**Returned by**: All question endpoints

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Question identifier |
| `type` | `QuestionType` enum | Question type |
| `difficulty` | `Difficulty` enum | Difficulty level |
| `questionText` | string | Question prompt |
| `content` | object | Type-specific content |
| `hint` | string (nullable) | Hint text |
| `explanation` | string (nullable) | Answer explanation |
| `attachmentUrl` | string (nullable) | Attachment URL |
| `createdAt` | ISO 8601 datetime | Creation timestamp |
| `updatedAt` | ISO 8601 datetime | Last update timestamp |
| `quizIds` | array of UUIDs | Associated quiz IDs |
| `tagIds` | array of UUIDs | Associated tag IDs |

**Example**:
```json
{
  "id": "question-uuid-here",
  "type": "MCQ_SINGLE",
  "difficulty": "MEDIUM",
  "questionText": "What is polymorphism in OOP?",
  "content": {
    "options": [
      { "id": "1", "text": "Multiple inheritance", "correct": false },
      { "id": "2", "text": "Method overriding", "correct": true },
      { "id": "3", "text": "Data hiding", "correct": false }
    ]
  },
  "hint": "Related to method behavior",
  "explanation": "Polymorphism allows objects to take multiple forms, commonly through method overriding.",
  "attachmentUrl": null,
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-15T14:30:00Z",
  "quizIds": ["quiz-uuid-1", "quiz-uuid-2"],
  "tagIds": ["tag-uuid-1"]
}
```

---

### CreateQuestionResponse

**Returned by**: `POST /questions`

| Field | Type | Description |
| --- | --- | --- |
| `questionId` | UUID | Newly created question ID |

**Example**:
```json
{
  "questionId": "newly-created-question-uuid"
}
```

---

### Page\<QuestionDto\>

**Returned by**: `GET /questions`

Standard Spring pagination wrapper.

| Field | Type | Description |
| --- | --- | --- |
| `content` | array of `QuestionDto` | Page of questions |
| `totalElements` | integer | Total question count |
| `totalPages` | integer | Total page count |
| `number` | integer | Current page (0-indexed) |
| `size` | integer | Page size |
| `first` | boolean | Whether first page |
| `last` | boolean | Whether last page |
| `sort` | object | Sort information |

**Default Sort**: `createdAt` descending (newest first)

---

## Enumerations

### QuestionType

| Value | Description |
| --- | --- |
| `MCQ_SINGLE` | Multiple choice with single correct answer |
| `MCQ_MULTI` | Multiple choice with multiple correct answers |
| `TRUE_FALSE` | True/False question |
| `OPEN` | Open-ended text answer |
| `FILL_GAP` | Fill in the blank(s) |
| `ORDERING` | Put items in correct order |
| `MATCHING` | Match items between two lists |
| `COMPLIANCE` | Mark statements as compliant/non-compliant |
| `HOTSPOT` | Click correct regions on an image |

---

### Difficulty

| Value | Description |
| --- | --- |
| `EASY` | Easy difficulty level |
| `MEDIUM` | Medium difficulty level |
| `HARD` | Hard difficulty level |

---

## Question Content Schemas

Each question type requires specific content structure. Server validates these strictly.

### TRUE_FALSE

```json
{
  "answer": true
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `answer` | boolean | Yes | Must be boolean |

---

### MCQ_SINGLE

```json
{
  "options": [
    { "id": "1", "text": "Option A", "correct": false },
    { "id": "2", "text": "Option B", "correct": true },
    { "id": "3", "text": "Option C", "correct": false }
  ]
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `options` | array | Yes | Minimum 2 options |
| `options[].id` | string | Yes | Non-empty, unique |
| `options[].text` | string | Yes | Non-empty |
| `options[].correct` | boolean | Yes | Exactly 1 must be true |

---

### MCQ_MULTI

Same structure as MCQ_SINGLE but allows multiple `correct: true`.

```json
{
  "options": [
    { "id": "1", "text": "Java", "correct": true },
    { "id": "2", "text": "Python", "correct": true },
    { "id": "3", "text": "HTML", "correct": false }
  ]
}
```

**Validation**: At least 1 option must be `correct: true`

---

### OPEN

```json
{
  "answer": "Expected answer text"
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `answer` | string | Yes | Non-empty |

**Notes**:
- Comparison is case-insensitive with trimming
- Partial matching may be implemented server-side

---

### FILL_GAP

```json
{
  "text": "Java is a {0} language developed by {1}.",
  "gaps": [
    { "id": 0, "answer": "programming" },
    { "id": 1, "answer": "Sun Microsystems" }
  ]
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `text` | string | Yes | Contains {0}, {1}, etc. placeholders |
| `gaps` | array | Yes | Minimum 1 gap |
| `gaps[].id` | integer | Yes | Unique, matches placeholders |
| `gaps[].answer` | string | Yes | Non-empty |

---

### ORDERING

```json
{
  "items": [
    { "id": 1, "text": "Step 1" },
    { "id": 2, "text": "Step 2" },
    { "id": 3, "text": "Step 3" }
  ]
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `items` | array | Yes | 2-10 items |
| `items[].id` | integer | Yes | Unique |
| `items[].text` | string | Yes | Non-empty |

**Notes**:
- Order in content represents correct order
- User must rearrange during attempt

---

### MATCHING

```json
{
  "left": [
    { "id": 1, "text": "Java", "matchId": 10 },
    { "id": 2, "text": "Python", "matchId": 20 }
  ],
  "right": [
    { "id": 10, "text": "Static typing" },
    { "id": 20, "text": "Dynamic typing" }
  ]
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `left` | array | Yes | Minimum 2 items |
| `left[].id` | integer | Yes | Unique |
| `left[].text` | string | Yes | Non-empty |
| `left[].matchId` | integer | Yes | Must exist in right[] |
| `right` | array | Yes | Minimum 2 items |
| `right[].id` | integer | Yes | Unique |
| `right[].text` | string | Yes | Non-empty |

---

### HOTSPOT

```json
{
  "imageUrl": "https://example.com/diagram.png",
  "regions": [
    { "id": 1, "x": 100, "y": 150, "width": 50, "height": 30, "correct": true },
    { "id": 2, "x": 200, "y": 100, "width": 60, "height": 40, "correct": false }
  ]
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `imageUrl` | string | Yes | Valid URL |
| `regions` | array | Yes | 2-6 regions |
| `regions[].id` | integer | Yes | Unique |
| `regions[].x` | integer | Yes | >= 0 |
| `regions[].y` | integer | Yes | >= 0 |
| `regions[].width` | integer | Yes | >= 0 |
| `regions[].height` | integer | Yes | >= 0 |
| `regions[].correct` | boolean | Yes | At least 1 must be true |

---

### COMPLIANCE

```json
{
  "statements": [
    { "id": 1, "text": "GDPR applies to EU citizens", "compliant": true },
    { "id": 2, "text": "No consent needed for marketing", "compliant": false }
  ]
}
```

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| `statements` | array | Yes | 2-6 statements |
| `statements[].id` | integer | Yes | Unique |
| `statements[].text` | string | Yes | Non-empty |
| `statements[].compliant` | boolean | Yes | At least 1 must be true |

---

## Endpoints

### 1. Create Question

```
POST /api/v1/questions
```

**Required Permission**: `QUESTION_CREATE`

**Request Body**: `CreateQuestionRequest`

**Success Response**: `201 Created`
```json
{
  "questionId": "newly-created-uuid"
}
```

**Error Responses**:
- `400` - Validation error (invalid content schema, field validation)
- `401` - Unauthorized
- `403` - Missing permission or don't own referenced quizzes
- `404` - Referenced quiz or tag not found

**Example**:
```javascript
const createQuestion = async (questionData) => {
  const response = await fetch('/api/v1/questions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(questionData)
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.details?.[0] || 'Create failed');
  }

  const { questionId } = await response.json();
  return questionId;
};
```

---

### 2. List Questions

```
GET /api/v1/questions
```

**Query Parameters**:
- `quizId` (UUID, optional) - Filter by quiz
- `pageNumber` (integer, optional) - Page number (0-indexed), default: 0
- `size` (integer, optional) - Page size (1-100), default: 20

**Example URLs**:
```
GET /api/v1/questions
GET /api/v1/questions?quizId=quiz-uuid-here
GET /api/v1/questions?quizId=quiz-uuid&pageNumber=0&size=20
```

**Success Response**: `200 OK` - `Page<QuestionDto>`

**Error Responses**:
- `401` - Unauthorized
- `403` - No quizId provided and user owns no quizzes, or don't have access to specified quiz
- `404` - Specified quiz not found

**Notes**:
- Without `quizId`: Returns questions from user's quizzes only
- With `quizId`: Must own quiz or quiz is public+published
- Default sort: newest first (createdAt desc)

---

### 3. Get Question by ID

```
GET /api/v1/questions/{id}
```

**Path Parameters**:
- `{id}` - Question UUID

**Success Response**: `200 OK` - `QuestionDto`

**Error Responses**:
- `401` - Unauthorized
- `403` - Don't own associated quiz and not public+published
- `404` - Question not found

---

### 4. Update Question

```
PATCH /api/v1/questions/{id}
```

**Required Permission**: `QUESTION_UPDATE`

**Path Parameters**:
- `{id}` - Question UUID

**Request Body**: `UpdateQuestionRequest`

**Success Response**: `200 OK` - `QuestionDto` (updated)

**Error Responses**:
- `400` - Validation error
- `401` - Unauthorized
- `403` - Missing permission or don't own associated quizzes
- `404` - Question, quiz, or tag not found

**Notes**:
- Must provide all required fields (not partial update)
- Content re-validated against type schema
- Ownership checked for all associated quizzes

---

### 5. Delete Question

```
DELETE /api/v1/questions/{id}
```

**Required Permission**: `QUESTION_DELETE`

**Path Parameters**:
- `{id}` - Question UUID

**Success Response**: `204 No Content`

**Error Responses**:
- `401` - Unauthorized
- `403` - Missing permission or don't own associated quizzes
- `404` - Question not found

**Notes**:
- Deletion is permanent
- Question removed from all associated quizzes
- Affects existing quiz attempts retroactively

---

## Error Handling

### ErrorResponse Format

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Validation Failed",
  "details": [
    "questionText: must be between 3 and 1000 characters",
    "content: At least one option must be marked as correct"
  ]
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `200` | OK | Successful GET or PATCH |
| `201` | Created | Successful POST |
| `204` | No Content | Successful DELETE |
| `400` | Bad Request | Validation errors, content schema violations |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Missing permission or quiz ownership |
| `404` | Not Found | Question, quiz, or tag doesn't exist |
| `500` | Internal Server Error | Unexpected server error |

### Common Error Scenarios

**Invalid Question Text Length**:
```json
{
  "status": 400,
  "error": "Validation Failed",
  "details": ["questionText: must be between 3 and 1000 characters"]
}
```

**Missing Correct Answer (MCQ_SINGLE)**:
```json
{
  "status": 400,
  "error": "Validation Failed",
  "details": ["content: Exactly one option must be marked as correct"]
}
```

**Duplicate Option IDs**:
```json
{
  "status": 400,
  "error": "Validation Failed",
  "details": ["content: Option IDs must be unique"]
}
```

**Quiz Not Found**:
```json
{
  "status": 404,
  "error": "Not Found",
  "details": ["Quiz not found: quiz-uuid-here"]
}
```

**No Permission to Access Quiz**:
```json
{
  "status": 403,
  "error": "Forbidden",
  "details": ["You don't have permission to add questions to this quiz"]
}
```

---

## Integration Guide

### Complete Question Manager

```javascript
class QuestionManager {
  constructor(token) {
    this.token = token;
    this.baseUrl = '/api/v1/questions';
  }

  async create(questionData) {
    const response = await fetch(this.baseUrl, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(questionData)
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.join(', ') || 'Create failed');
    }

    const { questionId } = await response.json();
    return questionId;
  }

  async list(quizId = null, page = 0, size = 20) {
    const params = new URLSearchParams({
      pageNumber: page,
      size: size
    });

    if (quizId) {
      params.append('quizId', quizId);
    }

    const response = await fetch(`${this.baseUrl}?${params}`, {
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (!response.ok) {
      throw new Error('Failed to list questions');
    }

    return await response.json();
  }

  async get(questionId) {
    const response = await fetch(`${this.baseUrl}/${questionId}`, {
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (response.status === 404) {
      throw new Error('Question not found');
    }

    if (response.status === 403) {
      throw new Error('Access denied to this question');
    }

    return await response.json();
  }

  async update(questionId, updateData) {
    const response = await fetch(`${this.baseUrl}/${questionId}`, {
      method: 'PATCH',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(updateData)
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.join(', ') || 'Update failed');
    }

    return await response.json();
  }

  async delete(questionId) {
    const response = await fetch(`${this.baseUrl}/${questionId}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (!response.ok && response.status !== 204) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Delete failed');
    }

    return true;
  }
}
```

---

### Question Form Builder

```javascript
const QuestionFormBuilder = ({ quizId, onComplete }) => {
  const [formData, setFormData] = useState({
    type: 'MCQ_SINGLE',
    difficulty: 'MEDIUM',
    questionText: '',
    content: { options: [] },
    hint: '',
    explanation: '',
    attachmentUrl: '',
    quizIds: quizId ? [quizId] : [],
    tagIds: []
  });

  const [errors, setErrors] = useState([]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setErrors([]);

    // Client-side validation
    const validation = validateQuestion(formData);
    if (!validation.valid) {
      setErrors(validation.errors);
      return;
    }

    try {
      const response = await fetch('/api/v1/questions', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(formData)
      });

      if (!response.ok) {
        const error = await response.json();
        setErrors(error.details || ['Failed to create question']);
        return;
      }

      const { questionId } = await response.json();
      onComplete(questionId);
    } catch (error) {
      setErrors(['Network error occurred']);
    }
  };

  const validateQuestion = (data) => {
    const errors = [];

    if (!data.questionText || data.questionText.length < 3) {
      errors.push('Question text must be at least 3 characters');
    }

    if (data.questionText.length > 1000) {
      errors.push('Question text must not exceed 1000 characters');
    }

    // Type-specific validation
    if (data.type === 'MCQ_SINGLE' || data.type === 'MCQ_MULTI') {
      if (!data.content.options || data.content.options.length < 2) {
        errors.push('Must have at least 2 options');
      }

      const correctCount = data.content.options?.filter(o => o.correct).length || 0;
      
      if (data.type === 'MCQ_SINGLE' && correctCount !== 1) {
        errors.push('Exactly one option must be correct');
      }

      if (data.type === 'MCQ_MULTI' && correctCount < 1) {
        errors.push('At least one option must be correct');
      }
    }

    return {
      valid: errors.length === 0,
      errors: errors
    };
  };

  // Render form based on question type...
  return (
    <form onSubmit={handleSubmit}>
      {/* Form fields */}
      {errors.length > 0 && (
        <div className="errors">
          {errors.map((err, idx) => (
            <div key={idx} className="error">{err}</div>
          ))}
        </div>
      )}
    </form>
  );
};
```

---

### MCQ Question Editor

```javascript
const McqQuestionEditor = ({ initialData, onSave }) => {
  const [questionText, setQuestionText] = useState(initialData?.questionText || '');
  const [options, setOptions] = useState(initialData?.content?.options || [
    { id: '1', text: '', correct: false },
    { id: '2', text: '', correct: false }
  ]);
  const [difficulty, setDifficulty] = useState(initialData?.difficulty || 'MEDIUM');
  const [hint, setHint] = useState(initialData?.hint || '');
  const [explanation, setExplanation] = useState(initialData?.explanation || '');

  const addOption = () => {
    const newId = (Math.max(...options.map(o => parseInt(o.id) || 0)) + 1).toString();
    setOptions([...options, { id: newId, text: '', correct: false }]);
  };

  const removeOption = (id) => {
    if (options.length <= 2) {
      alert('Must have at least 2 options');
      return;
    }
    setOptions(options.filter(o => o.id !== id));
  };

  const updateOption = (id, field, value) => {
    setOptions(options.map(o => 
      o.id === id ? { ...o, [field]: value } : o
    ));
  };

  const setCorrectOption = (id, isSingle = true) => {
    setOptions(options.map(o => ({
      ...o,
      correct: isSingle ? o.id === id : (o.id === id ? !o.correct : o.correct)
    })));
  };

  const handleSave = () => {
    const questionData = {
      type: isSingle ? 'MCQ_SINGLE' : 'MCQ_MULTI',
      difficulty,
      questionText,
      content: { options },
      hint: hint || null,
      explanation: explanation || null,
      quizIds: initialData?.quizIds || [],
      tagIds: initialData?.tagIds || []
    };

    onSave(questionData);
  };

  return (
    <div className="mcq-editor">
      <label>
        Question Text:
        <textarea
          value={questionText}
          onChange={(e) => setQuestionText(e.target.value)}
          maxLength={1000}
          placeholder="Enter your question..."
        />
        <span>{questionText.length}/1000</span>
      </label>

      <label>
        Difficulty:
        <select value={difficulty} onChange={(e) => setDifficulty(e.target.value)}>
          <option value="EASY">Easy</option>
          <option value="MEDIUM">Medium</option>
          <option value="HARD">Hard</option>
        </select>
      </label>

      <div className="options">
        <h4>Answer Options</h4>
        {options.map((option, idx) => (
          <div key={option.id} className="option">
            <input
              type="radio"
              name="correct"
              checked={option.correct}
              onChange={() => setCorrectOption(option.id)}
            />
            <input
              type="text"
              value={option.text}
              onChange={(e) => updateOption(option.id, 'text', e.target.value)}
              placeholder={`Option ${idx + 1}`}
            />
            <button onClick={() => removeOption(option.id)}>Remove</button>
          </div>
        ))}
        <button onClick={addOption}>Add Option</button>
      </div>

      <label>
        Hint (optional):
        <input
          type="text"
          value={hint}
          onChange={(e) => setHint(e.target.value)}
          maxLength={500}
        />
      </label>

      <label>
        Explanation (optional):
        <textarea
          value={explanation}
          onChange={(e) => setExplanation(e.target.value)}
          maxLength={2000}
        />
      </label>

      <button onClick={handleSave}>Save Question</button>
    </div>
  );
};
```

---

### Question List Browser

```javascript
const QuestionBrowser = ({ quizId, token }) => {
  const [questions, setQuestions] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadQuestions();
  }, [page, quizId]);

  const loadQuestions = async () => {
    setLoading(true);

    try {
      const params = new URLSearchParams({
        pageNumber: page,
        size: 20
      });

      if (quizId) {
        params.append('quizId', quizId);
      }

      const response = await fetch(`/api/v1/questions?${params}`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      if (!response.ok) {
        throw new Error('Failed to load questions');
      }

      const data = await response.json();
      setQuestions(data.content);
      setTotalPages(data.totalPages);
    } catch (error) {
      console.error('Load error:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (questionId, questionText) => {
    if (!confirm(`Delete question: "${questionText}"?`)) {
      return;
    }

    try {
      await fetch(`/api/v1/questions/${questionId}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${token}` }
      });

      loadQuestions(); // Refresh
    } catch (error) {
      alert('Failed to delete question');
    }
  };

  if (loading) return <div>Loading...</div>;

  return (
    <div className="question-browser">
      <h3>Questions {quizId && '(Filtered by Quiz)'}</h3>

      <div className="question-list">
        {questions.map(q => (
          <div key={q.id} className="question-card">
            <div className="header">
              <span className="type">{q.type}</span>
              <span className="difficulty">{q.difficulty}</span>
            </div>
            <p className="text">{q.questionText}</p>
            <div className="meta">
              <span>Quizzes: {q.quizIds.length}</span>
              <span>Tags: {q.tagIds.length}</span>
              <span>{new Date(q.createdAt).toLocaleDateString()}</span>
            </div>
            <div className="actions">
              <button onClick={() => viewQuestion(q.id)}>View</button>
              <button onClick={() => editQuestion(q.id)}>Edit</button>
              <button onClick={() => handleDelete(q.id, q.questionText)}>Delete</button>
            </div>
          </div>
        ))}
      </div>

      <div className="pagination">
        <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}>
          Previous
        </button>
        <span>Page {page + 1} of {totalPages}</span>
        <button onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}>
          Next
        </button>
      </div>
    </div>
  );
};
```

---

### Question Type-Specific Builders

**TRUE_FALSE Builder**:
```javascript
const TrueFalseBuilder = ({ onBuild }) => {
  const [answer, setAnswer] = useState(true);

  const build = () => ({
    content: { answer }
  });

  return (
    <div>
      <label>
        <input
          type="radio"
          checked={answer === true}
          onChange={() => setAnswer(true)}
        />
        True
      </label>
      <label>
        <input
          type="radio"
          checked={answer === false}
          onChange={() => setAnswer(false)}
        />
        False
      </label>
    </div>
  );
};
```

**FILL_GAP Builder**:
```javascript
const FillGapBuilder = ({ onBuild }) => {
  const [text, setText] = useState('');
  const [gaps, setGaps] = useState([]);

  const addGap = (position) => {
    const gapId = gaps.length;
    const placeholder = `{${gapId}}`;
    
    setText(prev => prev.slice(0, position) + placeholder + prev.slice(position));
    setGaps([...gaps, { id: gapId, answer: '' }]);
  };

  const updateGapAnswer = (id, answer) => {
    setGaps(gaps.map(g => g.id === id ? { ...g, answer } : g));
  };

  return (
    <div className="fill-gap-builder">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Enter text with {0}, {1} placeholders..."
      />

      <button onClick={() => addGap(text.length)}>
        Insert Gap
      </button>

      <div className="gaps">
        <h4>Gap Answers</h4>
        {gaps.map(gap => (
          <div key={gap.id}>
            <label>
              Gap {gap.id}:
              <input
                type="text"
                value={gap.answer}
                onChange={(e) => updateGapAnswer(gap.id, e.target.value)}
                placeholder="Correct answer"
              />
            </label>
          </div>
        ))}
      </div>
    </div>
  );
};
```

---

### Content Validator

```javascript
const validateQuestionContent = (type, content) => {
  const errors = [];

  switch (type) {
    case 'TRUE_FALSE':
      if (typeof content.answer !== 'boolean') {
        errors.push('Answer must be true or false');
      }
      break;

    case 'MCQ_SINGLE':
    case 'MCQ_MULTI':
      if (!content.options || content.options.length < 2) {
        errors.push('Must have at least 2 options');
      }

      const ids = content.options?.map(o => o.id) || [];
      if (new Set(ids).size !== ids.length) {
        errors.push('Option IDs must be unique');
      }

      const correctCount = content.options?.filter(o => o.correct).length || 0;
      if (type === 'MCQ_SINGLE' && correctCount !== 1) {
        errors.push('Exactly one option must be correct');
      } else if (type === 'MCQ_MULTI' && correctCount < 1) {
        errors.push('At least one option must be correct');
      }

      content.options?.forEach((opt, idx) => {
        if (!opt.id || !opt.text) {
          errors.push(`Option ${idx + 1}: ID and text are required`);
        }
      });
      break;

    case 'OPEN':
      if (!content.answer || !content.answer.trim()) {
        errors.push('Answer text is required');
      }
      break;

    case 'FILL_GAP':
      if (!content.gaps || content.gaps.length < 1) {
        errors.push('At least one gap is required');
      }

      const gapIds = content.gaps?.map(g => g.id) || [];
      if (new Set(gapIds).size !== gapIds.length) {
        errors.push('Gap IDs must be unique');
      }

      content.gaps?.forEach(gap => {
        if (gap.answer === undefined || gap.answer === '') {
          errors.push(`Gap ${gap.id}: Answer is required`);
        }
      });
      break;

    case 'ORDERING':
      if (!content.items || content.items.length < 2 || content.items.length > 10) {
        errors.push('Must have 2-10 items');
      }

      const itemIds = content.items?.map(i => i.id) || [];
      if (new Set(itemIds).size !== itemIds.length) {
        errors.push('Item IDs must be unique');
      }
      break;

    case 'MATCHING':
      if (!content.left || content.left.length < 2) {
        errors.push('Must have at least 2 left items');
      }
      if (!content.right || content.right.length < 2) {
        errors.push('Must have at least 2 right items');
      }

      const rightIds = content.right?.map(r => r.id) || [];
      content.left?.forEach(l => {
        if (!rightIds.includes(l.matchId)) {
          errors.push(`Left item ${l.id}: matchId ${l.matchId} not found in right items`);
        }
      });
      break;

    case 'HOTSPOT':
      if (!content.imageUrl) {
        errors.push('Image URL is required');
      }
      if (!content.regions || content.regions.length < 2 || content.regions.length > 6) {
        errors.push('Must have 2-6 regions');
      }

      const correctRegions = content.regions?.filter(r => r.correct).length || 0;
      if (correctRegions < 1) {
        errors.push('At least one region must be correct');
      }
      break;

    case 'COMPLIANCE':
      if (!content.statements || content.statements.length < 2 || content.statements.length > 6) {
        errors.push('Must have 2-6 statements');
      }

      const compliantCount = content.statements?.filter(s => s.compliant).length || 0;
      if (compliantCount < 1) {
        errors.push('At least one statement must be compliant');
      }
      break;
  }

  return {
    valid: errors.length === 0,
    errors: errors
  };
};
```

---

### Bulk Question Creation

```javascript
const bulkCreateQuestions = async (questions, token) => {
  const results = {
    successful: [],
    failed: []
  };

  for (const question of questions) {
    try {
      const response = await fetch('/api/v1/questions', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(question)
      });

      if (!response.ok) {
        const error = await response.json();
        results.failed.push({
          question: question.questionText,
          error: error.details?.[0] || 'Creation failed'
        });
        continue;
      }

      const { questionId } = await response.json();
      results.successful.push({
        question: question.questionText,
        id: questionId
      });
    } catch (error) {
      results.failed.push({
        question: question.questionText,
        error: error.message
      });
    }
  }

  return results;
};

// Usage
const questions = [
  {
    type: 'MCQ_SINGLE',
    difficulty: 'EASY',
    questionText: 'What is 2+2?',
    content: {
      options: [
        { id: '1', text: '3', correct: false },
        { id: '2', text: '4', correct: true },
        { id: '3', text: '5', correct: false }
      ]
    },
    quizIds: [quizId]
  },
  // ... more questions
];

const results = await bulkCreateQuestions(questions, token);
console.log(`Created: ${results.successful.length}, Failed: ${results.failed.length}`);
```

---

### Question Preview Component

```javascript
const QuestionPreview = ({ questionId, token }) => {
  const [question, setQuestion] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadQuestion();
  }, [questionId]);

  const loadQuestion = async () => {
    try {
      const response = await fetch(`/api/v1/questions/${questionId}`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      const data = await response.json();
      setQuestion(data);
    } catch (error) {
      console.error('Failed to load question:', error);
    } finally {
      setLoading(false);
    }
  };

  if (loading) return <div>Loading...</div>;
  if (!question) return <div>Question not found</div>;

  return (
    <div className="question-preview">
      <div className="header">
        <span className="type-badge">{question.type}</span>
        <span className="difficulty-badge">{question.difficulty}</span>
      </div>

      <h3>{question.questionText}</h3>

      {question.attachmentUrl && (
        <img src={question.attachmentUrl} alt="Question attachment" />
      )}

      {renderContent(question.type, question.content)}

      {question.hint && (
        <div className="hint">
          <strong>Hint:</strong> {question.hint}
        </div>
      )}

      {question.explanation && (
        <div className="explanation">
          <strong>Explanation:</strong> {question.explanation}
        </div>
      )}

      <div className="meta">
        <p>Created: {new Date(question.createdAt).toLocaleString()}</p>
        <p>Quizzes: {question.quizIds.length}</p>
        <p>Tags: {question.tagIds.length}</p>
      </div>
    </div>
  );
};

const renderContent = (type, content) => {
  switch (type) {
    case 'MCQ_SINGLE':
    case 'MCQ_MULTI':
      return (
        <div className="options">
          {content.options.map(opt => (
            <div key={opt.id} className={`option ${opt.correct ? 'correct' : ''}`}>
              {opt.text}
              {opt.correct && <span className="check">✓</span>}
            </div>
          ))}
        </div>
      );

    case 'TRUE_FALSE':
      return <div className="answer">Answer: {content.answer ? 'True' : 'False'}</div>;

    case 'OPEN':
      return <div className="answer">Expected: {content.answer}</div>;

    case 'FILL_GAP':
      return (
        <div>
          <p>{content.text}</p>
          <div className="gaps">
            {content.gaps.map(gap => (
              <div key={gap.id}>Gap {gap.id}: {gap.answer}</div>
            ))}
          </div>
        </div>
      );

    default:
      return <pre>{JSON.stringify(content, null, 2)}</pre>;
  }
};
```

---

## Security Considerations

### Permission-Based Access

1. **Create Permission**: Requires `QUESTION_CREATE` permission
2. **Update Permission**: Requires `QUESTION_UPDATE` permission
3. **Delete Permission**: Requires `QUESTION_DELETE` permission
4. **Read Access**: No special permission, but filtered by quiz access

### Quiz Ownership Validation

1. **Create**: Must own all referenced quizzes in `quizIds`
2. **Update**: Must own all quizzes (existing + newly added)
3. **Delete**: Must own all associated quizzes
4. **Moderator Override**: Moderators/admins can access any quiz

### Public Quiz Access

1. **Read Questions**: Allowed for public+published quiz questions
2. **Still Authenticated**: Authentication required even for public quizzes
3. **No Write Access**: Cannot modify public quiz questions unless owner/moderator

### Content Validation

1. **Server-Side**: All content schemas validated server-side
2. **Type-Specific**: Each question type has dedicated validator
3. **Strict Schemas**: Invalid structure rejected with detailed errors
4. **No Bypassing**: Cannot bypass validation

### Best Practices

**Frontend Implementation**:
- Validate content client-side before submission
- Show type-specific form controls
- Display character counters for text fields
- Implement dynamic option management
- Provide content examples for each type
- Use type-ahead for quiz/tag selection
- Cache question lists appropriately

**Error Handling**:
- Parse and display validation errors clearly
- Show field-level errors where possible
- Handle permission errors gracefully
- Provide helpful error messages for content validation
- Log errors for debugging

**Performance**:
- Paginate question lists
- Lazy load question details
- Cache frequently accessed questions
- Debounce search inputs
- Implement virtual scrolling for large lists

**UX**:
- Show question type icons
- Color-code difficulty levels
- Provide content templates
- Enable question duplication
- Allow bulk operations
- Show associated quizzes/tags
- Implement question search

**Testing**:
- Test each question type's content schema
- Verify permission checks
- Test quiz ownership validation
- Test with public and private quizzes
- Verify error handling
- Test pagination
- Test association management

---

## Content Schema Examples

### Complete Examples for Each Type

**MCQ_SINGLE Example**:
```json
{
  "type": "MCQ_SINGLE",
  "difficulty": "MEDIUM",
  "questionText": "Which is a programming language?",
  "content": {
    "options": [
      { "id": "a", "text": "Java", "correct": true },
      { "id": "b", "text": "HTML", "correct": false },
      { "id": "c", "text": "CSS", "correct": false }
    ]
  }
}
```

**MCQ_MULTI Example**:
```json
{
  "type": "MCQ_MULTI",
  "difficulty": "EASY",
  "questionText": "Which are programming languages?",
  "content": {
    "options": [
      { "id": "1", "text": "Java", "correct": true },
      { "id": "2", "text": "Python", "correct": true },
      { "id": "3", "text": "HTML", "correct": false }
    ]
  }
}
```

**ORDERING Example**:
```json
{
  "type": "ORDERING",
  "difficulty": "MEDIUM",
  "questionText": "Arrange these events in chronological order:",
  "content": {
    "items": [
      { "id": 1, "text": "Java 1.0 released (1996)" },
      { "id": 2, "text": "Java 5 released (2004)" },
      { "id": 3, "text": "Java 8 released (2014)" }
    ]
  }
}
```

**MATCHING Example**:
```json
{
  "type": "MATCHING",
  "difficulty": "MEDIUM",
  "questionText": "Match each language to its typing system:",
  "content": {
    "left": [
      { "id": 1, "text": "Java", "matchId": 10 },
      { "id": 2, "text": "Python", "matchId": 20 },
      { "id": 3, "text": "JavaScript", "matchId": 20 }
    ],
    "right": [
      { "id": 10, "text": "Static typing" },
      { "id": 20, "text": "Dynamic typing" }
    ]
  }
}
```

**HOTSPOT Example**:
```json
{
  "type": "HOTSPOT",
  "difficulty": "HARD",
  "questionText": "Click on the correct parts of the UML diagram:",
  "content": {
    "imageUrl": "https://example.com/uml-diagram.png",
    "regions": [
      { "id": 1, "x": 100, "y": 50, "width": 80, "height": 40, "correct": true },
      { "id": 2, "x": 200, "y": 150, "width": 80, "height": 40, "correct": false },
      { "id": 3, "x": 100, "y": 250, "width": 80, "height": 40, "correct": true }
    ]
  }
}
```

**COMPLIANCE Example**:
```json
{
  "type": "COMPLIANCE",
  "difficulty": "MEDIUM",
  "questionText": "Mark each statement as compliant or non-compliant with GDPR:",
  "content": {
    "statements": [
      { "id": 1, "text": "Obtain user consent before processing data", "compliant": true },
      { "id": 2, "text": "Store passwords in plain text", "compliant": false },
      { "id": 3, "text": "Allow users to request data deletion", "compliant": true }
    ]
  }
}
```

---

## Troubleshooting

### Common Issues

**1. 403 When Creating Question**
- **Cause**: Don't own referenced quizzes or missing `QUESTION_CREATE` permission
- **Solution**: Verify quiz ownership and user permissions
- **Prevention**: Only allow associating with owned quizzes

**2. Content Validation Errors**
- **Cause**: Content doesn't match type-specific schema
- **Solution**: Review schema requirements for question type
- **Prevention**: Use client-side validation before submission

**3. No Correct Answer Marked**
- **Cause**: MCQ options missing correct answer
- **Solution**: Ensure exactly 1 (MCQ_SINGLE) or ≥1 (MCQ_MULTI) correct option
- **Prevention**: Validate before submission

**4. Duplicate Option IDs**
- **Cause**: Options have non-unique IDs
- **Solution**: Generate unique IDs for each option
- **Prevention**: Use auto-incrementing IDs or UUIDs

**5. Cannot List Questions Without Quiz Filter**
- **Cause**: User owns no quizzes and didn't specify quizId
- **Solution**: Provide quizId parameter or create a quiz first
- **UX**: Show appropriate message to user

**6. matchId References Non-Existent Right Item**
- **Cause**: MATCHING question has invalid matchId
- **Solution**: Ensure all matchIds reference valid right item IDs
- **Prevention**: Validate matches before submission

### Debug Checklist

- [ ] Valid authentication token provided
- [ ] User has required permission (CREATE/UPDATE/DELETE)
- [ ] User owns all referenced quizzes
- [ ] Question text is 3-1000 characters
- [ ] Content matches type-specific schema
- [ ] All option/item IDs are unique
- [ ] Correct answers are marked properly
- [ ] Referenced quizzes and tags exist
- [ ] Hint ≤ 500 characters (if provided)
- [ ] Explanation ≤ 2000 characters (if provided)
- [ ] AttachmentUrl ≤ 2048 characters (if provided)

---

## Validation Rules Summary

### Field Validation

| Field | Min Length | Max Length | Required |
| --- | --- | --- | --- |
| `questionText` | 3 | 1000 | Yes |
| `hint` | - | 500 | No |
| `explanation` | - | 2000 | No |
| `attachmentUrl` | - | 2048 | No |

### Content Schema Validation

| Type | Min Items | Max Items | Notes |
| --- | --- | --- | --- |
| `MCQ_SINGLE` options | 2 | ∞ | Exactly 1 correct |
| `MCQ_MULTI` options | 2 | ∞ | At least 1 correct |
| `FILL_GAP` gaps | 1 | ∞ | IDs must match placeholders |
| `ORDERING` items | 2 | 10 | Order matters |
| `MATCHING` left/right | 2 | ∞ | matchIds must be valid |
| `HOTSPOT` regions | 2 | 6 | At least 1 correct |
| `COMPLIANCE` statements | 2 | 6 | At least 1 compliant |

---

## Best Practices Summary

### Question Design

1. **Clear Text**: Write clear, unambiguous question text
2. **Appropriate Difficulty**: Match difficulty to actual complexity
3. **Useful Hints**: Provide helpful (not answer-revealing) hints
4. **Educational Explanations**: Include detailed explanations
5. **Varied Types**: Use different question types for engagement

### Content Structure

1. **Unique IDs**: Always use unique IDs for options/items
2. **Non-Empty Text**: All text fields must have content
3. **Valid References**: Ensure matchIds, gap IDs reference correctly
4. **Appropriate Counts**: Follow min/max item requirements
5. **At Least One Correct**: Always mark correct answers

### API Usage

1. **Validate First**: Client-side validation before submission
2. **Handle Errors**: Parse and display validation errors
3. **Batch Operations**: Consider bulk creation for multiple questions
4. **Update Carefully**: Provide all required fields for updates
5. **Test Content**: Validate content schemas in development

### Performance

1. **Pagination**: Use appropriate page sizes
2. **Filtering**: Filter by quiz when possible
3. **Caching**: Cache question lists appropriately
4. **Lazy Loading**: Load details on demand
5. **Debouncing**: Debounce search/filter inputs

