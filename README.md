# HMS – Hospital Management System

Full-stack hospital management platform built with **Spring Boot 3.4** + **Angular 20** and deployed on **Railway**.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.4.11, Java 21, Gradle |
| Frontend | Angular 20, TypeScript 5.9, Vite |
| Database | PostgreSQL 16 (Railway) / H2 (local) |
| Auth | JWT + RBAC (5 roles) |
| Migrations | Liquibase |
| CI/CD | GitHub Actions → Railway |
| i18n | ngx-translate (EN/FR/ES) |

## Quick Start

### Prerequisites
- Java 21 (Temurin)
- Node.js 20
- Docker (optional, for local PostgreSQL)

### Backend (zero-config with H2)
```bash
cd hospital-core
./gradlew bootRun -Pargs='--spring.profiles.active=local-h2'
# → http://localhost:8081
```

### Backend (with PostgreSQL)
```bash
docker compose up -d            # starts PostgreSQL
cd hospital-core
./gradlew bootRun               # uses dev profile
```

### Frontend
```bash
cd hospital-portal
npm install
npm start
# → http://localhost:4200 (proxied to backend at :8081)
```

## Project Structure

```
hms/
├── hospital-core/              # Spring Boot backend
│   └── src/main/java/com/example/hms/
│       ├── controller/         # REST endpoints
│       ├── service/            # Business logic
│       ├── repository/         # Data access (JPA)
│       ├── model/              # JPA entities
│       ├── payload/dto/        # DTOs
│       ├── mapper/             # MapStruct mappers
│       ├── security/           # JWT + Spring Security
│       ├── config/             # Configuration classes
│       └── exception/          # Global error handling
├── hospital-portal/            # Angular frontend
│   └── src/app/
│       ├── core/               # Singleton services, guards
│       ├── shared/             # Reusable components
│       ├── auth/               # Login / registration
│       ├── dashboard/          # Role-based dashboards
│       └── services/           # API services
├── .github/workflows/          # CI/CD pipelines
├── docker-compose.yml          # Local PostgreSQL
├── Dockerfile                  # Production container
└── railway.toml                # Railway deployment config
```

## Spring Profiles

| Profile | Database | Use Case |
|---------|----------|----------|
| `local-h2` | H2 in-memory | Zero-config local dev |
| `dev` | PostgreSQL (Railway `DATABASE_URL`) | Staging / shared dev |
| `prod` | PostgreSQL (Railway) | Production |
| `test` | H2 in-memory | Automated tests |

## Quality Gates

```bash
# Backend
cd hospital-core && ./gradlew clean test

# Frontend
cd hospital-portal
npm run lint
npm run format:check
npm run test:headless
```

## Environment Variables

Copy `.env.example` and fill in values:

| Variable | Required | Description |
|----------|----------|-------------|
| `DATABASE_URL` | Railway auto-provides | PostgreSQL connection |
| `APP_JWTSECRET` | Yes | JWT signing secret (256-bit) |
| `APP_BOOTSTRAP_TOKEN` | Yes | First admin signup token |
| `PORT` | Railway auto-provides | Server port |
| `SPRING_PROFILES_ACTIVE` | Yes | `dev`, `prod`, etc. |

## Deployment

Deployed via **Railway** with GitHub integration:
1. Push to `main` triggers deploy via GitHub Actions
2. Railway builds using the `Dockerfile`
3. Health check at `/api/actuator/health`

## License

Private – All rights reserved.
