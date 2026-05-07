# After-Visit Summary on Patient Portal (iOS / Android) — User Stories

> **PHI handling.** This document describes a real production-incident shape but
> uses **synthetic placeholders** for every identifier and clinical string —
> never paste a real UUID, MRN, free-text instruction, diagnosis, or
> patient/hospital name into this file. Anyone investigating the actual
> incident should look up the real values in the operational data store and
> keep them out of source control. (Per the Copilot/CodeQL review on PR #259.)

**Source incident (synthetic example):** Encounter `ENCOUNTER_ID_EXAMPLE` (Patient `PATIENT_ID_EXAMPLE`, Hospital `HOSPITAL_ID_EXAMPLE`) was checked out on **YYYY-MM-DD HH:MM:SS** with `status = COMPLETED`, `checkout_timestamp`, `follow_up_instructions` ("Synthetic example: follow up with a specialist in N weeks") and `discharge_diagnoses` ("Synthetic example diagnosis") populated — but the patient's iOS / Android **Visit Summaries** screen is still empty the next day. Vitals, medications, and labs taken at the linked appointment are not appearing in the AVS.

**Scope:** Patient Portal — Android ([patient-android-app/](patient-android-app/)) and iOS ([patient-ios-app/](patient-ios-app/)) — plus the Spring Boot backend that feeds `GET /me/patient/after-visit-summaries`.

**Conventions followed:** [.github/copilot-instructions.md](.github/copilot-instructions.md), [.github/agents/hms-plan.agent.md](.github/agents/hms-plan.agent.md), [.github/agents/hms-implement.agent.md](.github/agents/hms-implement.agent.md). All 8 layers (DB → Mobile UI) must be considered.

---

## Epic A — Make the AVS visible to the patient on mobile

### US-AVS-001 — Patient can see the AVS for a checked-out encounter on iOS/Android

**As a** patient who was discharged yesterday
**I want** the After-Visit Summary for my completed encounter to appear in my mobile Visit Summaries list
**So that** I can review my diagnoses, follow-up instructions, and next steps from home

**Acceptance criteria**
- Given encounter `status = COMPLETED` and `checkout_timestamp IS NOT NULL`
- When I open Visit Summaries on iOS ([VisitSummariesView.swift](patient-ios-app/MediHubPatient/Features/Visits/VisitSummariesView.swift)) or Android ([VisitSummariesScreen.kt](patient-android-app/app/src/main/java/com/bitnesttechs/hms/patient/features/visitsummaries/VisitSummariesScreen.kt))
- Then within 5 seconds I see a card with at minimum: visit date, hospital, discharging provider, follow-up instructions, discharge diagnoses
- And the card is dated with `checkout_timestamp` (not `created_at`)
- And empty/null backend fields render as a neutral placeholder (e.g. "Not provided"), never as a blank card

**Layers touched** Mobile UI (iOS + Android), Mobile network/cache, backend response shape.

---

### US-AVS-002 — Patient receives a push notification when their AVS is ready

**As a** patient
**I want** a push notification on my phone the moment the doctor checks me out
**So that** I don't have to keep opening the app to check

**Acceptance criteria**
- Given the doctor calls `POST /encounters/{id}/checkout` and it succeeds
- Then `notificationService.createNotification(..., "DISCHARGE_SUMMARY")` fires (already exists in [EncounterServiceImpl.notifyPatientAfterVisitSummary](hospital-core/src/main/java/com/example/hms/service/EncounterServiceImpl.java))
- And a **push** (APNs / FCM) is delivered to the patient's registered device(s) — **not just** in-app + email
- And tapping the push deep-links to the AVS detail screen for that `encounterId`
- And the notification is wrapped in try/catch so it never rolls back the checkout transaction

**Layers touched** Backend service (push provider integration), iOS APNs registration, Android FCM registration, deep-link routing.

---

### US-AVS-003 — Patient can pull-to-refresh and see freshly-completed visits

**As a** patient who already had the app open during checkout
**I want** to pull down on the Visit Summaries list to refresh
**So that** I don't have to kill and relaunch the app

**Acceptance criteria**
- Pull-to-refresh gesture is wired on iOS `VisitSummariesView` and Android `VisitSummariesScreen`
- Triggers a fresh `GET /me/patient/after-visit-summaries` (cache bypass, `Cache-Control: no-cache`)
- Shows a spinner during the refetch and the new card appears at the top, ordered by `dischargeDate DESC`
- Pull-to-refresh works even while offline — shows a non-blocking error toast and keeps cached list

---

### US-AVS-004 — Empty state explains *why* nothing is showing

**As a** patient with no completed visits
**I want** a clear empty state instead of a blank screen
**So that** I don't think the app is broken

**Acceptance criteria**
- iOS and Android Visit Summaries screen shows: an icon, "No visit summaries yet", a one-line explanation, and a **Retry** button
- Empty state is distinct from the **error** state (network / 5xx) which shows: "Couldn't load — Tap to retry"
- Empty state is distinct from the **loading** state (skeleton placeholders)

---

## Epic B — Make the AVS payload complete (root cause)

### US-AVS-005 — AVS includes vitals captured during the encounter

**As a** patient
**I want** my vitals (BP, HR, temperature, SpO₂, weight) from the visit to appear in the AVS
**So that** I have a record of what was measured

**Acceptance criteria**
- `EncounterServiceImpl.upsertDischargeSummaryForCheckout()` aggregates the latest `Vitals` for the encounter (joined via `encounter_id` or `appointment_id`) into a new `DischargeSummary.vitals` collection / DTO field
- `DischargeSummaryResponseDTO` and the matching iOS `AfterVisitSummaryDTO` (Swift) and Android model (Kotlin) gain a typed `vitals` field
- iOS `VisitSummariesView` and Android `VisitSummariesScreen` render a "Vitals" section with each measurement + unit + timestamp
- N+1 prevention: vitals fetched with a single `JOIN FETCH` query
- Unit + integration tests added (`EncounterServiceImplTest`, `MockMvc` for `/me/patient/after-visit-summaries`)

**⚠️ PHI** — needs peer review (per [hms-plan.agent.md](.github/agents/hms-plan.agent.md#flag-risks)).

---

### US-AVS-006 — AVS includes all medications administered/prescribed during the encounter

**As a** patient
**I want** every medication given to me during the visit *and* every prescription written at discharge to be listed in the AVS
**So that** I know exactly what I'm on and what to pick up

**Acceptance criteria**
- AVS aggregates from both `Prescription` (already implemented) **and** `MedicationAdministration` / appointment medication records
- Each entry includes name, dose, route, frequency, action (CONTINUED / NEW / DISCONTINUED / HELD)
- Backend test `EncounterServiceImplTest.checkOut_aggregatesMedsFromBothSources` passes
- Mobile renders deduped list with a "New today" badge for prescriptions issued at checkout

---

### US-AVS-007 — AVS includes lab results ordered/resulted during the encounter

**As a** patient
**I want** lab results from this visit (or pending labs) listed in my AVS
**So that** I know what tests were done and which to follow up on

**Acceptance criteria**
- AVS aggregates `LabOrder` / `LabResult` filtered by `encounter_id`
- Resulted labs show value + reference range + flag (H/L/critical)
- Pending labs show a "Pending — your provider will contact you" status
- iOS and Android render a "Labs" section
- N+1 prevented; section hidden if no labs
- ⚠️ PHI / clinical correctness — peer review required

---

### US-AVS-008 — Backend never returns an empty/half-built AVS for a COMPLETED encounter

**As a** backend engineer
**I want** `GET /me/patient/after-visit-summaries` to *guarantee* a populated DischargeSummary exists for every COMPLETED encounter
**So that** patients never see a blank list when their encounter is done

**Acceptance criteria**
- Backfill in [DischargeSummaryServiceImpl](hospital-core/src/main/java/com/example/hms/service/DischargeSummaryServiceImpl.java) runs **synchronously** on the GET path **and** as a Spring `@Scheduled` job every 15 min
- The scheduled job emits a Prometheus counter `avs_backfill_created_total` and a gauge `avs_completed_encounters_without_summary`
- A Grafana alert fires if the gauge > 0 for more than 30 minutes
- Idempotency: backfill on the same encounter twice does not duplicate `discharge_summaries` rows
- Unit test covers the case from this incident: COMPLETED encounter + checkout_timestamp + null medication_reconciliation → enrichment populates it

---

## Epic C — Diagnose & prevent recurrence

### US-AVS-009 — Diagnostics for "AVS empty on mobile" incidents

**As an** on-call engineer
**I want** a single dashboard / runbook step to diagnose why a specific patient's AVS is missing
**So that** I can resolve incidents in minutes instead of hours

**Acceptance criteria**
- New runbook in [docs/runbooks/](docs/runbooks/): `avs-empty-on-mobile.md` covering the 6 known failure modes (DB row missing, null fields, mobile cache, push not registered, role/permission, network)
- New backend log line at INFO on `/me/patient/after-visit-summaries`: `patientId, count, encounterIds, durationMs`
- Mobile (iOS + Android) logs the response body length + first encounter ID returned (PHI-safe — no clinical content) for Grafana Faro RUM
- Add a `?debug=1` admin-only flag that returns a `_diagnostic` block explaining why each COMPLETED encounter did or did not appear

**⚠️ PHI** — `?debug` flag must be `@PreAuthorize("hasRole('" + SecurityConstants.ROLE_SUPER_ADMIN + "')")` and never log clinical content.

---

### US-AVS-010 — End-to-end test reproducing the incident shape

**As a** QA engineer
**I want** a Playwright / instrumented mobile test that reproduces this exact scenario
**So that** the regression cannot reach production again

**Acceptance criteria**
- Backend integration test (MockMvc): seed a Patient + Appointment with vitals/meds/labs, doctor checkout via `POST /encounters/{id}/checkout`, GET `/me/patient/after-visit-summaries` returns ≥1 fully-populated AVS within the same request lifecycle
- Mobile test (iOS XCUITest **and** Android instrumented Compose test): log in as patient, observe Visit Summaries screen, assert the new card is visible after the backend test seeded data
- Tests run in CI on PR to `develop`

---

## Epic D — Permissions & PHI

### US-AVS-011 — Only the patient (or their delegate) can fetch their AVS

**As a** privacy officer
**I want** `/me/patient/after-visit-summaries` to be locked to `ROLE_PATIENT` and the JWT `sub` is matched to the encounter's `patient_id`
**So that** patients cannot read each other's AVS

**Acceptance criteria**
- Controller already has `@PreAuthorize` — verify constant matches one in [SecurityConstants.java](hospital-core/src/main/java/com/example/hms/security/SecurityConstants.java)
- Service-layer check: `encounter.patient.userId == currentUser.id` — 403 otherwise
- MockMvc test: patient A authenticated, requests patient B's encounter via `/encounters/{B-id}/avs` → 403
- ⚠️ Auth + PHI — peer review required

---

## Out of scope (explicitly)

- Re-architecting the discharge_summaries table
- Web portal AVS (separate epic — web already uses [my-visits.component.ts](hospital-portal/src/app/patient-portal/my-visits/my-visits.component.ts))
- Provider-side AVS editing after checkout
- Multi-language AVS (will reuse the existing i18n system once content stabilises)

---

## Suggested ordering for delivery

1. **US-AVS-009** (diagnostics) — light, unblocks everything
2. **US-AVS-008** (backfill + alerting) — closes the immediate gap
3. **US-AVS-001 / 003 / 004** (mobile UX hygiene)
4. **US-AVS-005 / 006 / 007** (payload completeness — the deeper root cause)
5. **US-AVS-002** (push notifications)
6. **US-AVS-010** (regression test)
7. **US-AVS-011** (auth hardening — should be confirmed at every step)

> Run **HMS Tasks** ([.github/agents/hms-tasks.agent.md](.github/agents/hms-tasks.agent.md)) on each story to scaffold the 8-layer todo list, then **HMS Implementer** ([.github/agents/hms-implement.agent.md](.github/agents/hms-implement.agent.md)) to execute, then **HMS Reviewer** ([.github/agents/hms-review.agent.md](.github/agents/hms-review.agent.md)) before PR.

---

## Delivery log

### PR 1 — `feat/avs-mobile-backfill-observability` (2026-05-07)

**Partial coverage of US-AVS-008, US-AVS-009, US-AVS-006.**

Backend — [DischargeSummaryServiceImpl.java](hospital-core/src/main/java/com/example/hms/service/DischargeSummaryServiceImpl.java)

- Structured INFO log on every `GET /me/patient/after-visit-summaries`:
  `AVS portal fetch: patientId={…} returnedCount={…} backfilledCount={…} enrichedCount={…} firstEncounterIds={…} durationMs={…}` — single grep-able line for on-call.
- Prometheus counters via Micrometer:
  - `hms.avs.portal.fetch{outcome=hit|empty|backfilled}` — for SLO + Grafana alerting.
  - `hms.avs.portal.backfill{outcome=backfilled}` — fires when checkout-time write path failed silently and the GET path had to recreate the row. **A non-zero rate here is a signal that something upstream is broken** and warrants investigation.
  - `hms.avs.portal.enrich{outcome=backfilled}` — fires when an existing row was missing `hospitalCourse` or `medicationReconciliation`.
- Metrics never fail the request (try/catch + null-safe on `MeterRegistry`).

Backend tests — [DischargeSummaryServiceImplTest.java](hospital-core/src/test/java/com/example/hms/service/DischargeSummaryServiceImplTest.java)

- New nested class `PortalPatientAvs` with 4 tests:
  - empty patient → counter `outcome=empty`
  - existing summary returned → counter `outcome=hit`
  - **orphan COMPLETED encounter (the incident shape) → counter `outcome=backfilled`**, summary persisted with diagnoses + follow-up + notes
  - calling twice on a fully-enriched patient → no duplicate writes, counter increments to 2

iOS — [ClinicalModels.swift](patient-ios-app/MediHubPatient/Core/Models/ClinicalModels.swift)

- **Fixed real bug** found during diagnosis: `MedicationReconciliationDTO` was reading the JSON key `action`, but the backend serialises `reconciliationAction`. The optional decode silently returned `nil`, making the medication action label blank on iOS even when the backend had populated it. Now decodes the canonical key with the legacy key as a fallback (`action` is kept as a computed-property alias so existing call sites compile unchanged).

### Still to ship (in priority order)

- **US-AVS-005 / 007** — aggregate vitals + labs into the AVS payload (deeper root cause; needs PHI peer review)
- **US-AVS-008 (remainder)** — Spring `@Scheduled` job that runs every 15 min hospital-wide, plus a Grafana alert wired to `hms.avs.portal.backfill_total > 0 for 30m`
- **US-AVS-001 / 003 / 004** — iOS + Android pull-to-refresh, error state, empty state
- **US-AVS-002** — APNs / FCM push on encounter checkout
- **US-AVS-010** — full E2E (MockMvc + iOS XCUITest + Android Compose instrumented test) reproducing the incident
- **US-AVS-011** — confirm `@PreAuthorize` constants on every AVS endpoint match `SecurityConstants.ROLE_PATIENT`

### How to validate this PR in production

1. After deploy, watch Grafana for `hms.avs.portal.fetch{outcome="backfilled"}`.
2. If the affected patient (or any patient with a COMPLETED encounter and an empty mobile screen) opens Visit Summaries, the structured log will print and the counter will increment.
3. The affected encounter from the source incident (look up the real ID in the operational data store — keep it out of this doc) should be retroactively visible on iOS / Android once the patient pulls fresh data — the GET-path backfill+enrich already runs on every call.
