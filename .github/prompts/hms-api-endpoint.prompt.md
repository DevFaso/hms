---
description: "Add a single new REST API endpoint to the HMS backend. Creates controller method, service logic, DTO, and mapper. Use for: new endpoint, new API, add route, add REST method."
name: "HMS API Endpoint"
argument-hint: "Describe the endpoint (e.g. 'GET /api/patients/{id}/allergies — returns a patient allergy list, accessible by ROLE_DOCTOR and ROLE_NURSE')"
agent: "agent"
tools: [read, edit, search]
---
Add a new REST API endpoint to the Hospital Management System backend.

**Endpoint Request:** $input

## Checklist

Work through each item in order:

### 1. DTO
- Create or extend a request/response DTO in `com.example.hms.payload.dto`
- Use `@Valid`, `@NotNull`, `@NotBlank` constraints on request fields
- Match field names to the existing naming conventions in the package

### 2. Mapper
- Add `toDto()` / `toEntity()` method to the relevant `@Component` mapper in `com.example.hms.mapper`
- If no mapper exists for this entity, create one following the pattern of an existing mapper
- Use builder pattern for DTO construction

### 3. Service
- Add the method signature to the service interface
- Implement it in the `ServiceImpl` class
- Include authorization check if applicable
- Prevent N+1: use `JOIN FETCH` or `@EntityGraph` if loading associations

### 4. Controller
- Add the handler method to the appropriate `@RestController`
- Use `@PreAuthorize("hasRole(SecurityConstants.ROLE_X)")` — check `SecurityConstants.java` for the right constant
- Delegate entirely to the service — no business logic in the controller
- Return `ResponseEntity` with appropriate HTTP status

### 5. Tests
- **Service unit test** (JUnit 5 + Mockito): happy path + not-found + validation failure
- **Controller integration test** (MockMvc): 200/201, 400 (invalid input), 403 (wrong role), 404 (not found)

## Constraints

- Do NOT introduce MapStruct
- Do NOT hardcode role strings — use `SecurityConstants` constants
- Do NOT skip tests
