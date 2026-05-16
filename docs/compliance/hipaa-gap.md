# HIPAA-equivalent posture — gap analysis

Roadmap row 38 — `v2.0 / Compliance / HIPAA-equivalent posture`.

This document inventories HMS's current control posture against the
**HIPAA Security Rule (45 CFR §164.302–.318)** and the
**HIPAA Privacy Rule (45 CFR §164.500–.534)**, grounded in a code-level
survey: every claim cites a real file path in this repository.

HMS does not operate inside the United States and therefore has no
direct statutory exposure to HHS. The roadmap goal is **"HIPAA-equivalent
posture for international customers"** — pilot deployments in
Anglophone West/East Africa, Côte d'Ivoire, and the GCC will close
sales only with a credible HIPAA-style posture even when the local
regulator is national (Burkina Faso DPA, Côte d'Ivoire's ARTCI, etc.)
or sectoral (Saudi NHIE, UAE DHA). This document is the bridge between
those requests and what HMS actually does today.

## Companion artefacts

- [`hipaa-controls.csv`](./hipaa-controls.csv) — machine-readable matrix
  (one row per control point: ref, criterion, status, owner, priority,
  effort, evidence/action, target_close_date). Drives the remediation
  backlog at the foot of this document.
- [`hipaa-baa-template.md`](./hipaa-baa-template.md) — Business Associate
  Agreement template, redacted-customer-name placeholder.
- [`phi-inventory.md`](./phi-inventory.md) — full inventory of
  Protected Health Information fields, their persistence location,
  encryption status, and downstream flow.

## SOC 2 overlap

Approximately **40 % of the P0 remediation items overlap with the SOC 2
Type I backlog** (row 37). PHI inventory, encryption key rotation,
access-review cadence, and incident-response procedures are scoped
once in the SOC 2 work and re-cited here. This document calls out the
overlap inline so a single remediation PR closes both backlogs.

---

## Scorecard

| Safeguard family                          | Present | Partial | Gap | Total | Weighted % |
| ----------------------------------------- | ------- | ------- | --- | ----- | ---------- |
| §164.308 Administrative safeguards        | 4       | 6       | 4   | 14    | 50 %       |
| §164.310 Physical safeguards              | 0       | 2       | 2   | 4     | 25 %       |
| §164.312 Technical safeguards             | 7       | 4       | 1   | 12    | 75 %       |
| §164.314 Organizational requirements (BAA)| 0       | 0       | 3   | 3     | 0 %        |
| §164.316 Policies + procedures            | 1       | 2       | 2   | 5     | 40 %       |
| Privacy Rule — minimum necessary          | 4       | 1       | 1   | 6     | 75 %       |
| Privacy Rule — individual rights          | 3       | 2       | 2   | 7     | 57 %       |
| **Total**                                 | **19**  | **17**  | **15** | **51** | **54 %** |

**Headline:** the technical-safeguards axis (§164.312) is at **75 %
weighted** — the engineering work is largely done. The remaining
shortfall is concentrated in:

1. Organizational + BAA paperwork (no template + no executed agreements yet).
2. Physical safeguards documentation (Railway / managed Postgres is
   technically compliant but we haven't captured the provider's
   attestation in our control file).
3. Policy + procedure formalisation (we have practices but few
   written policies — the SOC 2 backlog already captures this).

A focused 90-day remediation push closes the P0 items in time for the
first international-customer BAA signature; another 6 months absorbs
P1 / P2.

---

## §164.308 — Administrative safeguards

### §164.308(a)(1)(i) — Security Management Process

**§164.308(a)(1)(ii)(A) Risk Analysis** — `Partial`. The SOC 2 gap
analysis (`docs/compliance/soc2-gap.md` once row 37 lands) inventories
the threat surface, but a HIPAA-specific risk register naming each PHI
field and the threats against it does not yet exist.

