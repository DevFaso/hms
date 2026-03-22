Executive Summary
The DevFaso/hms system currently includes a range of “Super Admin”–related capabilities in code, configurations, and UI. These include organization/hospital provisioning, a global dashboard, cross-tenant lab-ordering, user management (bulk import, password resets, password-rotation status), and a role-based security framework (with a ROLE_SUPER_ADMIN that inherits all other roles
). However, many enterprise-grade Super Admin features expected in a large hospital/EHR (Epic-style) are missing or incomplete. Critical missing capabilities include comprehensive audit/compliance controls (e.g. retention of audit logs, tamper-proof logging, encryption in transit/rest), SSO and MFA, global settings management, feature flags, backups/DR tools, detailed global reporting and analytics, integration safeguards, and tenant onboarding/migration tooling. HIPAA and multi-tenant SaaS best-practices emphasize strong RBAC, encryption, and logging (e.g. HIPAA mandates 6+ years of audit log retention
, encryption at rest/in transit, and centralized audit trails
). The report below catalogs all existing Super Admin code/config (file paths and purpose), contrasts these with the expected feature set, identifies gaps, and lays out a prioritized MVP roadmap with tasks, deliverables, and a timeline.

Existing Super Admin–Related Functionality (code, config, DB, APIs, UI, permissions, tests)
Role/Permissions: Role.java enum defines ROLE_SUPER_ADMIN as “Global admin with full authority”
. In SecurityConfig, a GrantedAuthoritiesMapper automatically grants a Super Admin all lower-level roles
. Security rules permit Super Admin on all sensitive endpoints (e.g. tenant creation/update, assignments)
.
Controllers & APIs:
Super-Admin Organization API: SuperAdminOrganizationController.java (e.g. GET /super-admin/organizations/summary, GET /hierarchy, POST /organizations, POST/DELETE /organizations/{org}/hospitals/{hosp})
 – provides global org/hospital overview and provisioning.
Super-Admin Dashboard API: SuperAdminDashboardController.java (e.g. GET /super-admin/summary, GET /appointments, /patients, /encounters, /staff-availability, /patient-consents)
 – returns aggregate counts and recent activity across all hospitals, used by the Angular portal.
Super-Admin Lab-Order API: SuperAdminLabOrderController.java (POST /super-admin/lab-orders)
 – allows creation of lab orders by name/alias (maps to IDs under the hood).
Super-Admin User Governance API: SuperAdminUserGovernanceController.java (POST /super-admin/users/create, /import, /force-password-reset; GET /password-rotation)
 – supports global user creation (via AdminSignupRequest), CSV bulk import of users, forcing password resets, and listing users’ password-rotation status.
Security/Compliance APIs: Partially present via governance services:
Security Rule Governance: (no exposed controller found) code provides rule-set creation/simulation/import (e.g. SecurityRuleGovernanceService.createRuleSet)
 with DTOs (SecurityRuleSetRequestDTO, templates, etc
).
Security Policy Governance: service for policy baselines and approvals (createBaseline, listPendingApprovals, exportLatestBaseline)
. There is a SecurityPolicyApprovalSummaryDTO
 and JPA entity security_policy_approvals
, but no obvious REST controller endpoint in code.
DTOs (Data Models): Numerous superadmin DTOs exist under payload/dto/superadmin/: organization requests/responses, lab-order requests, security rule templates/definitions, security policy baseline/approval DTOs
, and all the user-governance DTOs (bulk import and password reset requests/responses, password-rotation status)
. Also SuperAdminSummaryDTO
 used to return dashboard totals (total users/hospitals/etc and recent audit events).
