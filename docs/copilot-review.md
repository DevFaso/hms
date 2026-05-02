feature/recep-department

 Quality Gate failed
Failed conditions
 45.6% Coverage on New Code (required ≥ 80%)

See analysis details on SonarQube Cloud

hospital-core/src/main/java/com/example/hms/service/DepartmentServiceImpl.java
Comment on lines 112 to +116
    public Page<DepartmentResponseDTO> getAllDepartments(Pageable pageable, Locale locale) {
        return departmentRepository.findAll(pageable)
                .map(department -> buildLocalizedResponse(department, locale));
        UUID activeHospitalId = resolveActiveHospitalScope();
        Page<Department> page = (activeHospitalId != null)
            ? departmentRepository.findByHospitalId(activeHospitalId, pageable)
            : departmentRepository.findAll(pageable);
Copilot commented 2 minutes ago
Copilot
Copilot
AI
2 minutes ago
New tenant-scoped behavior is introduced here (non-super-admins are now restricted to activeHospitalId and super-admins are unscoped), but DepartmentServiceImplTest currently only exercises these read paths in the default super-admin setup. Please add/extend unit tests covering at least: (1) non-super-admin list/read/search/filter only returns departments for requireActiveHospitalId(), and (2) cross-hospital hospitalId requests are rejected.

Copilot generated this review using guidance from repository custom instructions.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/DepartmentServiceImpl.java
Comment on lines +441 to +446
        if (filter.getHospitalId() != null && !filter.getHospitalId().equals(activeHospitalId)) {
            log.warn("[dept:tenantGuard] User {} attempted to filter by hospital {} but is scoped to {}",
                    roleValidator.getCurrentUserId(), filter.getHospitalId(), activeHospitalId);
            throw new BusinessRuleException("You may only access departments within your assigned hospital.");
        }
        filter.setHospitalId(activeHospitalId);
Copilot commented 2 minutes ago
Copilot
Copilot
AI
2 minutes ago
applyTenantScopeToFilter throws BusinessRuleException, but BusinessRuleException is not mapped in GlobalExceptionHandler, so this will currently fall through to the generic runtime handler and return HTTP 500. Consider switching this to a handled exception type (e.g., BusinessException, IllegalStateException, AccessDeniedException, or ResourceNotFoundException) so invalid cross-hospital filters return a 4xx response.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/DepartmentServiceImpl.java
Comment on lines +423 to +428
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (requestedHospitalId != null && !requestedHospitalId.equals(activeHospitalId)) {
            log.warn("[dept:tenantGuard] User {} attempted to address hospital {} but is scoped to {}",
                    roleValidator.getCurrentUserId(), requestedHospitalId, activeHospitalId);
            throw new BusinessRuleException("You may only access departments within your assigned hospital.");
        }
Copilot commented 2 minutes ago
Copilot
Copilot
AI
2 minutes ago
enforceHospitalScopeOnHospitalId throws BusinessRuleException, but BusinessRuleException is not handled by GlobalExceptionHandler (only BusinessException/IllegalStateException etc. are), so this will currently fall through to the generic @ExceptionHandler(RuntimeException) and return HTTP 500. Consider throwing AccessDeniedException/ResourceNotFoundException (or BusinessException) instead, or add explicit handling for BusinessRuleException so cross-hospital requests don’t become 500s.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/DepartmentServiceImpl.java
Comment on lines 267 to +268
        Department department = findDepartmentOrThrow(departmentId, locale);
        enforceHospitalScopeOnEntity(department);
Copilot commented 2 minutes ago
Copilot
Copilot
AI
2 minutes ago
These newly added read paths call enforceHospitalScopeOnEntity(...), but that helper currently builds the “not found” message using DEFAULT_LOCALE rather than the request locale. That means a tenant-scope 404 can ignore the caller’s language even though the controller/service received a locale; consider passing locale into the helper (or otherwise using the request locale) while still returning a generic not-found message.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-portal/src/app/core/role-context.service.ts
  isReceptionist(): boolean {
    return this.hasRole('RECEPTIONIST');
    // Roles in the JWT/auth context are stored with the `ROLE_` prefix.
    // Accept both shapes so a missing prefix never silently passes the check.
Copilot commented 2 minutes ago
Copilot
Copilot
AI
2 minutes ago
The comment says “missing prefix never silently passes the check”, but based on the logic here the goal is to avoid the check silently failing when roles are stored as ROLE_*. Please update the wording so it matches the intent/behavior.

Suggested changeset 1 (1)
hospital-portal/src/app/core/role-context.service.ts
Original file line number	Diff line number	Diff line change
 export class RoleContextService {
   isReceptionist(): boolean {
     // Roles in the JWT/auth context are stored with the `ROLE_` prefix.
     // Accept both shapes so a missing prefix never silently passes the check.
     // Accept both shapes so a missing prefix never silently fails the check.
     return this.hasRole('ROLE_RECEPTIONIST') || this.hasRole('RECEPTIONIST');
   }
   private readonly _activeHospitalId = signal<string | null>(null);