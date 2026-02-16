# 🏥 HMS API - HTTP Test Collection

> Comprehensive endpoint testing organized by domain workflow.
> Uses [VS Code REST Client](https://marketplace.visualstudio.com/items?itemName=humao.rest-client) or IntelliJ HTTP Client.

## 📁 Folder Structure

```
http/
├── http-client.env.json        # Environment variables (local, dev, uat)
├── README.md                   # This file
├── 00-health.http              # Health checks & public endpoints
├── 01-auth.http                # Authentication & credential lifecycle
├── 02-organizations.http       # Organization management
├── 03-hospitals.http           # Hospital CRUD
├── 04-departments.http         # Department management
├── 05-roles-permissions.http   # Roles, permissions, permission matrix
├── 06-users.http               # User management
├── 07-assignments.http         # User↔Role↔Hospital assignments
├── 08-staff.http               # Staff profiles, availability, scheduling
├── 09-patients.http            # Patient CRUD, search, allergies, diagnoses
├── 10-patient-medical.http     # Medical history, immunizations, vitals, meds
├── 11-appointments.http        # Appointment lifecycle
├── 12-encounters.http          # Clinical encounters & notes
├── 13-prescriptions.http       # Prescriptions & medication history
├── 14-lab.http                 # Lab orders, results, definitions
├── 15-imaging.http             # Imaging orders & results
├── 16-procedures.http          # Procedure orders
├── 17-billing.http             # Invoices, items, payments, email
├── 18-admissions.http          # Admissions, order sets, discharge
├── 19-treatment-plans.http     # Treatment plans & follow-ups
├── 20-consultations.http       # Consultation requests & workflow
├── 21-referrals.http           # General & OB/GYN referrals
├── 22-maternal.http            # Maternal care, birth plans, prenatal, postpartum
├── 23-nurse.http               # Nurse tasks, vitals, MAR, handoffs, notes
├── 24-patient-education.http   # Education resources, progress, questions
├── 25-notifications.http       # Notifications & announcements
├── 26-chat.http                # Chat messaging
├── 27-files.http               # File uploads
├── 28-records-sharing.http     # Patient consent & record sharing
├── 29-security-policies.http   # Security policies, rules, compliance
├── 30-audit.http               # Audit logs & frontend audit
├── 31-super-admin.http         # Super admin dashboard & governance
├── 32-platform.http            # Platform registry & service catalog
├── 33-digital-signatures.http  # Digital signature & verification
├── 34-feature-flags.http       # Feature flag management
├── 35-lookup.http              # Lookup / reference endpoints
└── 36-dashboard.http           # Dashboard & me endpoints
```

## 🚀 Quick Start

### 1. Bootstrap the System (first-time setup)
```
Run requests in this order:
  00-health.http  → Verify server is running
  01-auth.http    → Bootstrap first super admin + login
  02-orgs.http    → Create organization
  03-hospitals    → Create hospital
  04-departments  → Create departments
  05-roles        → Set up roles & permissions
  06-users        → Create users (doctor, nurse, etc.)
  07-assignments  → Assign roles to users at hospitals
  08-staff        → Create staff profiles
```

### 2. Clinical Workflow
```
  09-patients.http       → Register patient
  11-appointments.http   → Book appointment
  12-encounters.http     → Create encounter
  13-prescriptions.http  → Write prescriptions
  14-lab.http            → Order & process lab tests
  15-imaging.http        → Order imaging studies
  17-billing.http        → Generate invoices
```

### 3. Set Up Environment Variables
1. Copy a token from the login response
2. Paste into `http-client.env.json` → `accessToken` field
3. Fill in IDs as you create resources (hospitalId, patientId, etc.)

## 🔑 Authentication

All requests (except public endpoints) require a JWT token:
```
Authorization: Bearer {{accessToken}}
```

After login, copy the `accessToken` from the response into your environment file.

## 📖 Conventions

- `{{variable}}` — references environment variables from `http-client.env.json`
- `###` — separates individual requests in a file
- Each file is self-contained and follows the domain's typical workflow order
- Comments explain what each request does and expected responses

## 🏗️ Server Info

| Setting | Value |
|---------|-------|
| Port | 8081 |
| Context Path | /api |
| Base URL | http://localhost:8081/api |
| Auth | JWT Bearer Token |
| Default Profile | local-h2 (in-memory DB) |