Database Schema: Key tables supporting Super Admin features include:
security_policy_baselines and security_policy_approvals in the governance schema (entities SecurityPolicyBaseline, SecurityPolicyApproval
).
security_rule_sets (from SecurityRuleSet entity) likely for rule-governance.
Core tables Organization, Hospital, User, Staff, Role, UserRoleHospitalAssignment under default schema, enabling the multi-tenant data model.
Audit logs: Although audit events appear in summary DTO, their storage (e.g. an audit_log table) is not obvious; presumably Spring Security or application auditing is configured (the dashboard service fetches “recent audit events” but code not shown here).
Configuration:
Spring Security: Method-level @PreAuthorize("hasRole('SUPER_ADMIN')") on all super-admin controllers ensures only super admins can call them (e.g.
).
GrantedAuthoritiesMapper: ensures that ROLE_SUPER_ADMIN automatically includes other roles
.
Application Security Constants: SecurityConfig defines ROLE_SUPER_ADMIN and privileges, and specifically restricts tenant/hospital mutation endpoints to Super Admins only
.
CORS/JWT: JWT filter and AuthenticationProvider are configured, but SSO/OAuth integration is not present.
UI (Angular):
Dashboard: The main portal’s DashboardComponent recognizes ROLE_SUPER_ADMIN and calls the backend’s /super-admin/summary API
, displaying the SuperAdminSummary data and recent audit events on a “Super Admin” summary page. No dedicated Angular pages for user import, org creation, or security settings were found in the repo. Role-context service flags (isSuperAdmin = auth.hasAnyRole(['ROLE_SUPER_ADMIN'])) control UI layout/themes
.
Tests:
SuperAdminUserGovernanceControllerTest verifies the user governance endpoints (create, import, force-reset) behave correctly
.
SuperAdminLabOrderServiceImplTest covers the lab-order service logic (ID resolution and delegation to LabOrderService)
.
SecurityRuleGovernanceServiceImplTest (implied from search) likely tests rule-set logic. There were no obvious tests for organization or dashboard service, or for the security policy service.
Epic-Style Hospital Super Admin Capabilities (expected)
A hospital group super-admin (e.g. Epic enterprise admin) is expected to manage across the entire multi-tenant environment. Key capabilities include:

User/Org Management: Create and manage all users, roles, and organizational units (networks, hospitals, clinics). Assign and revoke roles on any hospital/department. Bulk user import (CSV), password resets, forced rotations (for compliance), and role changes across tenants.
Multi-Tenant Configuration: Manage “tenants” (e.g. separate healthcare networks) globally: create/activate/deactivate them, assign hospitals to them, configure tenant-level settings. Support true logical isolation of data per tenant. (In a SaaS EHR, this often means separate schemas or tenancy ID filtering.)
Global Settings and Governance: Control system-wide configuration (feature flags, default policies, regional settings). Push global policy changes (security policies, password policies, data sharing policies) that apply to all tenants or selectively. Maintain a central registry of modules/integrations (e.g. lab/EHR interfaces per tenant).
Audit & Compliance: Maintain comprehensive audit trails across all tenants: track all user actions on PHI, configuration changes, and admin actions. Ensure logs are immutable, centralized, and retained per HIPAA rules (minimum 6 years
). Generate compliance reports (e.g. who accessed patient data, user role changes, etc). Manage security/compliance baselines and approvals (e.g. security policy approvals as in ISO/IEC 27001, HIPAA security rule).
Role/Permission Model: A fine-grained RBAC model that a Super Admin can configure. Ability to create new roles/permissions, and assign global roles (e.g. a global “Auditor” role), define cross-tenant RBAC rules, and configure two-factor authentication or SSO policies by role.
Single Sign-On & Identity: Enterprise SSO integration (SAML/OAuth2) to connect with the health system’s identity provider, with the ability to configure it. Enforce policies like MFA and automated provisioning/deprovisioning (e.g. via SCIM).
Data Access Controls: Ensure tenant data isolation (row-level security) and implement global sharing settings (if needed) with patient consent. In an Epic-style environment, manage patient portal access, inter-hospital referrals, and global population health queries.
Billing & Finance: Oversee billing configurations (e.g. insurance rules, fee schedules) across hospitals. Manage enterprise billing reports, integration with financial systems, and audit billing compliance.
Reporting & Analytics: Provide cross-hospital analytics (operational reports, compliance dashboards, KPIs). Enable exports of aggregated data for enterprise reporting or research (while respecting de-identification and consent).
Backups & DR: Configure and monitor backups, disaster recovery processes, and environment health. Ensure high-availability and failover for the entire system. Maintain data migrations/upgrades across tenants (for example, migrating historical data when adding a new module).
Environment/Feature Management: Manage deployment environments (dev/test/prod), release windows, feature-flag rollouts, and upgrades globally. Oversee integrations with ancillary systems (lab, pharmacy, imaging) and connectivity (VPNs, HL7 interfaces).
Monitoring & Alerts: Monitor system health and security across all tenants (audit logs, intrusion detection, performance). Receive alerts on suspicious activity (e.g. large data exports, repeated login failures) or service issues.
Many of these expectations derive from healthcare IT best practices: e.g. multi-tenant SaaS must enforce strict least-privilege RBAC and SSO/MFA
; all protected health information (PHI) access must be audited with logs maintained per HIPAA (>=6 years)
; encryption for data at rest/in transit is mandatory
; and continuous monitoring/alerts of unusual activity is recommended for early breach detection
.

