# HMS Monolith - Phased Deployment Plan

> Strategy: Deploy incrementally from `main` branch. Each MVP is a separate commit/PR.
> Each MVP must compile and deploy independently. Later MVPs build on earlier ones.

---

## MVP 0: Infrastructure & Shared Foundation _(Deploy First)_
**Goal**: All shared base classes, configs, security, exceptions — the skeleton everything depends on.

### Files:
```
# Already committed (baseline)
HmsApplication.java
config/CorsConfig.java
config/DataSourceConfig.java
build.gradle (updated with all deps)
application.properties, application-*.yml

# New: Core configs
config/AuditingConfig.java
config/BootstrapProperties.java
config/FeatureFlagProperties.java
config/JacksonConfig.java
config/JwtProperties.java
config/KafkaConfig.java
config/KafkaProperties.java
config/LocalDevConfiguration.java
config/LocaleBinderConfig.java
config/LocaleConfig.java
config/OpenApiConfig.java
config/OrganizationSecurityConstants.java
config/PasswordRotationPolicy.java
config/PlatformIntegrationProperties.java
config/PortalProperties.java
config/RenderDatabaseEnvironmentPostProcessor.java
config/ResolverConfig.java
config/S3Config.java
config/SecurityConfig.java
config/SecurityConstants.java
config/TenantRepositoryConfig.java
config/TimeConfig.java
config/TwilioConfig.java
config/TwilioProperties.java
config/WebConfig.java
config/WebSocketConfig.java

# Security layer (entire package)
security/**

# Exceptions (entire package)
exception/**

# Base model
model/BaseEntity.java
model/converter/**

# Shared enums (core ones used by multiple MVPs)
enums/AccountStatus.java
enums/AppointmentStatus.java
enums/AuditAction.java
enums/Gender.java
enums/MaritalStatus.java
enums/BloodType.java
enums/LanguageCode.java
# ... (all enums/ — they're lightweight, include all)

# Shared utilities
utility/**
helper/**
component/**
specification/**

# Bootstrap & seed
bootstrap/**
seed/**

# Shared DTOs (auth/user/role/permission)
payload/dto/*Request*.java (auth-related)
payload/dto/*Response*.java (auth-related)

# Resources
messages*.properties
schema-h2.sql
db/migration/**
static/**
feature-flags.json
```

**Estimated files**: ~350
**Risk**: Low — no business logic, just infrastructure

---

## MVP 1: Auth, Users, Roles & Permissions
**Goal**: Login, signup, bootstrap admin, RBAC, user/role/permission management.

### Controllers:
- AuthController
- UserController
- RoleController
- PermissionController
- PermissionMatrixController
- PasswordResetController
- UsernameReminderController
- MeController

### Models:
- User, UserRole, UserRoleId
- Role, Permission
- PermissionMatrixSnapshot, PermissionMatrixAuditEvent
- PasswordResetToken
- UserMfaEnrollment, UserRecoveryContact, UserProgress
- Staff
- Hospital, Organization

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~200 additional
**Depends on**: MVP 0

---

## MVP 2: Departments, Hospitals & Staff
**Goal**: Organization hierarchy, department management, staff profiles, availability.

### Controllers:
- HospitalController
- DepartmentController
- OrganizationController
- StaffController
- StaffAvailabilityController
- StaffSchedulingController
- UserRoleHospitalAssignmentController

### Models:
- Department, DepartmentTranslation
- StaffAvailability, StaffShift, StaffLeaveRequest
- UserRoleHospitalAssignment
- ServiceTranslation

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~100 additional
**Depends on**: MVP 1

---

## MVP 3: Patient Management
**Goal**: Patient registration, demographics, medical history, insurance, vitals.

### Controllers:
- PatientController
- PatientHospitalRegistrationController
- PatientInsuranceController
- PatientVitalSignController
- PatientConsentController
- PatientPrimaryCareController
- MedicalHistoryController
- MedicationHistoryController

