# QuizMaker Backend

Welcome to **QuizMaker** backend! This Spring Boot application powers a flexible, secure, and future-proof quiz platform
with JSON-based question storage, audit trails, role management, and more.

For a concise overview of the API and example question formats, see [PROJECT_DESCRIPTION.md](PROJECT_DESCRIPTION.md).

---

## 🚀 Overview

QuizMaker lets you create, manage, and deliver quizzes with ease. On the backend, we:

- Store question definitions in MySQL using the native `JSON` column type for flexibility
- Never expose correct answers to the frontend—grading happens securely on the server
- Track every user's answers and quiz results in dedicated tables
- Manage user roles via a many-to-many mapping
- Keep audit logs and soft-delete records for compliance and recovery
- Provide hooks for spaced repetition, analytics, ratings, comments, notifications, and more

---

## 🧰 Tech Stack

- **Language**: Java 17
- **Framework**: Spring Boot 3.x (Spring Data JPA, Spring Security)
- **Database**: MySQL 8+ with native JSON columns
- **Migration**: Flyway or Liquibase
- **Build**: Maven
- **Security**: JWT-based authentication, BCrypt password hashing, role-based access control

---

## ⚙️ Getting Started

### Prerequisites

- Java 17 SDK
- Maven 3.6+
- MySQL 8+ (or Docker container)
- (Optional) Docker & Docker Compose for local DB and test containers

### Installation

1. Clone the repo
   ```
   git clone https://github.com/your-org/quizmaker.git  
   cd quizmaker
   ```

2. Configure environment variables
   ``` 
   SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/quizdb  
   SPRING_DATASOURCE_USERNAME=quizuser  
   SPRING_DATASOURCE_PASSWORD=secret  
   JWT_SECRET=your_jwt_signing_secret  
   ```

3. Run database migrations
   ```  
   mvn flyway:migrate  
   ```

4. Start the application
   ```  
   mvn spring-boot:run  
   ```

The API will be available at `http://localhost:8080/api`.

---

## 📦 Database Schema

Below is a high-level summary of the core tables. For full DDL and examples, see the migration scripts in
`src/main/resources/db/migration`.

### 1. Users & Roles

| Table          | Purpose                                            |
|----------------|----------------------------------------------------|
| **users**      | Stores user credentials, audit flags, soft deletes |
| **roles**      | Enumerated roles: Admin, Moderator, Editor, User   |
| **user_roles** | Many-to-many mapping between users and roles       |

### 2. Quizzes & Tags

| Table          | Purpose                                              |
|----------------|------------------------------------------------------|
| **quizzes**    | Quiz metadata: title, visibility, difficulty, timers |
| **categories** | Category lookup for quizzes                          |
| **tags**       | Tag lookup for free-form labeling                    |
| **quiz_tags**  | Many-to-many mapping between quizzes and tags        |

### 3. Questions

| Table         | Purpose                                                                            |
|---------------|------------------------------------------------------------------------------------|
| **questions** | Stores each question; `content` is a JSON blob for MCQs, open text, hotspots, etc. |

### 4. User Answers & Results

| Table                 | Purpose                                                                |
|-----------------------|------------------------------------------------------------------------|
| **user_answers**      | Logs every answer attempt (JSON for submitted data)                    |
| **user_quiz_results** | Aggregates per-quiz results: scores, durations, spaced repetition info |

### 5. Feedback & Social

| Table            | Purpose                             |
|------------------|-------------------------------------|
| **quiz_ratings** | Star ratings and optional comments  |
| **comments**     | Free-form user comments on quizzes  |
| **followers**    | Follow users or quizzes for updates |

### 6. Notifications & Auditing

| Table             | Purpose                                       |
|-------------------|-----------------------------------------------|
| **notifications** | Scheduled or real-time messages to users      |
| **audit_logs**    | Records of CRUD actions, logins, role changes |

_All tables include `is_deleted` + `deleted_at` for soft deletes, plus `created_at` and `updated_at` timestamps for
audit readiness._

