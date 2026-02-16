# Production Database Access Policy

> **Last updated:** February 15, 2026
> **Owner:** Tiego Ouedraogo

---

## Overview

The HMS production database runs on **Railway PostgreSQL** and uses a **role-based access model** with 4 distinct database users. Every team member must use the correct role for their task.

## Role Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                  Railway PostgreSQL (prod)                    │
│                                                              │
│  postgres (superuser)                                        │
│  ├── hms_migrator   → Liquibase / CI-CD only (DDL)          │
│  ├── hms_app        → Spring Boot runtime (CRUD)            │
│  └── hms_readonly   → Developer investigation (SELECT only) │
└──────────────────────────────────────────────────────────────┘
```

| Role | Purpose | Can Do | Cannot Do | Conn Limit |
|------|---------|--------|-----------|------------|
| `postgres` | Emergency only | Everything | N/A — **never use for daily work** | Unlimited |
| `hms_migrator` | Liquibase migrations (CI/CD pipeline) | CREATE/ALTER/DROP tables, INSERT seed data | Login from developer machines | 3 |
| `hms_app` | Spring Boot application at runtime | SELECT, INSERT, UPDATE, DELETE on all tables | CREATE/DROP tables, TRUNCATE | 20 |
| `hms_readonly` | Developer browsing in IntelliJ / DBeaver | SELECT on all tables and sequences | INSERT, UPDATE, DELETE, DDL | 5 |

## For New Developers

### What You Get

When you join the team, you will receive:
- **`hms_readonly`** password — for browsing prod data in IntelliJ or DBeaver
- **Local database credentials** — for `hospital_db`, `hms_dev`, `hms_uat` on your machine

### What You Do NOT Get

- `postgres` superuser password — **only the tech lead has this**
- `hms_app` password — **only Railway env vars have this** (no human uses it)
- `hms_migrator` password — **only the CI/CD pipeline uses this**

### Setting Up IntelliJ

1. Open **Database** tool window → **+** → **Data Source** → **PostgreSQL**
2. Use these settings:

| Field | Value |
|-------|-------|
| Host | `interchange.proxy.rlwy.net` |
| Port | `53230` |
| Database | `railway` |
| User | `hms_readonly` |
| Password | *(ask tech lead)* |

3. **Important:** Check the **Read-Only** checkbox in the data source settings
4. Save and test connection

### Schemas You'll See

| Schema | Contains | Sensitivity |
|--------|----------|-------------|
| `clinical` | Patients, encounters, prescriptions, appointments | 🔴 **HIPAA / PHI** |
| `empi` | Master patient identity, aliases, merge events | 🔴 **HIPAA / PII** |
| `security` | Users, roles, permissions, MFA, passwords | 🔴 **Credentials** |
| `billing` | Invoices, payments | 🟡 **Financial** |
| `hospital` | Organizations, departments, staff | 🟡 **Operational** |
| `governance` | Security policies, baselines, rules | 🟢 **Config** |
| `lab` | Lab tests, orders, results | 🔴 **HIPAA / PHI** |
| `platform` | Organization services | 🟢 **Config** |
| `reference` | Catalogs, reference data | 🟢 **Config** |
| `support` | Audit logs, service translations | 🟡 **Operational** |
| `public` | Liquibase changelog, announcements | 🟢 **System** |

## Rules

1. **Never share the `hms_readonly` password** in Slack, email, or code
2. **Never run DML (INSERT/UPDATE/DELETE)** against prod — even if you think you can
3. **Never copy prod patient data** to your local machine or a file
4. **Report** if you see any data that looks like it shouldn't be there
5. **Password rotation**: `hms_readonly` password expires every **90 days** — the tech lead will rotate it and re-distribute

## Password Rotation Schedule

| Role | Current Expiry | Rotation Responsibility |
|------|---------------|------------------------|
| `hms_readonly` | 2026-05-16 | Tech lead rotates, re-distributes to team |
| `hms_app` | Never (env var) | Rotate when a team member leaves |
| `hms_migrator` | Never (CI/CD only) | Rotate annually or on team change |
| `postgres` | Never | **Emergency only** — stored in secure vault |

## How to Rotate `hms_readonly` Password

```sql
-- Connect as postgres (superuser)
ALTER ROLE hms_readonly PASSWORD 'NewSecurePassword2026';
ALTER ROLE hms_readonly VALID UNTIL '2026-08-16';  -- next 90 days
```

Then update the team's IntelliJ data source passwords.

## Emergency Procedures

### If You Suspect a Data Breach
1. Immediately notify the tech lead
2. Do NOT attempt to fix it yourself
3. The tech lead will:
   - Rotate all passwords
   - Check `support.audit_event_logs` for suspicious activity
   - Revoke compromised roles if needed

### If a Team Member Leaves
1. Rotate `hms_readonly` password immediately
2. Rotate `hms_app` password if they had Railway access
3. Remove their Railway account access
4. Update all team members with new `hms_readonly` password