### Models:
- Patient, PatientHospitalRegistration
- PatientAllergy, PatientFamilyHistory, PatientImmunization
- PatientInsurance, PatientPrimaryCare
- PatientProblem, PatientProblemHistory
- PatientSocialHistory, PatientSurgicalHistory
- PatientVitalSign, PatientConsent
- model/empi/**

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~150 additional
**Depends on**: MVP 2

---

## MVP 4: Appointments & Encounters
**Goal**: Scheduling, encounters, consultations, prescriptions.

### Controllers:
- AppointmentController
- EncounterController
- ConsultationController
- PrescriptionController
- TreatmentController
- TreatmentPlanController
- EncounterTreatmentController

### Models:
- Appointment, Encounter, EncounterHistory
- Consultation
- Prescription, Treatment, EncounterTreatment
- model/encounter/**
- model/treatment/**

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~120 additional
**Depends on**: MVP 3

---

## MVP 5: Lab, Imaging & Clinical Orders
**Goal**: Lab orders/results, imaging orders/reports, procedure orders.

### Controllers:
- LabOrderController, LabResultController, LabTestDefinitionController
- PatientLabResultController
- ImagingOrderController, ImagingResultController
- ProcedureOrderController
- DigitalSignatureController

### Models:
- LabOrder, LabResult, LabTestDefinition, LabTestReferenceRange
- ImagingOrder, ImagingReport, ImagingReportAttachment, ImagingReportMeasurement, etc.
- ProcedureOrder
- model/signature/**

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~120 additional
**Depends on**: MVP 4

---

## MVP 6: Nursing, Discharge & Admission
**Goal**: Nursing notes, admission workflows, discharge summaries.

### Controllers:
- NursingNoteController, NurseTaskController
- AdmissionController
- DischargeSummaryController, DischargeApprovalController

### Models:
- NursingNote, NursingNoteAddendum, NursingNoteTemplate, etc.
- Admission, AdmissionOrderSet
- DischargeApproval
- model/discharge/**

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~80 additional
**Depends on**: MVP 4

---

## MVP 7: Billing, Notifications & Communication
**Goal**: Invoicing, notifications, chat, announcements.

### Controllers:
- BillingInvoiceController, InvoiceItemController, InvoiceEmailController
- NotificationController, NotificationWebSocketController
- ChatController
- AnnouncementController

### Models:
- BillingInvoice, InvoiceItem
- Notification, ChatMessage, Announcement

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~60 additional
**Depends on**: MVP 1

---

## MVP 8: Advanced Clinical (OB/GYN, Education, Referrals)
**Goal**: Specialty clinical features — maternal, newborn, prenatal, education, referrals.

### Controllers:
- ObgynReferralController, GeneralReferralController
- MaternalHistoryController, PrenatalSchedulingController
- HighRiskPregnancyCarePlanController
- BirthPlanController, PostpartumCareController
- NewbornAssessmentController
- UltrasoundController
- PatientEducationController
- PatientMedicationController
- PatientRecordSharingController

### Models:
- MaternalHistory, BirthPlan
- model/postpartum/**, model/highrisk/**, model/neonatal/**
- model/referral/**, model/education/**, model/medication/**
- GeneralReferral, GeneralReferralAttachment, ReferralAttachment
- UltrasoundOrder, UltrasoundReport
- EducationResource

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~remaining files
**Depends on**: MVP 5, MVP 6

---

## MVP 9: Super Admin & Platform Operations
**Goal**: Platform registry, security governance, audit, feature flags.

### Controllers:
- SuperAdmin*Controller (all 7)
- OrganizationSecurity*Controller (all 3)
- FeatureFlagController
- AuditEventLogController
- FrontendAuditController
- ReferenceCatalogController
- LookupController, PublicContentController
- controller/platform/**

### Models:
- OrganizationSecurityPolicy, OrganizationSecurityRule
- AuditEventLog, FrontendAuditEvent
- model/platform/**, model/reference/**, model/security/**

### Services + Repos + Mappers + DTOs for above

**Estimated files**: ~remaining files
**Depends on**: MVP 2

---

## Deployment Checklist Per MVP

- [ ] Create feature branch: `mvp-X-<name>`
- [ ] Stage only the files for that MVP
- [ ] Run `./gradlew :hospital-core:compileJava` — must pass
- [ ] PR to `main`
- [ ] Railway auto-deploys DEV
- [ ] Verify DEV health: `curl api.hms.dev.bitnesttechs.com:8080/api/actuator/health`
- [ ] Promote to UAT
- [ ] Verify UAT health
- [ ] Merge & tag: `mvp-X-done`

---

## Rollback Procedures

### Release Tags

| Tag | Commit | Description |
|-----|--------|-------------|
| `v0.1.0` | `1c9304f` | First successful Railway dev deployment (current) |
| `v0.1.0-rc1` | `df3d2ee` | MVP2 merge — pre-deployment baseline |

### Code Rollback (Railway Dev)

Railway auto-deploys from the `develop` branch. To roll back:

```bash
# 1. Roll back develop to a known-good tag
git checkout develop
git reset --hard v0.1.0          # or any tagged version
git push origin develop --force  # Railway will auto-redeploy

# 2. Or roll back to a specific commit
git reset --hard <commit-sha>
git push origin develop --force
```

### Database Rollback (Liquibase)

⚠️ **Liquibase migrations are forward-only by default.** Rolled-back code will
skip already-applied changesets, but **will NOT undo DDL/DML changes**.

For a true DB rollback you must:

```bash
# Option 1: Liquibase rollback (if rollback SQL was generated)
# Connect to the Railway DB and run:
liquibase rollbackCount 1

# Option 2: Manual — remove the changeset record and reverse the SQL
DELETE FROM public.databasechangelog WHERE id = '<changeset-id>';
-- Then manually run the reverse DDL/DML

# Option 3: Nuclear — drop and recreate (DEV ONLY)
# Railway dashboard → Postgres plugin → Delete → Re-create
# Redeploy app — Liquibase will re-run all migrations from V1
```

### Current Migration State (Railway Dev)

| Changeset | Status | Description |
|-----------|--------|-------------|
| V1-initial-schema | ✅ Applied | 153 tables, 10 schemas |
| V1.1-add-unique-constraints | ✅ Applied | Unique indexes on roles, orgs, users |
| V2-seed-roles | ✅ Applied | 24 security roles |
| V3-seed-default-org-security | ✅ Applied | DEFAULT_ORG + security policies |

### Emergency Rollback Checklist

1. **Identify the last known-good tag**: `git tag -l`
2. **Reset develop**: `git reset --hard <tag> && git push origin develop --force`
3. **Wait for Railway redeploy** (~90s build + ~30s startup)
4. **Verify healthcheck**: `curl <railway-url>/api/actuator/health`
5. **If DB migration caused the issue**: see Database Rollback above
6. **Post-mortem**: document what went wrong, create a hotfix branch

---

## Key Principle

> **Each MVP must compile and boot on its own.**
> If a controller references a service that references a model not yet deployed,
> that controller goes in a later MVP — not this one.
