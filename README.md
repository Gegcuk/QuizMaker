# QuizMaker Backend

Welcome to **QuizMaker** backend! This Spring Boot application powers a flexible, secure, and future-proof quiz platform with JSON-based question storage, audit trails, role management, and more.

---

## 🚀 Overview

QuizMaker lets you create, manage, and deliver quizzes with ease. On the backend, we:

- Store question definitions in MySQL using the native `JSON` column type for flexibility  
- Never expose correct answers to the frontend—grading happens securely on the server  
- Track every user’s answers and quiz results in dedicated tables  
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
   git clone https://github.com/your-org/quizmaker-backend.git  
   cd quizmaker-backend  
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

Below is a high-level summary of the core tables. For full DDL and examples, see the migration scripts in `src/main/resources/db/migration`.

### 1. Users & Roles

| Table         | Purpose                                         |
| ------------- | ----------------------------------------------- |
| **users**     | Stores user credentials, audit flags, soft deletes |
| **roles**     | Enumerated roles: Admin, Moderator, Editor, User |
| **user_roles**| Many-to-many mapping between users and roles    |

### 2. Quizzes & Tags

| Table         | Purpose                                                |
| ------------- | ------------------------------------------------------ |
| **quizzes**   | Quiz metadata: title, visibility, difficulty, timers   |
| **categories**| Category lookup for quizzes                            |
| **tags**      | Tag lookup for free-form labeling                      |
| **quiz_tags** | Many-to-many mapping between quizzes and tags          |

### 3. Questions

| Table         | Purpose                                                                 |
| ------------- | ----------------------------------------------------------------------- |
| **questions** | Stores each question; `content` is a JSON blob for MCQs, open text, hotspots, etc. |

### 4. User Answers & Results

| Table                | Purpose                                                                 |
| -------------------- | ----------------------------------------------------------------------- |
| **user_answers**     | Logs every answer attempt (JSON for submitted data)                     |
| **user_quiz_results**| Aggregates per-quiz results: scores, durations, spaced repetition info  |

### 5. Feedback & Social

| Table           | Purpose                             |
| --------------- | ----------------------------------- |
| **quiz_ratings**| Star ratings and optional comments  |
| **comments**    | Free-form user comments on quizzes  |
| **followers**   | Follow users or quizzes for updates |

### 6. Notifications & Auditing

| Table            | Purpose                                           |
| ---------------- | ------------------------------------------------- |
| **notifications**| Scheduled or real-time messages to users          |
| **audit_logs**   | Records of CRUD actions, logins, role changes     |

_All tables include `is_deleted` + `deleted_at` for soft deletes, plus `created_at` and `updated_at` timestamps for audit readiness._

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