**Evidence:** [`docs/security-hardening-plan.md`](../security-hardening-plan.md)
captures the rolling security work; rate limiting at
[`hospital-core/src/main/java/com/example/hms/security/auth/`](../../hospital-core/src/main/java/com/example/hms/security/auth/);
audit log at
[`hospital-core/src/main/java/com/example/hms/model/AuditEventLog.java`](../../hospital-core/src/main/java/com/example/hms/model/AuditEventLog.java).

**Gap:** HIPAA-specific risk register (P0 — overlaps with SOC 2 CC3).

**§164.308(a)(1)(ii)(B) Risk Management** — `Partial`. We have a
documented hardening plan and security task tracker; a formal control
remediation tracker indexed against this gap document is what's
missing.

**Action:** stand up the remediation tracker (P0). Mirrors SOC 2 work.

**§164.308(a)(1)(ii)(C) Sanction Policy** — `Gap`. No documented
disciplinary policy for workforce members who violate HMS security
procedures.

**Action:** draft sanction policy (P1, HR-owned, doc-only).

**§164.308(a)(1)(ii)(D) Information System Activity Review** — `Present`.

**Evidence:**
[`hospital-core/src/main/java/com/example/hms/controller/SuperAdminAuditSearchController.java`](../../hospital-core/src/main/java/com/example/hms/controller/SuperAdminAuditSearchController.java),
[`hospital-core/src/main/java/com/example/hms/enums/AuditEventType.java`](../../hospital-core/src/main/java/com/example/hms/enums/AuditEventType.java)
covers 80+ event types including `LOGIN`, `LOGIN_FAILURE`,
`PATIENT_ACCESS`, `BREAK_GLASS_ACCESS`, `PERMISSION_GRANTED`,
`ROLE_ASSIGNED`. Splunk HEC export landed in PR #276
([`hospital-core/src/main/java/com/example/hms/config/observability/SplunkLoggingProperties.java`](../../hospital-core/src/main/java/com/example/hms/config/observability/SplunkLoggingProperties.java)).

### §164.308(a)(2) — Assigned Security Responsibility

`Gap`. No named Security Officer / Privacy Officer.

**Action:** designate roles in the BAA template + internal policy
(P0). Doc-only.

### §164.308(a)(3) — Workforce Security

**§164.308(a)(3)(ii)(A) Authorization / Supervision** — `Present`.
Role-based access via `Role` + `UserRoleHospitalAssignment`
(`hospital-core/src/main/java/com/example/hms/model/UserRoleHospitalAssignment.java`),
with super-admin assignment confirmation flows
(`hospital-core/src/main/java/com/example/hms/service/UserRoleHospitalAssignmentServiceImpl.java`).

**§164.308(a)(3)(ii)(B) Workforce Clearance** — `Gap`. No formal
background-check / clearance procedure documented; relies on
customer-side onboarding.

**Action:** document expectation in BAA (P1).

**§164.308(a)(3)(ii)(C) Termination Procedures** — `Partial`.
Keycloak admin REST supports user disable; the procedure is not
yet in a runbook.

**Action:** add to
[`docs/runbooks/keycloak-admin-recovery-2026-05-09.md`](../runbooks/keycloak-admin-recovery-2026-05-09.md)
or split into its own (P1).

### §164.308(a)(4) — Information Access Management

**§164.308(a)(4)(ii)(A) Isolating Health Care Clearinghouse Functions** —
`N/A` (HMS is not a clearinghouse).

**§164.308(a)(4)(ii)(B) Access Authorization** — `Present`.
`PermissionCatalog.java` enumerates the full permission surface;
`@PreAuthorize` / `@PostAuthorize` annotations across controllers
enforce least-privilege at the boundary.

**§164.308(a)(4)(ii)(C) Access Establishment + Modification** —
`Partial`. The workflow exists (super-admin assigns + confirms
roles); a periodic access-review cadence is not formalised.

**Action:** define quarterly access-review cadence + log review
artefacts in `docs/compliance/access-review-log.md` (P0 — overlaps
with SOC 2 CC6.3).

### §164.308(a)(5) — Security Awareness + Training

**§164.308(a)(5)(ii)(A–D)** — `Gap` (all four sub-items).