Existing vs. Required Super Admin Features
Capability	Existing in DevFaso/hms	Epic-Style Requirement (Expected)	Notes / Evidence
Tenant/Org Management	APIs to create organizations (SuperAdminOrganizationController) and assign hospitals
. Hospital list per tenant exists.	Full management of multi-tenant structure: create/edit/delete tenant (network, hospital, clinics), tenant-wide configs.	Basic features exist, but no UI or advanced settings per tenant.
User Management	Bulk import (/super-admin/users/import), create user, force password reset
. Password-rotation status list
.	Manage all users across tenants, dynamic roles, SSO/MFA policies. Extensive approval workflows.	Import/create exist
, but lack UI, no SSO/MFA, no role creation.
RBAC & Roles	Role enum with many roles; Super Admin inherits all
. SecurityConfig restricts endpoints to roles (includes super).	Configurable RBAC: ability to add/edit roles and permissions globally.	Only static roles exist. No runtime role admin UI/service.
SSO / Identity	None. Currently uses JWT with username/password.	Enterprise SSO (SAML/OAuth2) integration, MFA enforcement.	EHRs require SSO for enterprise health systems
.
Global Settings / Policies	Default policies applied on org creation (default security policies via OrganizationSecurityService)
; baseline policy service exists.	Central policy management: configure password policies, session timeouts, feature flags, etc., per tenant or global.	Basic baseline creation exists
, but no UI & limited scope.
Audit Logs & Compliance	Dashboard shows recent audit events (stored by SuperAdminSummaryDTO)
. No obvious admin UI for logs.	Full audit trail with tamper-proof logs, alerts on policy violations, data-export logs, and compliance reports (HIPAA).	Audit retention not addressed; HIPAA requires 6-year retention
.
Security Governance	Services for creating rule sets, importing templates (security rules), and simulating policies exist
. Policy baseline/approvals exist
.	Full security workflow: define global privacy/security baselines, required approvals (e.g. for releasing new policies), enforce policy across tenants.	Framework exists but lacks integration points (no controller/UI for approvals).
Data Access Controls	Multi-hospital patient queries (SuperAdminDashboardController fetches all)
. Otherwise tenant-scoped by default.	Cross-tenant PHI queries (with consent), patient record linking, consent management at enterprise level.	No global consent management UI; only consent per-patient API exists.
Billing/Finance	Some endpoints exist (permission config for invoices, billing specialists)
, but no explicit Super Admin billing UI.	Manage fee schedules, enterprise billing reports, global insurance rules.	Out-of-scope; likely handled externally or not implemented.
Reporting & Analytics	Basic dashboard stats (counts of users, patients)
. No advanced reports.	Enterprise reports (utilization, compliance, outcomes) across tenants, with filtering and exports.	Only high-level counts and recent events. No reporting engine.
Backups & DR	None in app code (infrastructure concern).	Configure and monitor backups, failover, disaster recovery drills.	Not addressed in application (expected via ops).
Integrations (HL7 etc.)	OrganizationPlatformBootstrapService applies default integrations on new org
. No UI.	Manage interface configurations (lab, radiology, pharmacy), data mappings, API credentials per hospital/tenant.	Bootstrap only; no admin UI for integrations, no feature flags.
Feature Flags	None.	Toggle features per tenant (e.g. enable new module gradually).	Missing (no feature-flag framework implemented).
Monitoring/Alerts	None.	System-wide monitoring dashboards (uptime, log analysis), security alerts on suspicious activity.	Missing; needed for proactive compliance and security (HIPAA).

Sources: The table compares repository content (cited above) to healthcare SaaS best-practices and HIPAA/NIST guidance. For example, multi-tenant security guidance stresses encryption and SSO/MFA
; HIPAA mandates extensive audit logs (retain ≥6 years)
; and least-privilege RBAC is recommended
.

