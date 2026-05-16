---
name: cds-hooks-service
description: Use when adding or modifying CDS Hooks 1.0 services (patient-view, order-select, medication-prescribe, order-sign), BPA rules, or terminology bindings (RxNorm, LOINC). Triggers on changes under hospital-core/src/main/java/com/example/hms/cdshooks/.
---

# CDS Hooks 1.0 services

HMS implements the CDS Hooks 1.0 contract for in-house and partner-EHR
consumers (Cerner, Epic sandboxes target). Services discover via
`GET /api/cds-services` and dispatch via `POST /api/cds-services/{id}`.

## The service contract

Every service implements `CdsHookService` with two methods:

- `descriptor()` — `CdsServiceDescriptor(hook, id, title, description, prefetch)`.
  `id` is the lookup key; must be unique repo-wide (enforced at startup
  by `CdsHookRegistry`).
- `evaluate(CdsHookRequest) → CdsHookResponse` — returns a list of
  `CdsCard`s.

Register the service as a `@Component`; it gets picked up automatically.

## Indicator semantics

- `INFO` — informational (problem list, allergy summary on chart-open).
- `WARNING` — actionable but not blocking (single-severe allergy, dosing
  concern).
- `CRITICAL` — blocking; the consumer should refuse to submit until the
  user acknowledges. Use sparingly; reserve for patient-safety events
  the clinical lead has signed off on.

## Card payload conventions

Every clinical code rendered on a card carries typed annotations so
external consumers can map back to standard systems:

- `[ICD-10: I10]` for problem codes
- `[LOINC: 85354-9 Blood pressure panel]` for LOINC bindings
- `[RxNorm: 1191]` for medications

Format is enforced in renderers (e.g. `PatientViewCdsService.appendCodings`)
and validated against `TerminologyCodes.isValidLoinc` /
`isValidIcd10` / `isValidRxNorm`. Malformed codes are dropped silently
— the card still renders, just without the annotation.

## Terminology binding precedence

For any code-driven CDS service:

1. **Entity-explicit code wins** (e.g. `PatientProblem.loincCode`).
2. **Curated seed table fallback** (`ProblemLoincBindings`,
   `MedicationCatalogItem.rxnormCode`).
3. **No external HTTP / FHIR terminology server** — HMS targets
   intermittent-connectivity West-African deployments. Same rationale
   as the RxNorm seed list (see V93 header).

When adding a new seed entry: it MUST pass the global regex in
`TerminologyCodes` — defence-in-depth tests in
`ProblemLoincBindingsTest.everySeededLoincPassesTheGlobalShapeValidator`
catch typos.

## BPA rules

Best-Practice-Advisory rules live in `cdshooks/bpa/` and route through
`BpaProtocolsCdsService` on `patient-view`. Implementations:

- `MalariaFeverProtocolRule` (West-Africa relevant — B50–B54 ICD)
- `ObHemorrhageProtocolRule`
- `SepsisQsofaProtocolRule`

Each rule implements `BpaRule` + `evaluate(BpaRuleContext)`. The context
carries the resolved patient, problems, vitals, medications. Rules MUST
NOT mutate state — emit a `CdsCard` or return nothing.

## Drug interaction rules

Live in `cdshooks/rules/`:

- `DrugDrugInteractionRule` — pairs from `drug_interactions` seed table
- `DuplicateMedicationOrderRule` — already-active prescription on the
  same RxNorm
- `PediatricDoseRule` — age-banded dose limits

Drug interaction lookups go through `MedicationCatalogItemRepository`
keyed on `rxnorm_code` with a partial index (see V93).

## Discovery + tests

The discovery endpoint must validate against Cerner + Epic CDS Hooks
sandboxes — this is roadmap row 27. When adding a new service:

1. Register descriptor with a stable `id` (kebab-case, prefix `hms-`).
2. Add unit tests for empty-chart + at-least-one-card + critical-card
   paths.
3. Test the rendered card detail contains the expected typed annotations.
4. If the service depends on a code-lookup helper, add a defence-in-depth
   test that every seed entry passes the global regex.

## Reference files

- `hospital-core/src/main/java/com/example/hms/cdshooks/CdsHooksController.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/CdsHookRegistry.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/CdsHookService.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/dto/CdsHookDtos.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/PatientViewCdsService.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/MedicationPrescribeRulesCdsService.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/OrderSelectRulesCdsService.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/BpaProtocolsCdsService.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/terminology/ProblemLoincBindings.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/terminology/RxNormCodingExtractor.java`
- `hospital-core/src/main/java/com/example/hms/terminology/TerminologyCodes.java`