**Action:** define training program: onboarding module covering
PHI handling, password hygiene, phishing recognition; annual
refresher (P1, doc-only initially, training-LMS later).

### §164.308(a)(6) — Security Incident Procedures

**§164.308(a)(6)(ii) Response + Reporting** — `Partial`. We have a DR
runbook (`docs/runbooks/disaster-recovery.md`) and a cutover playbook;
a HIPAA-style incident-response runbook with breach-notification
thresholds + customer-comms templates is missing.

**Action:** author `docs/runbooks/hipaa-incident-response.md`
including a 60-day breach-notification deadline placeholder, escalation
tree, customer notification template, regulator notification template
per jurisdiction (P0).

### §164.308(a)(7) — Contingency Plan

**§164.308(a)(7)(ii)(A) Data Backup Plan** — `Present`. Railway
PostgreSQL snapshots + point-in-time-recovery documented at
[`docs/runbooks/disaster-recovery.md`](../runbooks/disaster-recovery.md).

**§164.308(a)(7)(ii)(B) Disaster Recovery Plan** — `Present`. Same
runbook.

**§164.308(a)(7)(ii)(C) Emergency Mode Operation Plan** — `Partial`.
The DR runbook covers recovery; "operating during a degraded mode"
(read-only, fallback storage, manual fallback for the patient
tracker board) is implicit but not documented.

**Action:** add emergency-mode section to DR runbook (P1).