Gaps & Missing Super Admin Capabilities
Below are key missing/incomplete capabilities. For each, we explain why it’s needed (often citing HIPAA or SaaS best-practices), how to implement (files to modify/create, DB changes, security), and testing needs. Items not truly needed for Super Admin are marked “(Not Needed)” with reasoning.

Centralized Audit Logging & Retention (Compliance): Required by HIPAA. The system currently shows recent audit events
, but offers no log management or retention. HIPAA requires logging all PHI access and retaining logs ≥6 years
.

Implementation: Integrate a robust audit-log system (e.g. log all sensitive actions to a write-once datastore or centralized SIEM
).
Files: Add a logging aspect/interceptor in Spring Security to capture user actions (login, data access, role changes) into a audit_logs table (create via Liquibase migration). Extend SuperAdminDashboardService to query this table (for summary and for a new “full audit” API).
DB: Create audit_logs (schema, action type, timestamp, user, resource, etc).
Security: Ensure logs are append-only. Encrypt log storage (AES-256) and enforce that only read-only queries allowed from admin UI
.
Testing: Unit tests for logging logic. Integration test to verify log entries are written on actions (could use an in-memory DB).
Why: Without it, no proof of compliance. Auditors would flag lack of retained audit trail
.
User Self-Service & SSO (Identity Management): Expected by enterprise IT. No SSO or MFA is implemented; authentication is via username/password only. Best-practice dictates Single Sign-On (e.g. SAML or OIDC) for hospital identity integration
, plus optional MFA.

Implementation: Integrate Spring Security OAuth2/SAML (e.g. with Okta or hospital IdP).
Files: Extend SecurityConfig to configure an AuthenticationFilter for SSO. Modify HospitalUserDetailsService to trust external SSO tokens. Add endpoints or config to set up OAuth2 clients (via application properties or database).
DB: Possibly a oauth_clients table (via Liquibase). Add a MFA flag on user accounts.
Security: Ensure JWT tokens from IdP are validated. Enforce MFA (e.g. google authenticator) by wrapping login flow.
Testing: Unit tests for token validation and authority mapping. Manual/integration testing with a test IdP.
Why: Improves security and user experience (less password churn)
. Hospitals often mandate SSO; missing it is a gap.
Global Role/Permission Management UI: Currently roles are hardcoded and cannot be changed at runtime. A true Super Admin should configure RBAC policies.

Implementation:
Files: Create RoleService and RoleController for CRUD on roles and permissions (permissions as fine-grained strings or enums). Possibly use Permission entities. Add UI pages (Angular) to define roles and assign permission sets.
DB: Create tables roles, permissions, role_permissions, user_roles_global (for global assignments). Use Liquibase to add them.
Testing: Unit tests for role service (creation, lookup). Integration test for auth with dynamic roles.
Why: Hardcoded roles limit flexibility. New regulatory or organizational roles need to be created without redeploy.
Feature Flag / Module Toggle System: No feature flags exist. For controlled rollouts, a Super Admin should toggle features per tenant (e.g. enable a new module only for testing hospitals).

Implementation: Use a feature-flag library (e.g. LaunchDarkly or an in-house table).
Files: Create FeatureFlag entity and service. On startup or via config, set defaults. Add an admin UI (portal page) to set flags globally or per org.
DB: Table feature_flags(name, enabled, org_id|null). Liquibase migrations needed.
Testing: Unit tests to verify feature checks. QA to ensure toggling works.
Why: Necessary for controlled deployments and A/B testing. (Without it, cannot easily enable/disable parts of the app.)
Global Settings & Configurations: Beyond feature flags, there’s no UI for global settings (e.g. default time zone, password policy strength, integrations).

Implementation:
Files: Implement a GlobalConfigService with key-value store (database-backed). Create GlobalConfigController APIs for reading/writing settings. Integrate them in the portal as a “Settings” page for Super Admin.
DB: global_settings table. Liquibase for schema.
Testing: CRUD operations, security tests.
Why: Many system-wide parameters (e.g. compliance contact info, SSO endpoints, integration credentials) need admin control.
Audit Policy Approvals Workflow: The code has SecurityPolicyGovernanceService to create baselines and list approvals
, but no controller/UI to submit/approve policy changes.

