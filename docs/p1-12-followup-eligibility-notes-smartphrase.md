# P1 #12 follow-up — items 4, 5, 6 (Eligibility, per-section notes, SmartPhrase)

**Branch:** `feature/p1-12-followup-eligibility-notes-smartphrase`
**Date:** 2026-05-01
**Migrations on this branch:** V73, V74

This change set ships three items from the P1 #12 follow-up tail in a
single PR because the encounter-note form (item 5) is the host UI for the
SmartPhrase autocomplete (item 6), and the eligibility dialog (item 4)
hangs off the same encounter detail panel.

---

## Item 4 — Real-time eligibility / prior-auth API

### Why one PR, not the X12 270/271/278 transaction set

The original gap list (#11) called out US-style payer transactions but
flagged that they are "less relevant — most payments are out-of-pocket or
NHIS / CNAMGS card". This change set keeps the wire shape generic across
public payers via a scheme enum and a provider SPI, so a real partner
connector replaces a stub at deploy time without touching controllers, the
FE, or the database.

### Backend

- `enums/EligibilityScheme` — `NHIS_GH`, `NHIA_NG`, `CNAMGS_GA`,
  `MUTUELLE_RW`, `MUTUELLE_BF`, `GENERIC` (private payer fallback).
- `enums/EligibilityCheckType` — `COVERAGE` (X12 270/271 analogue) /
  `PRIOR_AUTH` (X12 278 analogue).
- `enums/EligibilityStatus` — `ELIGIBLE`, `NOT_ELIGIBLE`, `PENDING`,
  `UNKNOWN`, `ERROR`.
- `model/insurance/EligibilityCheck` — append-mostly persistent record of
  every call. `@Version` (BIGINT NOT NULL DEFAULT 0) protects against the
  rare race where two clinicians submit a check simultaneously, mirroring
  the V72 pattern on `general_referrals`. Optional FK to `PatientInsurance`.
- `service/integration/eligibility/EligibilityProvider` SPI —
  `supports(scheme)`, `checkCoverage(...)`, `requestPriorAuth(...)`. The
  service resolves the first bean whose `supports` returns `true` for the
  request scheme.
- `StubEligibilityProvider` — deterministic fallback that supports every
  scheme. memberId starting with `X` → `NOT_ELIGIBLE`; ending with `?` →
  `UNKNOWN`; missing → `ERROR`. Currency and prior-auth-by-default are
  modelled per scheme so the FE can render realistic results without any
  partner connectivity.
- `EligibilityService` + `EligibilityServiceImpl` — orchestrates lookups,
  emits a `PATIENT_ACCESS` audit event (status `FAILURE` when the check
  errored), persists the row.
- `EligibilityController` at `/eligibility`:
  - `POST /eligibility/check` (forces `checkType=COVERAGE`)
  - `POST /eligibility/prior-auth` (forces `checkType=PRIOR_AUTH`)
  - `GET /eligibility/{id}`
  - `GET /eligibility/patient/{patientId}` (paged history)
  - `GET /eligibility/patient/{patientId}/latest?scheme=...&type=...`
    (204 when no prior check)

`@PreAuthorize` allows `ROLE_SUPER_ADMIN`, `ROLE_HOSPITAL_ADMIN`,
`ROLE_DOCTOR`, `ROLE_NURSE`, `ROLE_MIDWIFE`, `ROLE_RECEPTIONIST` — the
receptionist needs the COVERAGE check at registration; only clinicians
typically run prior-auth.

### V73 migration

`clinical.eligibility_checks` with FKs to `clinical.patients`,
`hospital.hospitals`, `clinical.patient_insurances`, `security.users`.
Indexes:

- `idx_elig_patient` — patient timeline.
- `idx_elig_hospital` — tenant scoping.
- `idx_elig_scheme` — admin filter views.
- `idx_elig_patient_scheme_completed` — drives the
  "fresh answer for this scheme?" lookup on the encounter and checkout
  screens.

Pure DDL, additive only. Rollback comments are inline in the SQL.

### Frontend

- `services/eligibility.service.ts` — typed wrapper that returns `null`
  for the 204 No-Content "latest" lookup.
- `encounters/eligibility-check-dialog/` — modal that picks scheme +
  member id (and service code for prior-auth), renders the persisted
  result inline. Hangs off the encounter detail panel; the same dialog is
  reusable from the checkout flow.

---

## Item 5 — Per-section EncounterNote form

The backend has carried the SOAP / SOAPIE columns
(`chief_complaint`, `history_present_illness`, `review_of_systems`,
`physical_exam`, `diagnostic_results`, `data_subjective`, `data_objective`,
`data_assessment`, `data_plan`, `data_implementation`, `data_evaluation`,
`patient_instructions`, attestation flags, signature) since the encounter
workspace landed. The FE was still binding a single textarea to `summary`.

This change replaces that textarea with a fully sectioned form:

- Visit context: chief complaint, HPI, ROS, exam, diagnostic results.
- SOAP / SOAPIE template toggle — switching to SOAPIE reveals
  Implementation and Evaluation sections.
- Patient instructions block.
- Late-entry toggle with `eventOccurredAt` datetime input.
- Three attestation checkboxes (accuracy, no-DNU-abbreviations,
  spell-checked).
- Optional signer name / credentials — when present the note is signed at
  save time.

The form trims and drops empty fields before submit so a clinician filling
in only `subjective` and `plan` does not write blanks over previously
populated columns.

`EncounterNoteRequest` on the FE was extended with the per-section keys
(template, summary, chiefComplaint, HPI, ROS, exam, diagnostic results,
S/O/A/P/I/E, patient instructions, attestation flags, signature). The
existing single-textarea callers continue to work because every new field
is optional.

---

## Item 6 — SmartPhrase / dot-phrase macro library

### Backend

- `enums/SmartPhraseScope` — `GLOBAL`, `HOSPITAL`, `USER`.
- `model/SmartPhrase` — `phrase_trigger` column (Postgres reserves
  `trigger`), normalised to lowercase, validated to start with `.` and use
  alphanumerics / dash / underscore. `@Version` for concurrent edits.
- `repository/SmartPhraseRepository` — visible-to-user JPQL plus a
  trigger-prefix autocomplete query that returns macros from all three
  scopes; the service narrows by precedence.
- `SmartPhraseService` — USER > HOSPITAL > GLOBAL precedence at
  autocomplete time (a USER macro shadows a HOSPITAL macro with the same
  trigger). Search is short-circuited locally when the prefix does not
  start with `.` so no DB hit happens until the clinician actually types
  the trigger character.
- `SmartPhraseController` at `/smart-phrases`:
  - `POST /smart-phrases` (clinician — create)
  - `PUT /smart-phrases/{id}` (clinician — update)
  - `DELETE /smart-phrases/{id}` (clinician)
  - `GET /smart-phrases/{id}`
  - `GET /smart-phrases/autocomplete?prefix=...&hospitalId=...`
  - `POST /smart-phrases/{id}/usage` (fire-and-forget bump of
    `usage_count` + `last_used_at`)
  - `GET /smart-phrases/global` (admin / library view)

### V74 migration

`clinical.smart_phrases` with three partial unique indexes (one per
scope) so the same trigger can live across tiers without colliding —
required for the USER-shadows-HOSPITAL-shadows-GLOBAL behaviour.

Seeds six high-value triggers as GLOBAL macros so the FE has something to
autocomplete the moment the migration runs:

- `.normexam` — Normal physical exam (adult).
- `.normros` — Normal review of systems.
- `.htn-followup` — Hypertension follow-up plan.
- `.dm-followup` — Type-2 diabetes follow-up plan.
- `.malaria-tx` — Uncomplicated malaria treatment plan (ACT).
- `.anc-routine` — Routine antenatal visit assessment.

Idempotent seed — re-runs / replays skip rows whose trigger already
exists at GLOBAL scope.

### Frontend

- `services/smart-phrase.service.ts`.
- The dot-phrase autocomplete is built into the per-section note
  component. Typing `.x…` triggers a debounced (150 ms) call to
  `/smart-phrases/autocomplete`, the dropdown anchors under the current
  textarea, ↑/↓ navigates, Enter / Tab / click expands. The trigger token
  is replaced in-place by the macro's expansion text and the cursor is
  moved to the end of the inserted block. `recordUsage` is fired
  best-effort and never blocks the clinical flow.

---

## Tests

- `StubEligibilityProviderTest` — 9 tests covering deterministic
  ELIGIBLE / NOT_ELIGIBLE / UNKNOWN / ERROR paths, scheme-specific
  currency, prior-auth approval / short-circuit, missing service code.
- `EligibilityServiceImplTest` — 6 tests covering ELIGIBLE persistence,
  ERROR audit, missing patient, wrong-patient insurance guard,
  scheme-specific provider preference, latest-for-patient mapping.
- `SmartPhraseServiceImplTest` — 10 tests covering scope/owner
  validation, lowercase normalisation, duplicate-trigger guard, USER >
  HOSPITAL > GLOBAL precedence, recordUsage delegation + 404.

All 25 tests pass. FE typecheck and ESLint both pass clean.

---

## Deferred

- Real partner connectors for NHIS, NHIA, CNAMGS, mutuelle networks — the
  shipped `EligibilityProvider` SPI is the integration point. A real
  connector registers a more specific Spring bean; the wire format does
  not change.
- A dedicated SmartPhrase admin screen — for now the library is managed
  via the REST endpoints; clinicians own their personal macros via the
  encounter form (creation UX deferred).
