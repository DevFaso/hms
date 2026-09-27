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
