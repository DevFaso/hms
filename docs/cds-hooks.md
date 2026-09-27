# HMS CDS Hooks 1.0 services

> P0.3 of the Epic-alignment workstream (see [`claude/finding-gaps.md`](../claude/finding-gaps.md)).
>
> **Goal:** make the EHR a CDS-Hooks server so any compatible smart-app can
> drive Best Practice Advisories — drug-allergy alerts, malaria-protocol
> reminders, sickle-cell flags — into the clinician workflow.
>
> Spec: <https://cds-hooks.hl7.org/1.0/>

## Endpoints

| Method | Path                              | Auth          | Purpose |
| ------ | --------------------------------- | ------------- | ------- |
| `GET`  | `/api/cds-services`               | **public**    | Service catalogue (per CDS Hooks spec). |
| `POST` | `/api/cds-services/{serviceId}`   | Bearer JWT, clinical roles | Invoke a service for a hook context. |

The discovery endpoint is intentionally unauthenticated — clients need to
know what services exist before deciding whether to trigger them. It carries
no patient data.

Invocation is admitted to `DOCTOR`, `NURSE`, `MIDWIFE`, `PHARMACIST` and
`SUPER_ADMIN` (the same list that may acknowledge a card) and refused with
`403` to everyone else, patients included. The patient in `context.patientId`
must then be readable at the caller's hospital under the chart rule
(`PatientChartAccess.require`); a patient at another hospital, a restricted
chart, or an unresolved hospital scope answers `200 {"cards":[]}` — exactly
what an unknown patient answers.

## Which hospital's records a card is built from

The chart gate above decides **whether** the caller may run a service on this
patient. It does not decide **which** records the card reads, and that is on
purpose: the services read the patient's records where the patient is
anchored, not only the rows of the hospital the caller acts at.

| Service | Records read |
| ------- | ------------ |
| `hms-patient-view` | allergies and problems from **every** hospital (`findByPatient_Id`) |
| `hms-medication-allergy-check` | allergies from **every** hospital; its drug-drug check reads active prescriptions at the patient's **home hospital** |
| `hms-bpa-protocols`, `hms-order-select-rules`, `hms-order-sign-rules`, `hms-medication-prescribe-rules` | vitals, active problems, active prescriptions and the formulary of the patient's **home hospital** (`Patient.hospitalId`, the hospital that first registered them) |

So for a patient registered at hospital A and seen at hospital B, a clinician
at B gets BPA and drug-safety cards derived from A's prescriptions, vitals and
problems (and allergies from both), while the chart tabs at B show B's rows.
**This is kept on purpose, as a drug-safety behaviour.** An interaction, a
duplicate order or an allergy recorded at the patient's home hospital is
exactly what the prescriber at B must be warned about, and narrowing the cards
to B's rows would silently drop those warnings. A card carries the advisory
(its summary and the conflicting drug or allergen), not the other hospital's
chart.

Two things bound it, and both are intended:

- Only a clinician who may open this patient's chart at their own hospital
  reaches a service at all (the gate above); an unknown patient, a patient at
  another hospital or a restricted chart gets `200 {"cards":[]}`.
- Roles that are not clinical do not see the panel: the portal renders
  `<app-bpa-panel>` only for `CDS_CLINICIAN_ROLES`
  (`hospital-portal/src/app/patients/patient-chart/chart-access.ts`), which
  mirrors the backend's clinician list, so a receptionist, an administrator or
  a laboratory role opens the chart without the panel and without a request
  to `/api/cds-services`.

A new service whose card would show another hospital's **record** rather than
a safety advisory must not follow this rule: it reads the caller's hospital
(or the readable set, as the chart tabs do).

## Services in P0.3

### `hms-patient-view` (hook: `patient-view`)

Fired when a clinician opens a patient chart. Returns:

- **Allergy summary card** — count of active allergies, their reactions,
  and severities. Indicator: `warning` if any allergy is `SEVERE` /
  `LIFE_THREATENING`; otherwise `info`.
- **Active problem-list card** — count and detail of all active problems
  (status `ACTIVE`, `RECURRENCE`, or `RELAPSE`).

This is the foundation for the Storyboard banner gap (#15).

### `hms-medication-allergy-check` (hook: `order-sign`)

Fired when a clinician is about to sign a `MedicationRequest`. Compares
the proposed medication text against the patient's active allergy list
(case-insensitive substring match against `allergenDisplay` and
`allergenCode`). On match returns a **critical** card.

The match is text-only by design — RxNorm / WHO ATC binding (gap #5)
will replace it. Even unbinded, this catches the common West-Africa
case where allergies are recorded as freetext (penicillin, sulfa, aspirin).

## Request shape (excerpt)

```json
POST /api/cds-services/hms-medication-allergy-check
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "hook": "order-sign",
  "hookInstance": "uuid",
  "fhirServer": "https://hms.example.com/api/fhir",
  "context": {
    "userId": "Practitioner/...",
    "patientId": "Patient/<uuid>",
    "draftOrders": {
      "entry": [{
        "resource": {
          "resourceType": "MedicationRequest",
          "medicationCodeableConcept": { "text": "Penicillin V 500 mg PO BID" }
        }
      }]
    }
  }
}
```

## Response shape

```json
{
  "cards": [
    {
      "summary": "Allergy alert: Penicillin V 500 mg PO BID matches recorded allergy “penicillin”",
      "detail":  "The patient has an active allergy entry that matches the proposed medication...",
      "indicator": "critical",
      "source": { "label": "HMS Allergy Check" },
      "uuid":  "<random>"
    }
  ]
}
```

## How to add a new service

1. Implement `CdsHookService`. Pick a unique id and the hook (`patient-view`,
   `order-select`, `order-sign`, `medication-prescribe`, etc.).
2. Annotate the implementation with `@Component`. The
   `CdsHookRegistry` auto-discovers it and adds it to discovery.
3. Use `CdsHookContext.requirePatientId(...)` /
   `CdsHookContext.medicationDrafts(...)` to safely unpack the
   loosely-typed hook context.
4. Return `CdsHookResponse.of(cards)` with one card per advisory.

## What's deferred

- **Suggestions / Apply Action** — cards do not yet propose actions the
  client could auto-apply (e.g. "switch to Erythromycin"). The DTOs
  support it (`CdsSuggestion` + `CdsSuggestionAction`); plumbing comes
  with order-set support (gap #6).
- **Prefetch templates** — discovery does not advertise prefetch queries
  yet. Clients pass the patient context inline.
- **Drug-drug interaction** — needs the medication catalogue's RxNorm
  bindings (gap #5).
- **Pediatric dose** — needs structured dose components in
  `Prescription` (also gap #5 / #6).
