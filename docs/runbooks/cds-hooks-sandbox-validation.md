# CDS Hooks public discovery — sandbox validation

**Status:** foundation pass shipped on `feat/v1.1-cds-hooks-public-discovery` (roadmap row 27).

The HMS CDS Hooks 1.0 discovery endpoint (`GET /cds-services`) is reachable without authentication per the spec. This runbook documents how to validate HMS against the three external testing surfaces partner EHRs use:

1. SMART App Launcher (`https://launcher.smarthealthit.org`)
2. Cerner CDS Hooks sandbox (`https://fhir-ehr-code.cerner.com/cds-hooks-sandbox`)
3. Epic CDS Hooks sandbox (`https://fhir.epic.com/Documentation?docId=cdshooks`)

---

## Discovery surface

```
GET /api/cds-services        → 200 + { "services": [ … ] }
POST /api/cds-services/{id}  → 200 + { "cards": [ … ] }   (requires bearer)
```

Six services are registered today:

| `id` | `hook` | Prefetch |
| --- | --- | --- |
| `hms-patient-view` | `patient-view` | yes |
| `hms-bpa-protocols` | `patient-view` | yes |
| `hms-order-sign-rules` | `order-sign` | — |
| `hms-medication-allergy-check` | `order-sign` | — |
| `hms-order-select-rules` | `order-select` | — |
| `hms-medication-prescribe-rules` | `medication-prescribe` | — |

Adding or removing a registered service is caught by `CdsHooksDiscoveryIT.registeredServicesMatchExpectedInventory`.

---

## CORS allowlist

`SecurityConfig.corsConfigurationSource()` honors three groups of origins on `/**`:

1. Local dev defaults: `http://localhost:*`, `http://127.0.0.1:*`, `https://*.bitnesttechs.com`.
2. Operator-supplied via `APP_CORS_ALLOWED_ORIGINS` (comma-separated).
3. **CDS Hooks sandbox origins** (new in row 27), gated by `app.cors.cds-hooks-sandbox.enabled=true` (default) and supplied via `app.cors.cds-hooks-sandbox.origins`. The default list covers:

   - `https://fhir.epic.com`, `https://*.epic.com`
   - `https://fhir-ehr-code.cerner.com`, `https://sandbox.cerner.com`, `https://*.cerner.com`
   - `https://launcher.smarthealthit.org`, `https://*.smarthealthit.org`

Set `APP_CORS_CDS_HOOKS_SANDBOX_ENABLED=false` to suppress the sandbox origins entirely (e.g. closed-network deployments). Set `APP_CORS_CDS_HOOKS_SANDBOX_ORIGINS=…` to extend with private validation environments. These are public testing sandboxes only; they do not carry PHI.

---

## Local smoke test

```powershell
# Discovery — public, no bearer.
Invoke-WebRequest -Method GET -Uri 'http://localhost:8080/api/cds-services' `
    | Select-Object -ExpandProperty Content | ConvertFrom-Json | Select-Object -ExpandProperty services

# CORS preflight from the SMART App Launcher origin.
Invoke-WebRequest -Method OPTIONS -Uri 'http://localhost:8080/api/cds-services' `
    -Headers @{
        'Origin' = 'https://launcher.smarthealthit.org';
        'Access-Control-Request-Method' = 'GET';
        'Access-Control-Request-Headers' = 'Accept';
    } | Select-Object -ExpandProperty Headers
```

The preflight response must echo `Access-Control-Allow-Origin: https://launcher.smarthealthit.org` and list `GET` in `Access-Control-Allow-Methods`.

---

## Validation steps against external sandboxes

### SMART App Launcher

1. Open `https://launcher.smarthealthit.org/?fhir_version=r4`.
2. Pick a sandbox patient → "Launch App".
3. Enter the HMS portal launch URL (or the public CDS Hooks sandbox helper at https://cds-hooks.smarthealthit.org/).
4. Confirm the discovery call from `https://launcher.smarthealthit.org` reaches HMS without a CORS error in the browser console.
5. Invoke `hms-patient-view` — expect the cards to render with the standard CDS Hooks 1.0 indicator levels.

### Cerner sandbox

1. Open `https://fhir-ehr-code.cerner.com/cds-hooks-sandbox/`.
2. Register the HMS discovery URL (`https://<env>/api/cds-services`).
3. Trigger `patient-view` against a sandbox patient.
4. Confirm prefetch templates in the descriptor are honored — Cerner pre-resolves the FHIR queries and ships them in the invocation request body.

### Epic sandbox

1. Open `https://fhir.epic.com/Documentation?docId=cdshooks`.
2. Add HMS as an external CDS service in the App Orchard sandbox.
3. Trigger `medication-prescribe` against a sandbox encounter.
4. Confirm `hms-medication-prescribe-rules` invocation succeeds and the response carries `application/json`.

For each sandbox, capture the request + response pair and attach to the row-27 PR before flipping the roadmap row to `completed`.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/cdshooks/CdsHooksController.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/CdsHookRegistry.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/PatientViewCdsService.java`
- `hospital-core/src/main/java/com/example/hms/cdshooks/service/BpaProtocolsCdsService.java`
- `hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java` — CORS allowlist
- `hospital-core/src/test/java/com/example/hms/cdshooks/CdsHooksDiscoveryIT.java`