Implementation: Expose endpoints for Super Admin to view/approve pending policy changes.
Files: Add SecurityPolicyGovernanceController with routes like GET /super-admin/security/policies/baseline, /pending-approvals, /approve/{id}, /create-baseline.
DB: Baseline and approval tables already exist
. Ensure Liquibase migrations include them.
Security: Only SUPER_ADMIN can use.
Testing: Service tests already partly exist; add controller tests.
Why: Ensures any change to global security policy is tracked and approved, as required by compliance standards (some healthcare orgs use ISO-like processes for policies).
Audit Alerting/Monitoring: The system only passively logs. There’s no alerting on suspicious events (e.g. multiple failed logins, data exports).

Implementation: Integrate a monitoring tool (e.g. trigger email/Slack alerts).
Files: In the audit_logs insertion logic, check patterns and send alerts (or export logs to SIEM). Possibly reuse Spring events to notify listeners.
DB: May need an alerts table.
Testing: Simulation of suspicious actions triggers alerts.
Why: Early detection of breaches is a best-practice; HIPAA expects security event monitoring
.
Data Encryption: The code has no explicit data-at-rest encryption. Sensitive fields (PHI) in the database should be encrypted.

Implementation: Enable database encryption (at infrastructure level) and consider application-level encryption for ePHI columns (via JPA attribute converters).
Files: Add encryption converters for fields like SSN, addresses, lab results. Set @Convert on entities.
Config: Ensure TLS is enforced (already done in SecurityConfig via Csrf etc). Possibly configure HTTPS and secure cookies.
Testing: Verify encryption via integration tests.
Why: Required by HIPAA (e.g. NIST 800-66 suggests AES-256)
.
SSO Backup / Environment Keys: The app lacks any mechanism to manage environment-specific settings (like keys, certs).

(Not Needed) – This is an operational concern outside app scope (handled by DevOps). The Super Admin UI typically wouldn’t manage deployment keys.
Environment Management (Dev/Test/Prod): No support – out of scope.

(Not Needed) – Typically managed by infrastructure/DevOps.
Tenant Onboarding Automation: Creating a new tenant (Org) requires using the API or DB. There’s no smooth onboarding wizard.

Implementation: Develop a setup wizard/UI that calls /super-admin/organizations. Automate assigning default roles and users.
Files: Add an Angular “New Organization” form/page. Reuse existing APIs.
Testing: End-to-end tests of org creation.
Why: Improves usability; currently it’s only an API endpoint.
Data Migration Tools: The system has no export/import data utilities (e.g. migrating patients or settings between tenants).

Implementation: Build CLI or UI to import/export key data (using Liquibase or app services).
Files: Possibly a Spring Shell command or REST endpoints for bulk data operations.
DB: Use existing tables and export via ETL.
Testing: Test data round-trip migrations.
Why: Necessary for enterprise transitions (e.g. migrating legacy data into a new tenant).
Complexity: High, can be deferred after core features.
Billing Integration: There is minimal billing support (security config mention) but no Super Admin control of billing operations.

Implementation: If billing is needed, build billing admin APIs and UIs (invoice approvals, price list, etc).
Why: Only if the hospital uses the system for billing; otherwise, integrated with external system.
Assessment: Likely “Not Needed” for the core Super Admin feature set of an EHR-focused system. Epic’s revenue cycle might be separate.
MVP Breakdown and Task Prioritization
We prioritize features that enable basic admin control and compliance first, then advanced features. Below is a candidate MVP roadmap:

MVP1 (Core Admin+Compliance):

Implement centralized audit logging and retention (Task 1) – Critical for compliance
.
Expose Security Policy Governance endpoints (Task 6) – enables policy management.
Build missing controllers for existing services (e.g. for rule/governance if needed).
Provide a simple Admin UI for user import and organization creation (reuse existing APIs) – closes the usability gap.
Deliverables: audit_logs table (Liquibase), AuditLoggingInterceptor, “Audit Log Viewer” in UI (or API). Updated Admin portal pages for user/org. Basic encryption (DB TLS assumed).
Complexity: Medium (logging and retention is work, but architecture known; UI is straightforward form/pages).
MVP2 (Security Hardening & Identity):

Add SSO/MFA integration (Task 2) – implements enterprise auth.
Implement global role/permission management (Task 3).
Add feature-flag framework (Task 4).
Global settings page (Task 5).
Deliverables: OAuth2/SAML configuration, Role CRUD APIs/UI, FeatureFlag entity and toggle UI, GlobalSettings entity/UI.
Complexity: High (SSO integration can be complex, role model design, UI).
MVP3 (Monitoring & Advanced Ops):

