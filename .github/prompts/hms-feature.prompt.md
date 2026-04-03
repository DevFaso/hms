---
description: "Kick off a full-stack HMS feature. Plans all impacted layers then implements them. Use for: new feature, build a feature, add a feature, full-stack change."
name: "HMS Feature"
argument-hint: "Describe the feature to build (e.g. 'Add medication allergy tracking to patient records')"
agent: "agent"
tools: [read, edit, search, execute, todo]
---
You are implementing a new feature for the Hospital Management System using the HMS standards.

**Feature Request:** $input

## Step 1 — Plan

Before writing any code:
1. Search for existing related entities, services, controllers, mappers, and frontend components.
2. Identify every layer that must change (DB, Entity, Repo, Service, Controller, DTO, Mapper, Frontend model, Frontend service, UI component, Tests).
3. Identify which of the 20 security roles need access.
4. Flag any high-risk areas (auth, PHI, billing, migration).
5. Use the todo tool to create an ordered task list.

## Step 2 — Implement

Follow this strict order, completing and checking off each task:

1. **DB migration** — additive SQL in `hospital-core/src/main/resources/db/migration/`
2. **Entity** — in `com.example.hms.entity`
3. **Repository** — in `com.example.hms.repository`, avoid N+1
4. **Service interface + impl** — in `com.example.hms.service`
5. **DTO(s)** — request/response in `com.example.hms.payload.dto`
6. **Controller** — thin, `@PreAuthorize` with `SecurityConstants` role
7. **Mapper** — `@Component` in `com.example.hms.mapper`, `toDto()` + `toEntity()`
8. **Frontend TypeScript interface** — in `hospital-portal/src/app/`
9. **Frontend Angular service** — HTTP call, typed response
10. **UI component** — HTML + SCSS + component class with loading/error/empty states
11. **Tests** — JUnit (service), MockMvc (controller), Karma/Jasmine (frontend)

## Step 3 — Verify

- Run `./gradlew :hospital-core:compileJava` — must compile cleanly
- Run `npm run lint` from `hospital-portal/` — must pass
- Confirm all todos are marked complete with tests written