---

## 🛠️ Running Tests

We use JUnit 5, Mockito, and Testcontainers:

``` 
mvn test  
```

Integration tests will spin up a disposable MySQL container and run through key API flows.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push and open a Pull Request

Please follow our code style guidelines and write tests for new functionality.

---

## 📜 License

This project is licensed under the MIT License. See the `LICENSE` file for details.

---

Happy quizzing! 🎉

## 🔒 Security Enhancements (Latest Update)

### Critical Security Fix Applied

- **FIXED**: Questions sent to users during quiz attempts no longer expose correct answers
- **NEW**: `QuestionForAttemptDto` - Safe DTO that strips out all sensitive information
- **NEW**: `SafeQuestionContentBuilder` - Sanitizes question content before sending to users
- **NEW**: Enhanced attempt management with pause/resume functionality

### What was fixed:

- MCQ questions: Removed `"correct": true` flags from options
- TRUE_FALSE questions: Removed `"answer"` field
- FILL_GAP questions: Removed `"answer"` fields from gaps
- OPEN questions: Removed expected `"answer"` field
- COMPLIANCE questions: Removed `"compliant"` flags from statements
- HOTSPOT questions: Removed `"correct"` flags from regions
- ORDERING questions: Shuffled items to prevent pattern recognition

### Security Features:

- ✅ All answer validation happens server-side
- ✅ Questions are sanitized before sending to users
- ✅ Explanations are only shown after attempt completion
- ✅ User ownership validation on all attempt operations
- ✅ Timer enforcement for timed quizzes
- ✅ Suspicious activity flagging

## Enhanced Features

### 📊 Analytics & Statistics

- Detailed attempt statistics with timing data
- Question-level performance metrics
- Accuracy and completion percentages
- Individual question timing analysis

### 🔧 Attempt Management

- Pause and resume functionality for attempts
- Enhanced attempt status tracking (IN_PROGRESS, COMPLETED, ABANDONED, PAUSED)
- Shuffled question delivery to prevent cheating
- Better error handling and validation

### 🛡️ Security & Audit

- Safe question content delivery without answers
- Suspicious activity detection and flagging
- Comprehensive ownership validation
- Enhanced audit capabilities

## API Endpoints

### Safe Question Delivery

```
GET /api/v1/attempts/quizzes/{quizId}/questions/shuffled
```

Returns randomized questions without correct answers.

### Attempt Management

```
POST /api/v1/attempts/{attemptId}/pause    # Pause attempt
POST /api/v1/attempts/{attemptId}/resume   # Resume attempt
GET  /api/v1/attempts/{attemptId}/stats    # Get detailed stats
```

### Secure Answer Submission

```
POST /api/v1/attempts/{attemptId}/answers       # Submit single answer
POST /api/v1/attempts/{attemptId}/answers/batch # Submit multiple answers
```

## Safe JSON Examples

### MCQ Question (Safe for Users)

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "type": "MCQ_SINGLE",
  "questionText": "Which is correct Java syntax?",
  "safeContent": {
    "options": [
      {"id": "opt1", "text": "int x = 5;"},
      {"id": "opt2", "text": "int x := 5;"}
    ]
  },
  "hint": "Think about variable declaration"
}
```

### TRUE/FALSE Question (Safe for Users)

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "type": "TRUE_FALSE",
  "questionText": "Java is object-oriented.",
  "safeContent": {},
  "hint": "Consider Java's paradigm"
}
```

## Architecture

The application now uses a dual-DTO approach:

- `QuestionDto` - Full question data for admins/management
- `QuestionForAttemptDto` - Safe question data for users taking quizzes

This ensures that sensitive information like correct answers, explanations, and metadata are never exposed to users
during active quiz attempts.

## Running the Application

```bash
mvn spring-boot:run
```

## Testing

All security fixes have been applied with comprehensive test coverage to ensure:

- Correct answers are never exposed in API responses
- Answer validation works correctly on the backend
- User ownership is properly enforced
- Timing constraints are respected
