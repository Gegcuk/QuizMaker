# QuizMaker API Documentation

## Overview

QuizMaker is a Spring Boot backend that provides a comprehensive quiz management system with AI-powered quiz generation, user authentication, and detailed analytics.

**Base URL:** `http://localhost:8080/api`

**API Version:** `v1`

## Authentication

All authenticated endpoints require a Bearer token in the Authorization header:
```
Authorization: Bearer <access_token>
```

### JWT Token Structure
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "dGhpc2lzYXJlZnJlc2h0b2tlbg==",
  "accessExpiresInMs": 3600000,
  "refreshExpiresInMs": 864000000
}
```

## Endpoints

### Authentication Endpoints

#### Register User
```http
POST /v1/auth/register
Content-Type: application/json

{
  "username": "newUser",
  "email": "user@example.com",
  "password": "P@ssw0rd!"
}
```

**Response:** `201 Created`
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "username": "newUser",
  "email": "user@example.com",
  "isActive": true,
  "roles": ["ROLE_USER"],
  "createdAt": "2025-05-21T15:30:00",
  "lastLoginDate": null,
  "updatedAt": "2025-05-21T15:30:00"
}
```

#### Login
```http
POST /v1/auth/login
Content-Type: application/json

{
  "username": "newUser",
  "password": "P@ssw0rd!"
}
```

**Response:** `200 OK`
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "dGhpc2lzYXJlZnJlc2h0b2tlbg==",
  "accessExpiresInMs": 3600000,
  "refreshExpiresInMs": 864000000
}
```

#### Refresh Token
```http
POST /v1/auth/refresh
Content-Type: application/json

