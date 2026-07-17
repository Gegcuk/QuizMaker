# QuizMaker Annotation Study Guide

This is a maintained study reference for the Java, Spring Boot, JPA/Hibernate, Jackson, OpenAPI, Lombok, and JUnit annotations used in QuizMaker. It is organised by responsibility rather than alphabetically so that the relationship between annotations is clearer when reading production code.

Read [the annotation study guide](spring-boot-annotation-study-guide.md) alongside the nearest real class in `src/main/java` or `src/test/java`. The guide explains what an annotation does; the codebase shows when this project uses it.

## Topics

- Spring configuration and dependency injection
- REST endpoints, validation, error handling, and OpenAPI
- Security, permissions, and asynchronous/event processing
- JPA entities, associations, queries, transactions, and locking
- Jackson JSON mapping
- Lombok
- Unit, MVC, JPA, and integration testing

The source tree is the authoritative inventory. Search current usages with:

```bash
rg -o --no-filename '@[A-Za-z][A-Za-z0-9_]*' src/main/java src/test/java | sort -u
```

When an annotation is unfamiliar, first identify its package/import, then read the related project example and its tests. Do not copy an annotation from a study example without understanding its lifecycle, transaction, or security implications.
