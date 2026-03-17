# RepoLens

AI-powered documentation tool — **Phase 1: Ingestion Engine**

## Tech Stack

- **Java 25** (LTS)
- **Spring Boot 4.0.3**
- **Maven**
- **JGit** (repository cloning)
- **Lombok**, **Spring Web MVC**, **Spring Data JPA**
- **MySQL**
- **Virtual Threads** (Project Loom)

## Prerequisites

- JDK 25+
- Maven 3.9+
- MySQL 8+

## Setup

1. **Clone and build:**
   ```bash
   cd RepoLens
   mvn clean install
   ```

2. **Configure MySQL** in `application.properties`:
   ```properties
   spring.datasource.url=jdbc:mysql://localhost:3306/repolens
   spring.datasource.username=root
   spring.datasource.password=your_password
   ```

3. **Run the application:**
   ```bash
   mvn spring-boot:run
   ```

## API

### POST /api/repository/ingest

Ingests a GitHub repository: clones it, scans for Java files, and stores metadata.

**Request:**
```json
{
  "githubUrl": "https://github.com/spring-projects/spring-boot"
}
```

**Response:**
```json
{
  "id": 1,
  "owner": "spring-projects",
  "name": "spring-boot",
  "cloneUrl": "https://github.com/spring-projects/spring-boot.git",
  "fileCount": 1234,
  "totalLinesOfCode": 456789,
  "ingestedAt": "2026-03-17T12:00:00Z",
  "packageStructure": {
    "org.springframework.boot": 150,
    "org.springframework.boot.autoconfigure": 89
  }
}
```

## Architecture

- **Controller** → **Service** → **Repository** (layered)
- **JavaFileScanner**: Recursively finds `.java` files, maps packages, counts LOC
- **IngestionService**: JGit clone + scan + persist; uses Virtual Threads for non-blocking I/O
- **GlobalExceptionHandler**: Handles Git-related and validation errors with RFC 7807 Problem Details

## Project Structure

```
src/main/java/com/repolens/
├── RepoLensApplication.java
├── controller/RepositoryIngestionController.java
├── dto/IngestRequest.java, IngestResponse.java
├── entity/RepositoryMetadata.java
├── exception/GitOperationException.java, GlobalExceptionHandler.java
├── repository/RepositoryMetadataRepository.java
├── scanner/JavaFileScanner.java, ScanResult.java
└── service/IngestionService.java
```
