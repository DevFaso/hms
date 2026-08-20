# EMPI v0 — probabilistic matching (foundation pass)

**Status:** foundation pass shipped on `feat/v2.0-foundation-batch` (roadmap row 25). The wire contract + DTO shape + feature-flag scaffolding land here so the receptionist UI can be wired against a stable empty-list response; the actual Fellegi-Sunter scorer + labelled-audit-set tuning are the named row-25 follow-on.

---

## Feature flag

```
app.empi.probabilistic.enabled=${EMPI_PROBABILISTIC_ENABLED:false}
app.empi.probabilistic.min-score=0.7
app.empi.probabilistic.max-candidates=10
```

Default OFF. When off:

- `POST /api/empi/candidates` → `404 Not Found`
- `EmpiProbabilisticMatcher.findCandidates(...)` returns an empty list

When on: the endpoint reaches the matcher and the Fellegi-Sunter scorer returns real scored candidates (weights: name 0.40, DOB 0.25, national-ID 0.25, sex 0.10; `min-score` cut-off, `max-candidates` truncation), scoped to the caller's active hospital. **No auto-merge, no auto-link** at any flag setting — the receptionist confirms a match to navigate to the existing patient, and identity merges are the separate admin-only `/empi` endpoints (`POST /empi/merge-by-patient`, `POST /empi/identities/{id}/merge`), which are independent of this flag. *(An earlier version of this runbook described the pre-scorer foundation pass, which returned an empty list even when enabled; the scorer body shipped in `020719d2`.)*

---

## Surface

### `POST /api/empi/candidates`

Allowlist: `SUPER_ADMIN`, `HOSPITAL_ADMIN`, `RECEPTIONIST`, `NURSE`, `DOCTOR`. Mirrors the deterministic identity-resolution roles.

**Request body** (`application/json`):

```json
{
  "firstName": "Awa",
  "lastName": "Diallo",
  "dateOfBirth": "1990-01-01",
  "sex": "F",
  "nationalId": "BF1234567890"
}
```

All fields optional; the scorer weighs whatever is present.

**Response** (200): list of `EmpiCandidateMatchDTO`:

```json
[
  {
    "patientId": "uuid",
    "displayName": "Diallo, Awa",
    "score": 0.92,
    "nameMatched": true,
    "dobMatched": true,
    "sexMatched": true,
    "nationalIdMatched": false
  }
]
```

Sorted by `score` descending; truncated to `max-candidates`. Empty list when nothing scores above `min-score` (or when the flag is on but the scorer body is still deferred).

---

## Why the scorer is deferred

The deliverable target is "≥ 90 % recall on labelled audit set". Shipping a scorer body without the labelled audit set means tuning `min-score` against intuition rather than data — which is exactly how false-positive merge incidents start. The foundation pass intentionally ships the contract empty so:

1. The receptionist UI integration work can land in parallel against the stable DTO shape.
2. The audit-set assembly (labelled by the receptionist team — same patient or different, across 500+ pairs) happens in its own focused work stream.
3. The scorer + threshold tuning + ROC analysis ship as a single atomic follow-on PR.

---

## Row-25 follow-on

The row stays `started` until these land:

- **Fellegi-Sunter scorer body** with these axes:
  - Name: Jaro-Winkler over `(firstName, lastName)` with phonetic fallback (Soundex / Metaphone) for transliteration variants common in West Africa.
  - DOB: exact match + year-only fallback + month/year fallback; penalty per axis loss.
  - Sex: exact match; small penalty on disagreement (sex annotations are sometimes wrong on legacy records).
  - National-ID: exact match + format-specific checksum where defined (BF, CI, SN, GH at minimum); strong positive weight when matched.
- **Labelled audit set** under `docs/empi/probabilistic-audit-set.md` — 500+ labelled pairs with the ground-truth match decision. Calibrates `min-score` to the ≥90% recall point with the lowest practical FPR.
- **ROC analysis** documented alongside the audit set, with the chosen `min-score` justified.
- **Receptionist UI** (`<app-empi-candidate-list>`) embedded in the patient-registration component with the "this is a different patient" / "this IS the same patient — link" CTAs; the link action calls the deterministic alias path (no auto-merge).
- **Cross-tenant EMPI v1** (roadmap row 40) — this row gates that one; v1 reuses the same scorer with Consent + national-ID as the joining key across hospitals.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/empi/probabilistic/EmpiProbabilisticProperties.java`
- `hospital-core/src/main/java/com/example/hms/empi/probabilistic/EmpiProbabilisticMatcher.java`
- `hospital-core/src/main/java/com/example/hms/empi/probabilistic/EmpiCandidateQueryDTO.java`
- `hospital-core/src/main/java/com/example/hms/empi/probabilistic/EmpiCandidateMatchDTO.java`
- `hospital-core/src/main/java/com/example/hms/controller/EmpiProbabilisticController.java`
- `hospital-core/src/test/java/com/example/hms/empi/probabilistic/EmpiProbabilisticMatcherTest.java`
- `hospital-core/src/test/java/com/example/hms/empi/probabilistic/EmpiProbabilisticControllerIT.java`
- Companion deterministic path: `hospital-core/src/main/java/com/example/hms/service/empi/EmpiServiceImpl.java` (alias-based resolution; `empi-identity` skill is the authority)