{
  "refreshToken": "dGhpc2lzYXJlZnJlc2h0b2tlbg=="
}
```

#### Logout
```http
POST /v1/auth/logout
Authorization: Bearer <access_token>
```

#### Get Current User
```http
GET /v1/auth/me
Authorization: Bearer <access_token>
```

### Quiz Endpoints

#### Create Quiz (Admin Only)
```http
POST /v1/quizzes
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "title": "My Quiz",
  "description": "A fun pop-quiz",
  "visibility": "PRIVATE",
  "difficulty": "MEDIUM",
  "isRepetitionEnabled": false,
  "timerEnabled": true,
  "estimatedTime": 10,
  "timerDuration": 5,
  "categoryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "tagIds": ["a1b2c3d4-0000-0000-0000-000000000000"]
}
```

**Response:** `201 Created`
```json
{
  "quizId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

#### List Quizzes
```http
GET /v1/quizzes?page=0&size=20&sort=createdAt,desc
```

**Query Parameters:**
- `page`: Page number (0-based)
- `size`: Page size (default: 20)
- `sort`: Sort field and direction
- `title`: Filter by title
- `categoryId`: Filter by category
- `difficulty`: Filter by difficulty
- `status`: Filter by status
- `visibility`: Filter by visibility

#### Get Quiz by ID
```http
GET /v1/quizzes/{quizId}
```

#### Update Quiz (Admin Only)
```http
PATCH /v1/quizzes/{quizId}
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "title": "Updated Quiz Title",
  "description": "Updated description"
}
```

#### Bulk Update Quizzes (Admin Only)
```http
PATCH /v1/quizzes/bulk-update
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "quizIds": ["id1", "id2", "id3"],
  "updates": {
    "visibility": "PUBLIC",
    "status": "PUBLISHED"
  }
}
```

**Response:** `200 OK`
```json
{
  "successfulUpdates": 3,
  "failedUpdates": 0,
  "errors": []
}
```

#### Delete Quiz (Admin Only)
```http
DELETE /v1/quizzes/{quizId}
Authorization: Bearer <access_token>
```

#### Bulk Delete Quizzes (Admin Only)
```http
DELETE /v1/quizzes?ids=id1,id2,id3
Authorization: Bearer <access_token>
```

#### Add Question to Quiz (Admin Only)
```http
POST /v1/quizzes/{quizId}/questions/{questionId}
Authorization: Bearer <access_token>
```

#### Remove Question from Quiz (Admin Only)
```http
DELETE /v1/quizzes/{quizId}/questions/{questionId}
Authorization: Bearer <access_token>
```

#### Add Tag to Quiz (Admin Only)
```http
POST /v1/quizzes/{quizId}/tags/{tagId}
Authorization: Bearer <access_token>
```

#### Remove Tag from Quiz (Admin Only)
```http
DELETE /v1/quizzes/{quizId}/tags/{tagId}
Authorization: Bearer <access_token>
```

#### Change Quiz Category (Admin Only)
```http
PATCH /v1/quizzes/{quizId}/category/{categoryId}
Authorization: Bearer <access_token>
```

#### Get Quiz Results
```http
GET /v1/quizzes/{quizId}/results
```

#### Get Quiz Leaderboard
```http
GET /v1/quizzes/{quizId}/leaderboard?top=10
```

#### Toggle Quiz Visibility (Admin Only)
```http
PATCH /v1/quizzes/{quizId}/visibility
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "visibility": "PUBLIC"
}
```

#### Change Quiz Status (Admin Only)
```http
PATCH /v1/quizzes/{quizId}/status
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "status": "PUBLISHED"
}
```

#### List Public Quizzes
```http
GET /v1/quizzes/public?page=0&size=20
```

### Question Endpoints

#### Create Question (Admin Only)
```http
POST /v1/questions
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "type": "MCQ_SINGLE",
  "difficulty": "EASY",
  "questionText": "What is the capital of France?",
  "content": {
    "options": [
      {"text": "London", "correct": false},
      {"text": "Paris", "correct": true},
      {"text": "Berlin", "correct": false},
      {"text": "Madrid", "correct": false}
    ]
  },
  "hint": "Think of the Eiffel Tower",
  "explanation": "Paris is the capital of France.",
  "attachmentUrl": "http://example.com/image.png",
  "quizIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"]
}
```

#### List Questions
```http
GET /v1/questions?quizId={quizId}&pageNumber=0&size=20
```

#### Get Question by ID
```http
GET /v1/questions/{id}
```

#### Update Question (Admin Only)
```http
PATCH /v1/questions/{id}
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "questionText": "Updated question text",
  "difficulty": "HARD"
}
```

#### Delete Question (Admin Only)
```http
DELETE /v1/questions/{id}
Authorization: Bearer <access_token>
```

### Attempt Endpoints

#### Start Attempt
```http
POST /v1/attempts/quizzes/{quizId}
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "mode": "ONE_BY_ONE"
}
```

**Response:** `201 Created`
```json
{
  "attemptId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "firstQuestion": {
    "id": "abcdef12-3456-7890-abcd-ef1234567890",
    "type": "MCQ_SINGLE",
    "difficulty": "EASY",
    "questionText": "What is the capital of France?",
    "safeContent": {
      "options": [
        {"text": "London"},
        {"text": "Paris"},
        {"text": "Berlin"},
        {"text": "Madrid"}
      ]
    },
    "hint": "Think of the Eiffel Tower",
    "attachmentUrl": "http://example.com/image.png"
  }
}
```

#### List Attempts
```http
GET /v1/attempts?page=0&size=20&quizId={quizId}&status={status}
Authorization: Bearer <access_token>
```

#### Get Attempt Details
```http
GET /v1/attempts/{attemptId}
Authorization: Bearer <access_token>
```

#### Submit Answer
```http
POST /v1/attempts/{attemptId}/answers
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "questionId": "abcdef12-3456-7890-abcd-ef1234567890",
  "response": {
    "answer": "Paris"
  }
}
```

**Response:** `200 OK`
```json
{
  "answerId": "4b3a2c1d-0f9e-8d7c-6b5a-4c3b2a1d0e9f",
  "questionId": "abcdef12-3456-7890-abcd-ef1234567890",
  "isCorrect": true,
  "score": 1.0,
  "answeredAt": "2025-05-20T14:35:00Z",
  "nextQuestion": {
    "id": "next-question-id",
    "type": "TRUE_FALSE",
    "questionText": "Is the Earth round?",
    "safeContent": {
      "statement": "The Earth is round"
    }
  }
}
```

#### Submit Batch Answers
```http
POST /v1/attempts/{attemptId}/answers/batch
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "answers": [
    {
      "questionId": "question1",
      "response": {"answer": "Paris"}
    },
    {
      "questionId": "question2", 
      "response": {"answer": true}
    }
  ]
}
```

#### Complete Attempt
```http
POST /v1/attempts/{attemptId}/complete
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
{
  "attemptId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "quizId": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
  "userId": "9f8e7d6c-5b4a-3c2d-1b0a-9f8e7d6c5b4a",
  "startedAt": "2025-05-20T14:30:00Z",
  "completedAt": "2025-05-20T14:45:00Z",
  "status": "COMPLETED",
  "totalScore": 8.5,
  "correctCount": 8,
  "totalQuestions": 10,
  "answers": [...]
}
```

#### Get Attempt Statistics
```http
GET /v1/attempts/{attemptId}/stats
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
{
  "attemptId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "totalQuestions": 10,
  "answeredQuestions": 8,
  "correctAnswers": 7,
  "currentScore": 7.0,
  "timeElapsed": 450,
  "estimatedTimeRemaining": 150,
  "progress": 80.0
}
```

#### Pause Attempt
```http
POST /v1/attempts/{attemptId}/pause
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
{
  "attemptId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "PAUSED",
  "pausedAt": "2025-05-20T14:35:00Z"
}
```

#### Resume Attempt
```http
POST /v1/attempts/{attemptId}/resume
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
{
  "attemptId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "IN_PROGRESS",
  "resumedAt": "2025-05-20T14:40:00Z"
}
```

#### Get Shuffled Questions for Quiz
```http
GET /v1/attempts/quizzes/{quizId}/questions/shuffled
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
[
  {
    "id": "question1",
    "type": "MCQ_SINGLE",
    "difficulty": "EASY",
    "questionText": "What is the capital of France?",
    "safeContent": {
      "options": [
        {"text": "London"},
        {"text": "Paris"},
        {"text": "Berlin"},
        {"text": "Madrid"}
      ]
    },
    "hint": "Think of the Eiffel Tower",
    "attachmentUrl": "http://example.com/image.png"
  },
  {
    "id": "question2",
    "type": "TRUE_FALSE",
    "questionText": "Is the Earth round?",
    "safeContent": {
      "statement": "The Earth is round"
    }
  }
]
```

### Category Endpoints

#### List Categories
```http
GET /v1/categories?page=0&size=20
```

#### Create Category (Admin Only)
```http
POST /v1/categories
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "Science",
  "description": "All science-related quizzes"
}
```

#### Get Category by ID
```http
GET /v1/categories/{categoryId}
```

#### Update Category (Admin Only)
```http
PATCH /v1/categories/{categoryId}
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "Updated Science",
  "description": "Updated description"
}
```

#### Delete Category (Admin Only)
```http
DELETE /v1/categories/{categoryId}
Authorization: Bearer <access_token>
```

### Tag Endpoints

#### List Tags
```http
GET /v1/tags?page=0&size=20
```

#### Create Tag (Admin Only)
```http
POST /v1/tags
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "math",
  "description": "Questions related to mathematics"
}
```

#### Get Tag by ID
```http
GET /v1/tags/{tagId}
```

#### Update Tag (Admin Only)
```http
PATCH /v1/tags/{tagId}
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "mathematics",
  "description": "Updated description"
}
```

#### Delete Tag (Admin Only)
```http
DELETE /v1/tags/{tagId}
Authorization: Bearer <access_token>
```

### Document Processing Endpoints

#### Upload Document
```http
POST /api/documents/upload
Authorization: Bearer <access_token>
Content-Type: multipart/form-data

Parameters:
- file: The document file to upload
- chunkingStrategy: AUTO, CHAPTER_BASED, SECTION_BASED, SIZE_BASED, PAGE_BASED (optional)
- maxChunkSize: Maximum characters per chunk (optional, default: 4000)
```

#### Get Document Configuration
```http
GET /api/documents/config
Authorization: Bearer <access_token>
```

#### Get Document
```http
GET /api/documents/{documentId}
Authorization: Bearer <access_token>
```

#### List User Documents
```http
GET /api/documents?page=0&size=10
Authorization: Bearer <access_token>
```

#### Get Document Chunks
```http
GET /api/documents/{documentId}/chunks
Authorization: Bearer <access_token>
```

#### Get Specific Chunk
```http
GET /api/documents/{documentId}/chunks/{chunkIndex}
Authorization: Bearer <access_token>
```

#### Delete Document
```http
DELETE /api/documents/{documentId}
Authorization: Bearer <access_token>
```

#### Reprocess Document
```http
POST /api/documents/{documentId}/reprocess
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "chunkingStrategy": "CHAPTER_BASED",
  "maxChunkSize": 4000
}
```

#### Get Document Status
```http
GET /api/documents/{documentId}/status
Authorization: Bearer <access_token>
```

### AI Quiz Generation Endpoints

#### Generate Quiz from Document (Async)
```http
POST /v1/quizzes/generate-from-document
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "documentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "scope": {
    "type": "ENTIRE_DOCUMENT"
  },
  "questionTypes": {
    "MCQ_SINGLE": 5,
    "TRUE_FALSE": 3,
    "OPEN": 2
  },
  "difficulty": "MEDIUM",
  "quizTitle": "Generated Quiz",
  "quizDescription": "AI-generated quiz from document"
}
```

#### Get Generation Status
```http
GET /v1/quizzes/generation-status/{jobId}
Authorization: Bearer <access_token>
```

#### Get Generated Quiz
```http
GET /v1/quizzes/generated-quiz/{jobId}
Authorization: Bearer <access_token>
```

#### Cancel Generation Job
```http
DELETE /v1/quizzes/generation-status/{jobId}
Authorization: Bearer <access_token>
```

#### List Generation Jobs
```http
GET /v1/quizzes/generation-jobs?page=0&size=20
Authorization: Bearer <access_token>
```

#### Get Generation Statistics
```http
GET /v1/quizzes/generation-jobs/statistics
Authorization: Bearer <access_token>
```

### AI Chat Endpoints

#### Send Chat Message
```http
POST /api/ai/chat
Content-Type: application/json

{
  "message": "Hello, how can you help me with quiz creation?"
}
```

**Response:** `200 OK`
```json
{
  "message": "I can help you create quizzes from documents, suggest questions, and provide educational content.",
  "model": "gpt-4o-mini",
  "latency": 1500,
  "tokensUsed": 45,
  "timestamp": "2025-05-21T15:30:00"
}
```

### Admin Endpoints

#### List Roles
```http
GET /v1/admin/roles
Authorization: Bearer <access_token>
```

#### Get Role by ID
```http
GET /v1/admin/roles/{roleId}
Authorization: Bearer <access_token>
```

#### Create Role
```http
POST /v1/admin/roles
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "MODERATOR",
  "description": "Moderator role with limited permissions"
}
```

#### Update Role
```http
PUT /v1/admin/roles/{roleId}
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "MODERATOR",
  "description": "Updated moderator role"
}
```

#### Delete Role (Admin Only)
```http
DELETE /v1/admin/roles/{roleId}
Authorization: Bearer <access_token>
```

**Response:** `204 No Content`

#### Assign Role to User (Admin Only)
```http
POST /v1/admin/users/{userId}/roles/{roleId}
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
{
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "roleId": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
  "assignedAt": "2025-05-21T15:30:00Z"
}
```

#### Remove Role from User (Admin Only)
```http
DELETE /v1/admin/users/{userId}/roles/{roleId}
Authorization: Bearer <access_token>
```

**Response:** `204 No Content`

#### Initialize System (Admin Only)
```http
POST /v1/admin/system/initialize
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
{
  "status": "INITIALIZED",
  "message": "System initialized successfully",
  "initializedAt": "2025-05-21T15:30:00Z",
  "defaultRoles": ["ROLE_USER", "ROLE_ADMIN"],
  "defaultCategories": ["General", "Science", "History"]
}
```

#### Get System Status (Admin Only)
```http
GET /v1/admin/system/status
Authorization: Bearer <access_token>
```

**Response:** `200 OK`
```json
{
  "status": "HEALTHY",
  "database": "CONNECTED",
  "aiService": "AVAILABLE",
  "fileStorage": "READY",
  "uptime": "2 days, 5 hours, 30 minutes",
  "version": "1.0.0",
  "lastMaintenance": "2025-05-19T10:00:00Z"
}
```

#### Dangerous Operation (Super Admin Only)
```http
POST /v1/admin/super/dangerous-operation
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "operation": "RESET_ALL_DATA",
  "confirmation": "I understand this will delete all data"
}
```

**Response:** `200 OK`
```json
{
  "status": "COMPLETED",
  "message": "All data has been reset",
  "completedAt": "2025-05-21T15:30:00Z",
  "affectedRecords": 15000
}
```

### Utility Endpoints

#### Health Check
```http
GET /v1/health
```

**Response:** `200 OK`
```json
{
  "status": "UP"
}
```

## Data Transfer Objects (DTOs)

### Authentication DTOs

#### RegisterRequest
```json
{
  "username": "string (4-20 chars)",
  "email": "string (valid email)",
  "password": "string (8-100 chars)"
}
```

#### LoginRequest
```json
{
  "username": "string",
  "password": "string"
}
```

#### JwtResponse
```json
{
  "accessToken": "string",
  "refreshToken": "string", 
  "accessExpiresInMs": "number",
  "refreshExpiresInMs": "number"
}
```

#### UserDto
```json
{
  "id": "UUID",
  "username": "string",
  "email": "string",
  "isActive": "boolean",
  "roles": ["ROLE_USER", "ROLE_ADMIN"],
  "createdAt": "LocalDateTime",
  "lastLoginDate": "LocalDateTime",
  "updatedAt": "LocalDateTime"
}
```

### Quiz DTOs

#### CreateQuizRequest
```json
{
  "title": "string (3-100 chars)",
  "description": "string (max 1000 chars)",
  "visibility": "PRIVATE|PUBLIC",
  "difficulty": "EASY|MEDIUM|HARD",
  "isRepetitionEnabled": "boolean",
  "timerEnabled": "boolean",
  "estimatedTime": "number (1-180)",
  "timerDuration": "number (1-180)",
  "categoryId": "UUID",
  "tagIds": ["UUID"]
}
```

#### QuizDto
```json
{
  "id": "UUID",
  "creatorId": "UUID",
  "categoryId": "UUID",
  "title": "string",
  "description": "string",
  "visibility": "PRIVATE|PUBLIC",
  "difficulty": "EASY|MEDIUM|HARD",
  "status": "DRAFT|PUBLISHED",
  "estimatedTime": "number",
  "isRepetitionEnabled": "boolean",
  "timerEnabled": "boolean",
  "timerDuration": "number",
  "tagIds": ["UUID"],
  "questionCount": "number",
  "createdAt": "Instant",
  "updatedAt": "Instant"
}
```

#### QuizSearchCriteria
```json
{
  "title": "string",
  "categoryId": "UUID",
  "difficulty": "EASY|MEDIUM|HARD",
  "status": "DRAFT|PUBLISHED",
  "visibility": "PRIVATE|PUBLIC",
  "creatorId": "UUID"
}
```

### Question DTOs

#### CreateQuestionRequest
```json
{
  "type": "MCQ_SINGLE|MCQ_MULTIPLE|TRUE_FALSE|OPEN|FILL_GAP|ORDERING|HOTSPOT|COMPLIANCE",
  "difficulty": "EASY|MEDIUM|HARD",
  "questionText": "string (3-1000 chars)",
  "content": "object (question-type specific)",
  "hint": "string (max 500 chars)",
  "explanation": "string (max 2000 chars)",
  "attachmentUrl": "string (max 2048 chars)",
  "quizIds": ["UUID"]
}
```

#### QuestionDto
```json
{
  "id": "UUID",
  "type": "QuestionType",
  "difficulty": "Difficulty",
  "questionText": "string",
  "content": "object",
  "hint": "string",
  "explanation": "string",
  "attachmentUrl": "string",
  "createdAt": "Instant",
  "updatedAt": "Instant",
  "quizIds": ["UUID"],
  "tagIds": ["UUID"]
}
```

#### QuestionForAttemptDto (Safe for Users)
```json
{
  "id": "UUID",
  "type": "QuestionType",
  "difficulty": "Difficulty",
  "questionText": "string",
  "safeContent": "object (no correct answers)",
  "hint": "string",
  "attachmentUrl": "string"
}
```

### Attempt DTOs

#### StartAttemptRequest
```json
{
  "mode": "ONE_BY_ONE|ALL_AT_ONCE"
}
```

#### StartAttemptResponse
```json
{
  "attemptId": "UUID",
  "firstQuestion": "QuestionForAttemptDto"
}
```

#### AttemptDto
```json
{
  "attemptId": "UUID",
  "quizId": "UUID",
  "userId": "UUID",
  "startedAt": "Instant",
  "status": "IN_PROGRESS|COMPLETED|ABANDONED|PAUSED",
  "mode": "ONE_BY_ONE|ALL_AT_ONCE"
}
```

#### AttemptDetailsDto
```json
{
  "attemptId": "UUID",
  "quizId": "UUID",
  "userId": "UUID",
  "startedAt": "Instant",
  "completedAt": "Instant",
  "status": "AttemptStatus",
  "mode": "AttemptMode",
  "answers": ["AnswerSubmissionDto"]
}
```

#### AnswerSubmissionRequest
```json
{
  "questionId": "UUID",
  "response": "object (question-type specific)"
}
```

#### AnswerSubmissionDto
```json
{
  "answerId": "UUID",
  "questionId": "UUID",
  "isCorrect": "boolean",
  "score": "number",
  "answeredAt": "Instant",
  "nextQuestion": "QuestionForAttemptDto"
}
```

#### BatchAnswerSubmissionRequest
```json
{
  "answers": ["AnswerSubmissionRequest"]
}
```

### Category DTOs

#### CreateCategoryRequest
```json
{
  "name": "string (3-100 chars)",
  "description": "string (max 1000 chars)"
}
```

#### CategoryDto
```json
{
  "id": "UUID",
  "name": "string",
  "description": "string"
}
```

### Tag DTOs

#### CreateTagRequest
```json
{
  "name": "string (3-50 chars)",
  "description": "string (max 500 chars)"
}
```

#### TagDto
```json
{
  "id": "UUID",
  "name": "string",
  "description": "string"
}
```

### Document DTOs

#### DocumentDto
```json
{
  "id": "UUID",
  "originalFilename": "string",
  "contentType": "string",
  "fileSize": "number",
  "status": "UPLOADED|PROCESSING|PROCESSED|FAILED",
  "uploadedAt": "LocalDateTime",
  "processedAt": "LocalDateTime",
  "title": "string",
  "author": "string",
  "totalPages": "number",
  "totalChunks": "number",
  "processingError": "string",
  "chunks": ["DocumentChunkDto"]
}
```

#### DocumentChunkDto
```json
{
  "id": "UUID",
  "documentId": "UUID",
  "chunkIndex": "number",
  "title": "string",
  "content": "string",
  "pageNumber": "number"
}
```

### AI DTOs

#### ChatRequestDto
```json
{
  "message": "string (max 2000 chars)"
}
```

#### ChatResponseDto
```json
{
  "message": "string",
  "model": "string",
  "latency": "number",
  "tokensUsed": "number",
  "timestamp": "LocalDateTime"
}
```

#### GenerateQuizFromDocumentRequest
```json
{
  "documentId": "UUID",
  "scope": {
    "type": "ENTIRE_DOCUMENT|SPECIFIC_CHUNKS|CHAPTER|SECTION",
    "chunkIds": ["UUID"],
    "chapterTitle": "string",
    "sectionTitle": "string"
  },
  "questionTypes": {
    "MCQ_SINGLE": "number",
    "MCQ_MULTIPLE": "number",
    "TRUE_FALSE": "number",
    "OPEN": "number",
    "FILL_GAP": "number",
    "ORDERING": "number",
    "HOTSPOT": "number",
    "COMPLIANCE": "number"
  },
  "difficulty": "EASY|MEDIUM|HARD",
  "quizTitle": "string",
  "quizDescription": "string"
}
```

#### QuizGenerationResponse
```json
{
  "jobId": "UUID",
  "status": "PENDING",
  "message": "Quiz generation job started successfully"
}
```

#### QuizGenerationStatus
```json
{
  "jobId": "UUID",
  "status": "PENDING|PROCESSING|COMPLETED|FAILED|CANCELLED",
  "progress": "number (0-100)",
  "processedChunks": "number",
  "totalChunks": "number",
  "generatedQuestions": "number",
  "estimatedCompletionTime": "Instant",
  "error": "string",
  "startedAt": "Instant",
  "completedAt": "Instant"
}
```

### Result DTOs

#### QuizResultSummaryDto
```json
{
  "quizId": "UUID",
  "totalAttempts": "number",
  "averageScore": "number",
  "passRate": "number",
  "questionStats": ["QuestionStatsDto"]
}
```

#### LeaderboardEntryDto
```json
{
  "userId": "UUID",
  "username": "string",
  "score": "number",
  "completedAt": "Instant",
  "rank": "number"
}
```

#### QuestionStatsDto
```json
{
  "questionId": "UUID",
  "correctAnswers": "number",
  "totalAnswers": "number",
  "averageTime": "number"
}
```

### Admin DTOs

#### CreateRoleRequest
```json
{
  "name": "string",
  "description": "string"
}
```

#### RoleDto
```json
{
  "id": "UUID",
  "name": "string",
  "description": "string"
}
```

#### UpdateRoleRequest
```json
{
  "name": "string",
  "description": "string"
}
```

## Question Types and Content Structures

### MCQ_SINGLE
```json
{
  "options": [
    {"text": "Option 1", "correct": false},
    {"text": "Option 2", "correct": true},
    {"text": "Option 3", "correct": false},
    {"text": "Option 4", "correct": false}
  ]
}
```

### MCQ_MULTIPLE
```json
{
  "options": [
    {"text": "Option 1", "correct": true},
    {"text": "Option 2", "correct": false},
    {"text": "Option 3", "correct": true},
    {"text": "Option 4", "correct": false}
  ],
  "minSelections": 1,
  "maxSelections": 2
}
```

### TRUE_FALSE
```json
{
  "statement": "The Earth is round",
  "correctAnswer": true
}
```

### OPEN
```json
{
  "expectedKeywords": ["keyword1", "keyword2"],
  "maxLength": 500,
  "caseSensitive": false
}
```

### FILL_GAP
```json
{
  "text": "The capital of France is ___.",
  "gaps": [
    {
      "position": 25,
      "correctAnswers": ["Paris"],
      "caseSensitive": false
    }
  ]
}
```

### ORDERING
```json
{
  "items": [
    {"id": 1, "text": "First step", "correctPosition": 1},
    {"id": 2, "text": "Second step", "correctPosition": 2},
    {"id": 3, "text": "Third step", "correctPosition": 3}
  ]
}
```

### HOTSPOT
```json
{
  "imageUrl": "http://example.com/image.png",
  "regions": [
    {
      "id": "region1",
      "x": 100,
      "y": 100,
      "width": 50,
      "height": 50,
      "correct": true
    }
  ]
}
```

### COMPLIANCE
```json
{
  "statements": [
    {"text": "Statement 1", "compliant": true},
    {"text": "Statement 2", "compliant": false}
  ]
}
```

## Error Responses

### Standard Error Format
```json
{
  "timestamp": "2025-05-21T15:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/quizzes",
  "details": {
    "field": "title",
    "message": "Title must not be blank"
  }
}
```

### Common HTTP Status Codes

- `200 OK`: Request successful
- `201 Created`: Resource created successfully
- `204 No Content`: Request successful, no content to return
- `400 Bad Request`: Invalid request data
- `401 Unauthorized`: Authentication required
- `403 Forbidden`: Insufficient permissions
- `404 Not Found`: Resource not found
- `409 Conflict`: Resource conflict
- `422 Unprocessable Entity`: Validation error
- `500 Internal Server Error`: Server error

## Pagination

Most list endpoints support pagination with the following parameters:

- `page`: Page number (0-based, default: 0)
- `size`: Page size (default: 20, max: 100)
- `sort`: Sort field and direction (e.g., `createdAt,desc`)

### Pagination Response Format
```json
{
  "content": [...],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "sorted": true,
      "unsorted": false
    }
  },
  "totalElements": 100,
  "totalPages": 5,
  "last": false,
  "first": true,
  "numberOfElements": 20,
  "size": 20,
  "number": 0,
  "sort": {
    "sorted": true,
    "unsorted": false
  },
  "empty": false
}
```

## Security

### Authentication
- JWT-based authentication
- Access tokens expire in 12 hours
- Refresh tokens expire in 7 days
- Tokens must be included in Authorization header

### Authorization
- Role-based access control (RBAC)
- Roles: `ROLE_USER`, `ROLE_ADMIN`
- Admin endpoints require `ROLE_ADMIN`
- User endpoints require authentication

### CORS
- Configured for cross-origin requests
- Supports credentials
- Allowed origins should be configured in production

## Rate Limiting

- API endpoints are rate-limited to prevent abuse
- Limits vary by endpoint type
- Rate limit headers included in responses

## File Upload

### Supported Formats
- Documents: PDF, DOCX, TXT
- Images: PNG, JPG, JPEG, GIF
- Maximum file size: 150MB

### Upload Endpoints
- Document upload: `/api/documents/upload`
- Image upload: (if implemented)

## WebSocket Support

### Real-time Features
- Quiz generation progress updates
- Live notifications
- Real-time chat (if implemented)

## Testing

### Test Data
- Sample documents available in `/test-documents/`
- Test users and quizzes can be created via API
- Integration tests available in test suite

### API Testing
- Swagger UI available at `/api/v1/docs/swagger-ui.html`
- OpenAPI specification at `/api/v1/docs`
- Postman collection available (if provided)

## Deployment

### Environment Variables
```bash
# Database
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/quizmakerdb
SPRING_DATASOURCE_USERNAME=bestuser
SPRING_DATASOURCE_PASSWORD=bestuser

# JWT
JWT_ACCESS_EXPIRATION_MS=43200000
JWT_REFRESH_EXPIRATION_MS=604800000

# OpenAI
OPENAI_API_KEY=your_openai_api_key
OPENAI_MODEL=gpt-4o-mini

# File Upload
SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE=150MB
SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE=150MB
```

### Health Checks
- Health endpoint: `GET /v1/health`
- Database connectivity check
- External service status

## Support

For API support and questions:
- Check the Swagger documentation
- Review integration tests
- Contact development team
- Check application logs for detailed error information 