# HMS — Workspace Instructions

## Project

Full-stack Hospital Management System. Users: Doctors, Nurses, 20 clinical/admin roles, Patients.

## Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.4.x, PostgreSQL, Redis |
| Frontend | Angular 20, TypeScript (no NgRx — plain Services) |
| Android | Kotlin, Jetpack Compose, Hilt |
| iOS | Swift 5.9, SwiftUI, iOS 17+ |
| CI/CD | GitHub Actions → Railway (Docker) |
| Observability | Grafana Cloud, Prometheus (Actuator), Faro RUM |

## Backend Conventions

- Layered: `Controller → Service → Repository → Entity/DTO`
- Controllers are thin — all business logic lives in Services
- DTOs mapped via **hand-written `@Component` mapper classes** in `com.example.hms.mapper` — `toDto()` / `toEntity()` methods, builder pattern. **Do not introduce MapStruct.**
- Validate inputs at the Controller boundary (`@Valid`, `@Validated`)
- Package root: `com.example.hms`
- Security roles are string constants in `SecurityConstants.java` (20 roles, e.g. `ROLE_DOCTOR`, `ROLE_NURSE`, `ROLE_LAB_SCIENTIST`)

## Frontend Conventions

- State via plain Angular Services — no NgRx
- Strong TypeScript interfaces for all API models
- ESLint enforced — run `npm run lint` before considering work done
- Unit tests: Karma + Jasmine; E2E: Playwright

## Full-Stack Change Rule

Any change that touches an API must update **all** impacted layers:
1. DB schema / migration
2. Entity
3. Service logic
4. Controller + DTO
5. Mapper class
6. Frontend service / model
7. UI component
8. Tests at each layer

Never update one layer in isolation.

## Core Principles

1. Read existing code before changing it
2. Prefer incremental, additive changes — no large rewrites
3. Never hardcode secrets, tokens, or passwords
4. Sanitize all external input; enforce auth on every endpoint
5. Prevent N+1 queries; paginate list endpoints
6. Remove dead code and debug prints
7. Do not mark tasks done unless tests exist

## High-Risk Areas

Authentication, patient PHI, billing/payment, data migrations, cross-org record sharing.
Require peer review before merging changes in these areas.

## Docs

- Agent workflow agents: `.github/agents/`
- Prompt templates: `.github/prompts/`
- Architecture & deployment details: `agent/`