Alerts & monitoring (Task 7).
Tenant onboarding wizard (Task 11).
Data migration CLI (Task 12, maybe partial).
Deliverables: Alerting rules (e.g. automated email), multi-tenant wizard UI, basic data import/export scripts.
Complexity: Medium.
Unnecessary Features (for core EHR Super Admin):

Detailed “cleaning staff” or non-clinical role management beyond standard RBAC (the system can handle any role name, so these are not extra features).
Environment deployment tooling (purely infra).
Standalone billing system (would duplicate separate billing modules if any).
Below is a Gantt timeline outline (quarters from Q2 2026 onward):

2026-04-01
2026-05-01
2026-06-01
2026-07-01
2026-08-01
2026-09-01
2026-10-01
2026-11-01
Audit Logging & Retention
Policy Governance API
Admin UI for Users/Orgs
SSO/MFA Integration
RBAC Role Management
Feature Flags System
Global Settings UI/API
Alerting & Monitoring
Tenant Onboarding Wizard
Data Migration Tools
MVP1: Core Admin & Compliance
MVP2: Security & Identity
MVP3: Monitoring & Ops
Super Admin MVP Timeline


Show code
Migration/Implementation Plan & Checklist
Schema Migrations (Liquibase):

audit_logs table (id, timestamp, user, action, details, etc).
roles, permissions, role_permissions, user_global_roles (for dynamic RBAC).
feature_flags table.
global_settings table.
Columns for SSO clients or tokens if needed.
Service Layer:

AuditLogService: intercept user actions, persist to audit_logs.
RoleService/PermissionService: CRUD roles & assign permissions.
FeatureFlagService: Evaluate flag status per org.
GlobalConfigService: get/set key-values.
Extend existing services: integrate audit logging, respect feature flags in business logic.
Controllers:

New AuditController (for Super Admin to query logs).
SecurityPolicyGovernanceController (see Task 6).
RoleController, FeatureFlagController, SettingsController.
Enhance SuperAdminOrganizationController to include additional settings (if multi-tenancy logic needed).
Modify existing controllers to respect new features (e.g. feature flag checks).
UI (Angular):

Add Super Admin sections/pages: e.g. User Management (CSV import, list of users), Org Management (wizard to add org/hospital), Settings (features, flags, global configs), Audit Logs (table). Can use Angular components/forms.
Leverage DashboardService to populate new endpoints (e.g. /api/super-admin/audit etc).
Ensure role-context allows Super Admin to see these sections.
API Contracts:

Define JSON schemas for new endpoints. Document via OpenAPI annotations (use existing style with @Operation).
Example: GET /super-admin/audit-logs?limit=... returns paged entries. GET/POST /super-admin/roles, etc.
Ensure consistent envelope (ApiResponseWrapper if needed) or direct bodies as per existing controllers.
Security:

All new admin endpoints annotated @PreAuthorize("hasRole('SUPER_ADMIN')").
Ensure data isolation logic still enforced (e.g. any query by SuperAdmin should bypass tenant filter, but normal users must be filtered by their hospital). Add checks in service layer.
Validate CSV imports thoroughly (no injection, proper format).
Input validation (@Valid) on all new DTOs.
Liquibase & Data Migration:

Write changeSets for new tables/columns. Include rollback/clear scripts.
Prepare scripts to migrate any existing config (if e.g. moving from file-based to DB-based settings).
Provide one-time data migration for any needed baseline (e.g. insert default flags feature_X = false).
Testing:

Unit tests: For each new service and controller (cover all code paths and security checks).
Integration tests: SecurityConfig mapping, full flows (e.g. login with SSO, role creation, feature toggling effect).
Performance/Load tests: Audit logging can be high-volume; ensure DB indexes on timestamp, etc.
Manual QA: Especially for SSO integration and multi-tenant behavior.
Each task above should be documented as a deliverable in the project plan, with assigned developer(s) and estimated story points/time. Minimal Viable scope focuses on core admin and compliance (MVP1) – these deliver immediate business value (org/user control + HIPAA compliance).

Sources: Epic/healthcare requirements are drawn from HIPAA/NIST guidelines and healthcare SaaS best-practices
, and the existing DevFaso/hms code described above
. All proposed file paths and code references correspond to that repo.