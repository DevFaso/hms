# OB/GYN + pediatrics — finish scope audit

**Status:** foundation pass shipped on `feat/v2.0-foundation-batch` (roadmap row 41).
**Scope today:** an audit of what's actually missing in the partially-designed `HighRiskPregnancyCarePlan*`, `NewbornAssessment*`, `PostpartumCare*` services so the row-41 follow-on PRs can land one clinical surface at a time instead of in one huge bundle. **No service code changes** — the row stays `started` until each gap below is closed.

---

## What exists today

The three services and their controllers ARE present and substantial:

| Service | Lines | Wire endpoint | Substance |
| --- | --- | --- | --- |
| `HighRiskPregnancyCarePlanServiceImpl` | ~391 | `HighRiskPregnancyCarePlanController` | Care-plan CRUD + risk-factor list + visit-schedule projection |
| `NewbornAssessmentServiceImpl` | ~422 | `NewbornAssessmentController` | APGAR capture + initial measurements + feeding plan + screening flags |
| `PostpartumCareServiceImpl` | ~676 | `PostpartumCareController` | Mother + newborn dyad postpartum visits, lactation support, contraception counselling, depression screening (EPDS) |

These are not skeletons — they ship real persistence + business logic. The roadmap cell's "finish" language is misleading; what's actually missing is **(a) UI parity**, **(b) cross-service workflow integration**, **(c) a few late-lifecycle screens**.

---

## Concrete gaps (foundation-pass audit)

### Backend

#### Cross-service workflow integration

- **HighRiskPregnancyCarePlan → NewbornAssessment hand-off.** Today the two services live independently; no automatic linkage when a high-risk pregnancy progresses to delivery. **Needs:** a `Pregnancy.outcome` enum + `NewbornAssessment.parentPregnancyId` FK so the audit trail joins prenatal risk factors to neonatal outcomes. Drives the WHO-EMRO neonatal-mortality reporting requirement.
- **NewbornAssessment → PostpartumCare dyad linkage.** `PostpartumCareServiceImpl` already models a mother+newborn dyad but the linkage to the existing `NewbornAssessment` row is by patient_id + date heuristic. **Needs:** explicit FK on `PostpartumCare.newbornAssessmentId`.
- **PostpartumCare → routine-immunization scheduler.** Postpartum visits should auto-enqueue the BCG / hep-B / OPV-0 immunization records via `ImmunizationService`. Today the linkage is manual; the receptionist re-enters.

#### Missing surfaces

- **Antepartum / partogram capture.** The `HighRiskPregnancyCarePlan` covers ANC visits but the WHO partogram (latent + active phases, contractions, dilation, station, fetal heart rate) is not modeled. Big enough for its own foundation pass.
- **Postpartum-hemorrhage (PPH) emergency surface.** Hooks into the existing `BPA_PROTOCOLS` row (row 64) — `OB_HEMORRHAGE` seed already exists but no postpartum-specific UI affordance.
- **Pediatric immunization schedule projector.** Given a newborn's DOB + (optional) gestational age + country, project the EPI schedule (BCG, OPV-0, OPV-1, penta-1, etc.). Today the immunization records exist but the schedule is operator-driven.

#### Audit + privacy

- `NewbornAssessmentServiceImpl` PHI columns are not yet on the encryption list (`EncryptedStringConverter`). **Needs:** verify newborn name / mother's contact / address are encrypted; if not, add to the P0 HIPAA-gap backlog (row 38).

### Frontend (`hospital-portal/`)

- **`<app-pregnancy-care-plan>`** — partially shipped under `obgyn/` but missing the visit-schedule projection panel (consumes the existing service surface) and the risk-factor multiselect in EN / FR / ES.
- **`<app-newborn-assessment>`** — APGAR capture exists; missing the feeding-plan, screening-flag, and discharge-readiness sub-components.
- **`<app-postpartum-dyad>`** — present in v0 (lactation + EPDS) but missing the contraception-counselling slice and the routine-immunization-schedule embed.

### Tests

- Existing tests on the three services cover CRUD shape; cross-service workflow scenarios are absent. **Needs:** end-to-end IT for the high-risk-pregnancy → delivery → newborn-assessment → postpartum-care happy path, asserting the FKs propagate.
- Frontend a11y axe-core smoke on each new component (per the existing `axe-core/playwright smoke` row 9 gate).

---

## Why this row stays `started`

The roadmap target is "complete the partially-designed services and matching UI". Today's audit identifies **three clinical surfaces** (antepartum/partogram, PPH emergency, pediatric immunization scheduler) and **three backend integrations** (pregnancy→newborn, newborn→postpartum, postpartum→immunizations) and **three UI completion gaps** — that's nine deliverable follow-on PRs, plus encryption audit + cross-service ITs.

Each follow-on is its own foundation-pass-shaped PR; bundling them risks landing a clinical surface without its UI or vice versa. The row flips to `completed` when:

1. All three cross-service FKs land (one PR each is fine).
2. All three new clinical surfaces ship at foundation-pass quality (one PR each).
3. The three frontend gaps close.
4. Encryption audit closes any newly-flagged PHI columns.
5. The cross-service happy-path IT passes against seeded data.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/service/HighRiskPregnancyCarePlanServiceImpl.java`
- `hospital-core/src/main/java/com/example/hms/service/impl/NewbornAssessmentServiceImpl.java`
- `hospital-core/src/main/java/com/example/hms/service/impl/PostpartumCareServiceImpl.java`
- `hospital-core/src/main/java/com/example/hms/controller/HighRiskPregnancyCarePlanController.java`
- `hospital-core/src/main/java/com/example/hms/controller/NewbornAssessmentController.java`
- `hospital-core/src/main/java/com/example/hms/controller/PostpartumCareController.java`
- `hospital-portal/src/app/` (per-feature `obgyn/` module) — frontend gap candidates
- Roadmap row 64 (BPA / protocol cards) — `OB_HEMORRHAGE` seed already in `clinical.bpa_protocols`
- Roadmap row 38 (HIPAA-equivalent posture) — drives the encryption audit step