**§164.308(a)(7)(ii)(D) Testing + Revision** — `Partial`. DR drill
was executed (per v1.0 exit criterion #3); no scheduled cadence
recorded for HIPAA purposes.

**Action:** quarterly DR-drill schedule + per-drill log (P1).

**§164.308(a)(7)(ii)(E) Applications + Data Criticality Analysis** —
`Gap`. No formal classification of which features / data are most
critical to restore first.

**Action:** add criticality column to the
[`phi-inventory.md`](./phi-inventory.md) tables (P2).

### §164.308(a)(8) — Evaluation

`Partial`. The SOC 2 gap analysis (when row 37 lands) serves as
the periodic technical evaluation; HIPAA-specific evaluation cadence
(annual) is not yet documented.

**Action:** define annual HIPAA self-evaluation cadence pointing at
this file as the canonical artefact (P1).

### §164.308(b) — Business Associate Contracts

`Gap`. No BAA template, no executed BAAs.

**Action:** sign-off + publish [`hipaa-baa-template.md`](./hipaa-baa-template.md)
(P0, this PR provides the draft template).

---

## §164.310 — Physical safeguards

HMS does not operate its own data centres; all infrastructure runs on
**Railway managed Postgres** in production and **Railway compute**
for the application. Physical safeguards are inherited from the
provider; this document captures the inheritance trail.

### §164.310(a)(1) — Facility Access Controls

**§164.310(a)(2)(i) Contingency Operations** — `Partial`.
[`docs/runbooks/disaster-recovery.md`](../runbooks/disaster-recovery.md)
covers operations during data-centre outage but does not name the
Railway data-centre or attest to their own physical controls.

**§164.310(a)(2)(ii) Facility Security Plan** — `Gap`. Need Railway
SOC 2 / ISO 27001 attestation captured in our control file.

**Action:** request and archive Railway's latest SOC 2 Type II report;
reference its physical-security section here (P0).

**§164.310(a)(2)(iii) Access Control + Validation Procedures** — `Gap`.

**Action:** as above — inherited from Railway attestation.

**§164.310(a)(2)(iv) Maintenance Records** — `Partial`. Railway
captures their own; we have no maintenance log of our own.

### §164.310(b) — Workstation Use

`N/A` for the foundation pass — HMS does not control end-user
workstations. Documented in the BAA template that customers are
responsible for workstation policies.

### §164.310(c) — Workstation Security

`N/A` — same as above.

### §164.310(d) — Device + Media Controls

**§164.310(d)(2)(i) Disposal** — `Partial`. Database tombstoning is
implemented (soft delete on key entities); media destruction is
inherited from Railway.

**§164.310(d)(2)(ii) Media Re-use** — `N/A` (cloud).

**§164.310(d)(2)(iii) Accountability** — `Gap`. No formal media
inventory.

**Action:** none planned — inherits from Railway.

**§164.310(d)(2)(iv) Data Backup + Storage** — `Present`. Railway
snapshots + PITR.

---

## §164.312 — Technical safeguards (the strongest axis)

### §164.312(a)(1) — Access Control

**§164.312(a)(2)(i) Unique User Identification** — `Present`.
`Users.id` is a UUID; usernames are unique per Keycloak realm.
[`hospital-core/src/main/java/com/example/hms/model/Users.java`](../../hospital-core/src/main/java/com/example/hms/model/Users.java).

**§164.312(a)(2)(ii) Emergency Access Procedure** — `Present`.
Break-glass access pattern with `BREAK_GLASS_ACCESS` audit event in
`AuditEventType.java`; super-admin recovery runbook at
[`docs/runbooks/keycloak-admin-recovery-2026-05-09.md`](../runbooks/keycloak-admin-recovery-2026-05-09.md).

**§164.312(a)(2)(iii) Automatic Logoff** — `Partial`. Idle-session
timeout (15 min default) shipped in v1.0
([`hospital-core/src/main/java/com/example/hms/security/auth/`](../../hospital-core/src/main/java/com/example/hms/security/auth/)).
JWT short-lived access tokens (15 min) + refresh-cookie HttpOnly +
SameSite=Strict
(`app.auth.refresh-cookie.*` in `application.properties`).

**Gap:** absolute session limit (e.g., 12 h regardless of activity)
not enforced.

**Action:** add absolute-session-max-age check (P1).

**§164.312(a)(2)(iv) Encryption + Decryption** — `Partial`. AES-256-GCM
field-level encryption is operational via
[`hospital-core/src/main/java/com/example/hms/security/EncryptedStringConverter.java`](../../hospital-core/src/main/java/com/example/hms/security/EncryptedStringConverter.java),
key supplied via `APP_ENCRYPTION_KEY` (Base64 of 32 bytes). Wire format
includes a version prefix (`gcm1:`) so algorithm rotation is possible
without a destructive migration. Coverage today is limited to the
narrative-PHI columns (10 columns on `Patient`, 3 on `Prescription`,
1 on `Dispense`; full list in
[`phi-inventory.md`](./phi-inventory.md)) — the §164.514(b)(2)(i)
identifier columns (names, phone, email, city/state/zip, MRN aliases,
insurance policy/group numbers) are plaintext today and rely on
tenant scoping + platform-level volume encryption.

**Action:** extend `@Convert(EncryptedStringConverter)` to the
identifier columns (P0 — see remediation backlog item 10).

### §164.312(b) — Audit Controls

`Present`.
[`hospital-core/src/main/java/com/example/hms/model/AuditEventLog.java`](../../hospital-core/src/main/java/com/example/hms/model/AuditEventLog.java)
+ 80+ event types in
[`hospital-core/src/main/java/com/example/hms/enums/AuditEventType.java`](../../hospital-core/src/main/java/com/example/hms/enums/AuditEventType.java),
exported to Splunk HEC and Grafana via OpenTelemetry. Cross-tenant
read attempts are explicitly audited
([`hospital-core/src/main/java/com/example/hms/security/audit/CrossTenantReadAudit.java`](../../hospital-core/src/main/java/com/example/hms/security/audit/CrossTenantReadAudit.java)).

### §164.312(c) — Integrity

**§164.312(c)(2) Mechanism to Authenticate Electronic PHI** —
`Present`. Database constraints + foreign keys + Liquibase
schema versioning give structural integrity; JPA `@Version` optimistic
locking on `Encounter`, `Admission`, and other clinical entities
prevents lost updates; cryptographic integrity is not separately
implemented (would require row-level signing — not standard practice).

### §164.312(d) — Person or Entity Authentication

`Present`. Keycloak OIDC + per-role MFA enforcement
(`app.mfa.required-roles` includes `ROLE_SUPER_ADMIN`,
`ROLE_HOSPITAL_ADMIN`, `ROLE_DOCTOR`, `ROLE_PHARMACIST`,
`ROLE_FINANCE`). RS256 signing (Phase 6 done — see roadmap
"Where the project is today" / Auth).

### §164.312(e) — Transmission Security

**§164.312(e)(2)(i) Integrity Controls** — `Present`. All public
endpoints require HTTPS (Railway-terminated TLS); inter-service
calls (FHIR R4, CDS Hooks, HL7 MLLP, DHIS2 ADX) carry their own
authentication + integrity protections.

**§164.312(e)(2)(ii) Encryption** — `Present`. TLS 1.2+ for all
external traffic. The one open item is inbound HL7 v2 MLLP, which
runs over plain TCP per the protocol — the deployment guide
recommends fronting it with an mTLS terminator (see
[`MllpProperties.java`](../../hospital-core/src/main/java/com/example/hms/hl7/mllp/MllpProperties.java)
header comment).

**Gap:** MLLP-over-TLS not built into the listener itself; relies on
operator-side termination. Acceptable for HIPAA inheritance but worth
calling out.

**Action:** document recommended termination topology in
`docs/runbooks/hl7-mllp-tls-termination.md` (P2, doc-only).

---

## §164.314 — Organizational requirements

### §164.314(a)(1) — Business Associate Contracts

**`Gap`** — three control points all marked Gap.

**Action:** publish + sign [`hipaa-baa-template.md`](./hipaa-baa-template.md)
(P0, this PR provides the draft).

### §164.314(b) — Requirements for Group Health Plans

`N/A` — HMS is not a group health plan.

---

## §164.316 — Policies, procedures, documentation

### §164.316(a) — Policies + Procedures

`Partial`. We have runbooks (DR, Keycloak, env-sync), README, security
hardening plan. We do not have a single index of HIPAA-relevant
policies.

**Action:** add `docs/compliance/policies-index.md` (P0).

### §164.316(b)(1) — Documentation

`Partial`. Policies aren't formalised (see above).

### §164.316(b)(2)(i) — Time Limit (6-year retention)

`Gap`. Audit log retention is not configured to satisfy a 6-year
window; current Splunk index retention is unconfigured.

**Action:** set Splunk index retention on `hms_prod` to ≥ 6 years; set
DB audit log retention policy with archival to cold storage (P0).

### §164.316(b)(2)(ii) — Availability

`Present` for ops staff (Splunk dashboards + Grafana);
**Gap** for end-user transparency (Privacy Rule §164.524 right to
access — see below).

### §164.316(b)(2)(iii) — Updates

`Partial`. We update policies as runbooks change; no annual review
cadence documented.

**Action:** add quarterly review cadence to the policies index (P1).

---

## Privacy Rule — minimum necessary (§164.502(b))

`Present`. The role-permission matrix + hospital-context scoping
limits each request to the smallest data set the role needs. The
[`TenantAwareJpaRepository`](../../hospital-core/src/main/java/com/example/hms/repository/) +
hospital-scoped queries throughout the codebase enforce per-tenant
isolation. Patient-merging operations require explicit super-admin
flows.

**Gap:** Minimum-necessary policy is not written down for non-engineers
to read.

**Action:** add to the policies index (P1).

---

## Privacy Rule — individual rights

### §164.524 — Right of access

`Present`. Patient portal exposes encounters, prescriptions, lab
results, allergies, problems through
[`hospital-core/src/main/java/com/example/hms/patient/`](../../hospital-core/src/main/java/com/example/hms/patient/)
+ mobile apps.

### §164.526 — Right to amend

`Partial`. Patients can request profile updates; clinical record
amendments require a clinician-mediated workflow which is implemented
but not documented as a HIPAA-compliant amendment procedure.

**Action:** add `docs/runbooks/patient-record-amendment.md` (P1).

### §164.528 — Right to an accounting of disclosures

`Partial`. The audit log captures `RECORD_SHARE`, `DATA_EXPORT`,
`CONSENT_GRANTED`, `CONSENT_REVOKED` — sufficient to reconstruct a
disclosure list. A patient-facing "show me my disclosures" endpoint
is not yet built.

**Action:** add `GET /api/patient/me/disclosures` endpoint and a
portal screen (P2).

### §164.508 — Authorization for use / disclosure

`Present`. `PatientConsent` + `ConsentType` enum
([`hospital-core/src/main/java/com/example/hms/enums/ConsentType.java`](../../hospital-core/src/main/java/com/example/hms/enums/ConsentType.java));
consent flows enforced at access-time for marked records.

### §164.510 — Uses + disclosures requiring opportunity to agree/object

`Gap`. The directory / facility-directory model is not separated from
the general patient model; a separate opt-in is not collected.

**Action:** scope decision pending — many international deployments
do not have an analogous "directory" concept; deferred to v2.1
unless a specific customer asks (P2).

### §164.512 — Uses + disclosures not requiring authorization

`Partial`. Documented in policy expectations only; no code-level
allowlist of the §164.512 carve-outs (treatment, payment, healthcare
operations).

---

## Remediation backlog

**P0 — must close before first international BAA signature** (10
items, target close **2026-09-30**):

1. HIPAA-specific risk register
2. Security Officer + Privacy Officer designation
3. Periodic access-review cadence (quarterly) + log
4. Incident-response runbook with breach-notification deadlines
5. BAA template publication + first customer signature
6. Railway SOC 2 Type II archival + attestation reference in this doc
7. Policies index (`docs/compliance/policies-index.md`)
8. Audit log retention configuration (≥ 6 years on Splunk + DB)
9. PHI inventory (`docs/compliance/phi-inventory.md`) — this PR
10. Extend `EncryptedStringConverter` to the §164.514(b)(2)(i)
    identifier columns currently in plaintext (names, phone, email,
    city/state/zip, MRN aliases, insurance policy/group numbers — see
    [`phi-inventory.md`](./phi-inventory.md))

**P1 — close before observation window** (10 items, target close
**2026-12-31**):

1. Sanction policy (doc-only)
2. Workforce clearance expectation in BAA
3. Termination-procedures runbook
4. Security-awareness training program
5. Emergency-mode operation plan (DR runbook addendum)
6. DR-drill quarterly schedule + per-drill log
7. Annual HIPAA self-evaluation cadence
8. Absolute session max-age enforcement (engineering)
9. Patient-record-amendment runbook
10. Encryption key-rotation + crypto-shred runbook
    (`docs/runbooks/key-rotation.md`, referenced from BAA §4.4)

**P2 — incremental** (7 items, no specific target):

1. Applications + data criticality analysis (PHI inventory addendum)
2. MLLP-over-TLS termination runbook
3. Patient disclosures endpoint + portal screen
4. Directory opt-in model (if a customer asks)
5. §164.512 allowlist code surface
6. Workstation-policy template for customer redistribution
7. Media-inventory baseline (mostly inherited from Railway)

## Critical path

```
2026-05-16 — this gap analysis published (roadmap row 38 → "started")
2026-06-15 — P0 items 5–9 drafted (BAA, policies index, retention, PHI inventory expanded)
2026-07-31 — P0 items 1–4 drafted (risk register, designation, access review, IR runbook)
2026-08-31 — P0 item 10 + first external review
2026-09-30 — P0 complete, BAA template ready for legal signoff
2026-10-15 — first international customer BAA signed (target)
2026-12-31 — P1 complete
2027+      — P2 incremental, alongside SOC 2 Type II observation window
```

This sequence is **calibrated to the SOC 2 critical path** in row 37:
P0 closes by 2026-09-30 (one month after the SOC 2 P0 deadline of
2026-08-15), so a single HR / compliance push satisfies both backlogs
in succession.

---

Last updated: 2026-05-16. Update on every status change in
`hipaa-controls.csv`.
