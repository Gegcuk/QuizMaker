# QuizMaker API Overview

This document summarizes the purpose of the backend service, provides the base API URL, highlights the main REST endpoints by controller and shows example JSON for each supported question type.

## Purpose

QuizMaker is a Spring Boot backend that stores questions as JSON, grades answers on the server and tracks attempts, roles and audit logs. It is designed to be secure and extensible so new quiz types can be added easily.

The API is served from:

```
http://localhost:8080/api
```

All endpoint paths below are relative to this root.

## Authentication

| Method & Path | Description |
|--------------|-------------|
| `POST /v1/auth/register` | Register a new user |
| `POST /v1/auth/login` | Obtain access and refresh tokens |
| `POST /v1/auth/refresh` | Refresh tokens |
| `POST /v1/auth/logout` | Revoke the current token |
| `GET  /v1/auth/me` | Fetch details of the authenticated user |

## Quizzes

| Method & Path | Description |
|--------------|-------------|
| `POST /v1/quizzes` | Create a quiz (admin) |
| `GET  /v1/quizzes` | List quizzes with paging and filters |
| `GET  /v1/quizzes/{id}` | Get a quiz by id |
| `PATCH /v1/quizzes/{id}` | Update quiz fields (admin) |
| `PATCH /v1/quizzes/bulk-update` | Bulk update quizzes (admin) |
| `DELETE /v1/quizzes/{id}` | Delete a quiz (admin) |
| `DELETE /v1/quizzes?ids=...` | Bulk delete by id list (admin) |
| `POST /v1/quizzes/{id}/questions/{questionId}` | Add question to quiz (admin) |
| `DELETE /v1/quizzes/{id}/questions/{questionId}` | Remove question from quiz (admin) |
| `POST /v1/quizzes/{id}/tags/{tagId}` | Add tag to quiz (admin) |
| `DELETE /v1/quizzes/{id}/tags/{tagId}` | Remove tag from quiz (admin) |
| `PATCH /v1/quizzes/{id}/category/{categoryId}` | Change quiz category (admin) |
| `GET  /v1/quizzes/{id}/results` | Get aggregated results |
| `GET  /v1/quizzes/{id}/leaderboard` | Get top scores |
| `PATCH /v1/quizzes/{id}/visibility` | Toggle public/private (admin) |
| `PATCH /v1/quizzes/{id}/status` | Change status (admin) |
| `GET  /v1/quizzes/public` | List publicly visible quizzes |

## Questions

| Method & Path | Description |
|--------------|-------------|
| `POST /v1/questions` | Create a question (admin) |
| `GET  /v1/questions` | List questions |
| `GET  /v1/questions/{id}` | Retrieve a question |
| `PATCH /v1/questions/{id}` | Update a question (admin) |
| `DELETE /v1/questions/{id}` | Delete a question (admin) |

## Attempts

| Method & Path | Description |
|--------------|-------------|
| `POST /v1/attempts/quizzes/{quizId}` | Start an attempt for a quiz |
| `GET  /v1/attempts` | List attempts with filters |
| `GET  /v1/attempts/{attemptId}` | Get attempt details |
| `POST /v1/attempts/{attemptId}/answers` | Submit an answer |
| `POST /v1/attempts/{attemptId}/answers/batch` | Submit multiple answers |
| `POST /v1/attempts/{attemptId}/complete` | Complete an attempt |

## Categories

| Method & Path | Description |
|--------------|-------------|
| `GET  /v1/categories` | List categories |
| `POST /v1/categories` | Create a category (admin) |
| `GET  /v1/categories/{id}` | Get a category by id |
| `PATCH /v1/categories/{id}` | Update a category (admin) |
| `DELETE /v1/categories/{id}` | Delete a category (admin) |

## Tags

| Method & Path | Description |
|--------------|-------------|
| `GET  /v1/tags` | List tags |
| `POST /v1/tags` | Create a tag (admin) |
| `GET  /v1/tags/{id}` | Get a tag by id |
| `PATCH /v1/tags/{id}` | Update a tag (admin) |
| `DELETE /v1/tags/{id}` | Delete a tag (admin) |

## Utility

| Method & Path | Description |
|--------------|-------------|
| `GET  /v1/health` | Basic health check |

## Question JSON Examples

Below are valid JSON structures for each supported question type.

### MCQ_SINGLE
```json
{
  "type": "MCQ_SINGLE",
  "difficulty": "EASY",
  "questionText": "Pick one",
  "content": {
    "options": [
      {"text": "A", "correct": false},
      {"text": "B", "correct": true}
    ]
  }
}
```

### MCQ_MULTI
```json
{
  "type": "MCQ_MULTI",
  "difficulty": "MEDIUM",
  "questionText": "Pick many",
  "content": {
    "options": [
      {"text": "A", "correct": true},
      {"text": "B", "correct": false},
      {"text": "C", "correct": true}
    ]
  }
}
```

### TRUE_FALSE
```json
{
  "type": "TRUE_FALSE",
  "difficulty": "EASY",
  "questionText": "T or F?",
  "content": {"answer": true}
}
```

### OPEN
```json
{
  "type": "OPEN",
  "difficulty": "HARD",
  "questionText": "Explain?",
  "content": {"answer": "Because..."}
}
```

### FILL_GAP
```json
{
  "type": "FILL_GAP",
  "difficulty": "MEDIUM",
  "questionText": "Fill:",
  "content": {
    "text": "___ is Java",
    "gaps": [
      {"id": 1, "answer": "Java"}
    ]
  }
}
```

### ORDERING
```json
{
  "type": "ORDERING",
  "difficulty": "HARD",
  "questionText": "Order these",
  "content": {
    "items": [
      {"id": 1, "text": "First"},
      {"id": 2, "text": "Second"}
    ]
  }
}
```

### HOTSPOT
```json
{
  "type": "HOTSPOT",
  "difficulty": "MEDIUM",
  "questionText": "Click",
  "content": {
    "imageUrl": "http://img.png",
    "regions": [
      {"x": 10, "y": 20, "width": 30, "height": 40}
    ]
  }
}
```

### COMPLIANCE
```json
{
  "type": "COMPLIANCE",
  "difficulty": "MEDIUM",
  "questionText": "Agree?",
  "content": {
    "statements": [
      {"text": "Yes", "compliant": true}
    ]
  }
}
```
