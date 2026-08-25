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

Row 27 foundation pass (`feat/v1.1-cds-hooks-public-discovery`) hardens
the public discovery endpoint for Cerner / Epic / SMART App Launcher
sandbox compatibility. The discovery endpoint itself was already
public (per the spec); the row-27 work was about machine-validateable
shape + CORS allowlist + prefetch templates.

### Sandbox CORS allowlist

`SecurityConfig` honors three groups of origins on `/**`:

1. Local dev defaults (`http://localhost:*`, `https://e-keneya.com`, `https://*.e-keneya.com`).
2. `APP_CORS_ALLOWED_ORIGINS` (comma-separated, operator-supplied).
3. **CDS Hooks sandbox origins**: gated by
   `app.cors.cds-hooks-sandbox.enabled=true` (default), supplied via
   `app.cors.cds-hooks-sandbox.origins`. Defaults cover:
   `https://fhir.epic.com`, `https://*.epic.com`,
   `https://fhir-ehr-code.cerner.com`, `https://sandbox.cerner.com`,
   `https://*.cerner.com`, `https://launcher.smarthealthit.org`,
   `https://*.smarthealthit.org`.

Set `APP_CORS_CDS_HOOKS_SANDBOX_ENABLED=false` for closed-network
deployments. Sandbox origins do not carry PHI.

**Known issue (PR #338 review, P0 follow-on):** the row-27 foundation
pass added the sandbox origins to the **global** `/**` CORS config,
which broadens cross-origin access for every PHI-bearing endpoint
under `/api/**`, not just `/cds-services`. The corrective pattern is
to register a path-scoped `CorsConfiguration` only for the discovery
endpoint:

```java
var source = new UrlBasedCorsConfigurationSource();
source.registerCorsConfiguration("/**", coreCfg);             // existing
source.registerCorsConfiguration("/cds-services/**", cdsCfg);  // new, narrow
```

Track this as a P0 follow-on against the row-27 PR; the global-CORS
shape was Copilot-flagged as High severity.

**`@Value` default-replace trap.** When an operator sets
`APP_CORS_CDS_HOOKS_SANDBOX_ORIGINS=...`, Spring **replaces** the
default list — it does not append. Runbook prose that says "extend"
or "add to the defaults" is wrong; either rewrite the prose to say
"replaces the default list; include the built-in origins explicitly
if you want to keep them", or compose defaults in code so additions
truly append. Caught in PR #338 review on both `SecurityConfig.java`
and `docs/runbooks/cds-hooks-sandbox-validation.md`.

### Prefetch templates

The two `patient-view` services declare prefetch templates so partner
EHRs pre-resolve the FHIR queries and ship the bundles inline:

- `PatientViewCdsService`: `patient`, `allergies` (active),
  `problems` (active **+ recurrence** — the local service treats
  both `ACTIVE` and `RECURRENCE` statuses as active when building
  cards, so the prefetch query must match or recurrent problems get
  dropped if HMS starts honoring `request.prefetch`).
- `BpaProtocolsCdsService`: `patient`, `vitals` (`_count=2000`
  sorted desc — **NOT 20**; `BpaRuleEngine` loads a 24-hour window
  paging up to 2000 vitals because high-frequency monitoring would
  otherwise miss older readings that still trigger rules),
  `problems` (active), `medications` (active).

**Prefetch templates MUST mirror the service-internal queries.** If
the service reads `problems WHERE status IN (ACTIVE, RECURRENCE)` but
the prefetch template only fetches `clinical-status=active`, then any
EHR that ships the prefetch bundle inline (Cerner, Epic) will give
the service a strict subset of the data it expects, and cards will
silently disappear. Audit each prefetch template against the
corresponding repository query before merging — this is the row-27
follow-on for both `PatientViewCdsService` and `BpaProtocolsCdsService`.
Caught in PR #338 Copilot review.

**Prefetch contract incompleteness.** The row-27 foundation pass
advertises prefetch templates in the descriptors but the `evaluate(...)`
methods still ignore `request.prefetch` entirely — they read patient
data from local repositories keyed on the HMS UUID extracted from
`context.patientId`. For sandbox / EHR invocations carrying non-HMS
FHIR patient ids, the advertised payload is dropped on the floor and
the cards come back empty. Honoring the prefetch is a row-27 follow-on
item; in the meantime, advertising it is honest only for invocations
that also pass a resolvable HMS UUID via `context.patientId`. Caught
in PR #338 Copilot review.

The four CDS services on `order-sign` / `order-select` /
`medication-prescribe` hooks intentionally declare **no** prefetch —
those services receive the resources to act on directly in the
hook context.

### When adding a new service

1. Register descriptor with a stable `id` (kebab-case, prefix `hms-`).
2. If the hook is `patient-view` and the service reads patient data,
   declare prefetch templates so Cerner/Epic can pre-resolve.
3. Add unit tests for empty-chart + at-least-one-card + critical-card
   paths.
4. Test the rendered card detail contains the expected typed
   annotations.
5. If the service depends on a code-lookup helper, add a defence-in-
   depth test that every seed entry passes the global regex.
6. The `CdsHooksDiscoveryIT.registeredServicesMatchExpectedInventory`
   assertion is **load-bearing** — removing or renaming a registered
   service must fail this test before it fails the downstream EHR
   integration.

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
- `hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java` — CDS Hooks sandbox CORS allowlist
- `hospital-core/src/test/java/com/example/hms/cdshooks/CdsHooksDiscoveryIT.java` — five-case discovery contract
- `docs/runbooks/cds-hooks-sandbox-validation.md` — sandbox validation playbook
