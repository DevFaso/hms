# HMS — Epic-Parity Gap Analysis (West Africa Context)

**Branch:** `epic-alignment-2026-04-28`
**Date:** 2026-04-28
**Scope:** Audit of `hospital-core` (Spring Boot 3.4 / Java 21) and `hospital-portal` (Angular 20) against a real Epic-class EHR, adapted for the realities of deployment in West Africa.

---

## West Africa Deployment Realities (drives every priority decision)

| Reality | Implication for design |
|---|---|
| Intermittent internet, frequent power loss | Offline-first / queue-and-forward; graceful degradation; small payloads. |
| Mobile-first clinicians on cheap Android | UI must work on low-RAM devices, slow 3G/4G; SSR for first paint. |
| Anglophone + Francophone users | i18n is non-negotiable (EN/FR/local) — already partially in place. |
| Mobile-money payments dominate (Orange Money, MTN MoMo, Wave) | Already supported in pharmacy checkout (`Espèces / Mobile Money / Assurance`). Extend to billing. |
| HIE/registries are DHIS2, OpenMRS, OpenHIE — **not** Epic Care Everywhere | FHIR R4 must align with **WHO SMART Guidelines** and **OpenHIE** profiles, not just Epic. |
| ICD-10 + WHO ICD-11 + LOINC are common; SNOMED CT licensing is restrictive | Bind labs to **LOINC**, problems to **ICD-10/11**, meds to **RxNorm/WHO ATC**. SNOMED only where free. |
| State immunization registries via DHIS2 Tracker | Immunization HL7 v2 VXU is wrong target; use FHIR `Immunization` + DHIS2 ADX. |
| Lab analyzers are mid-range (Mindray, Sysmex) speaking ASTM/HL7 v2 over serial or TCP | Need MLLP TCP listener; sometimes ASTM bridge. |
| USSD/SMS for patient communication (limited smartphone reach in rural areas) | Keep Twilio + add Africa's Talking; SMS appointment reminders, lab-ready notices. |
| Stockouts, counterfeit drugs, donor-funded supply chains | Pharmacy already strong; add lot/expiry tracking + GS1 barcode (already partial). |
| Limited radiology — frequently no PACS, just standalone US/X-ray rooms | DICOM/PACS optional; image-attachment workflow already good. |
| HIPAA does not apply, but local data-protection laws do (Nigeria NDPR, Senegal CDP, Ghana DPC, OHADA) | PHI encryption already in place; add data-residency flag per organization. |

These shift the "Epic parity" target. We are not building an Epic clone; we are building an **OpenHIE-compatible, FHIR-native, mobile-first EHR with Epic-class clinical safety and workflow patterns**.

---

## What the system already does well

- Multi-tenant RBAC (24 roles), Keycloak/OIDC, MFA/TOTP, JWT blacklist, rate limiting.
- 108 controllers, ~104 entities, ~280 services. Layered architecture is followed.
- EMPI (master patient index, merge events).
- OB/GYN suite (maternal Hx, birth plan, newborn assessment, postpartum, high-risk).
- Lab system: orders, results, QC events, instrument outbox, reflex rules, validation studies, approval queue.
- Pharmacy: catalog, inventory, dispense, MTM, claims, stock routing, French printable receipt with mobile-money options.
- Audit log, digital signatures, consent management, record sharing (Care-Everywhere-like).
- Patient portal (MyChart-like) — 13 routes covering meds, results, vitals, billing, messaging, appointments.
- In Basket, Patient Tracker (kanban with 30s polling), nurse station triage.
- Kafka, WebSocket (STOMP), Twilio SMS, email.
- ngx-translate EN/FR/ES, Angular 20 signals, Material 20.
- Liquibase migrations, Grafana Cloud (OTLP + Faro RUM), Railway CI/CD.

---

## Gap List (24)

### P0 — Interoperability blockers (start here)
| # | Gap | West-Africa-specific note |
|---|---|---|
| 1 | **No FHIR R4 REST API** — no HAPI-FHIR, no `/fhir/*` endpoints, no `CapabilityStatement`. | Required for OpenHIE / DHIS2 ADX / OpenMRS sync. Anchor for everything below. |
| 2 | **No HL7 v2 MLLP TCP listener** — only a REST adapter for ORU^R01. | Real labs (Mindray, Sysmex, Roche) speak MLLP. Common in regional referral hospitals. |
| 3 | **No CDS Hooks** — no `patient-view`, `order-sign`, `order-select`. | Used for malaria-protocol BPAs, drug-allergy alerts, pediatric dose checks — high impact in resource-limited care. |
| 4 | **No SMART-on-FHIR launch** — no app-launch context, scopes, OAuth2 SMART flow. | Lower priority; defer until #1 lands. |

### P1 — Clinical safety & terminology
| # | Gap | West-Africa-specific note |
|---|---|---|
| 5 | No SNOMED / **LOINC / ICD-10 / RxNorm-WHO ATC** terminology binding. `PatientProblem.problemCode` is freetext; `LabTestDefinition` lacks LOINC; meds lack validated RxNorm. | Use LOINC + ICD-10/11 + WHO ATC (SNOMED licensing is hard for African deployments). |
| 6 | No CPOE order-set engine. `AdmissionOrderSet` exists but has no template builder, versioning, or order-to-prescription pipeline. | Critical for malaria, sepsis, OB hemorrhage protocols. |
| 7 | No drug-allergy / drug-drug interaction check at order time. `PrescriptionAlert` exists but no rule engine fires before signing. | High patient-safety win. |
| 8 | No structured clinical documentation. Encounter/nursing notes are plain `textarea`; no Storyboard sidebar, no SmartPhrases, no HPI/ROS/PE/A+P templates, no rich-text editor. | Templates can be in French/English; reduces typing time on tablets. |

### P1 — Workflow modules
| # | Gap | West-Africa-specific note |
|---|---|---|
| 9 | No surgical scheduling (OpTime). `Appointment` is outpatient-only; no OR block time, surgical team, anesthesia. | Useful for tertiary hospitals; lower priority for district-level. |
| 10 | No inpatient pharmacy / eMAR. Pharmacy is retail-style; no MAR queue → barcode-scan administration loop (Willow). | Inpatient med safety; high value for referral hospitals. |
| 11 | No prior-auth / eligibility / claims (270/271, 278, 837/835). | Less relevant — most payments are out-of-pocket or NHIS/CNAMGS card. Replace with **NHIS/mutuelle eligibility check** workflow. |
| 12 | No telehealth — no video encounter type, link generation, async messaging. | High impact for rural reach; favor low-bandwidth (audio + photo upload) over HD video. |
| 13 | No referral authorization & tracking lifecycle. `GeneralReferral` lacks accept/decline/complete states. | Critical for district→regional→tertiary referral chain. |
| 14 | No immunization registry interface. `PatientImmunization` exists; no link to state IIS. | Use **FHIR `Immunization` + DHIS2 Tracker ADX**, not HL7 v2 VXU. |

### P2 — Frontend Epic UX
| # | Gap | West-Africa-specific note |
|---|---|---|
| 15 | No persistent Storyboard patient banner (allergies, problems, code status, active encounter). | High clinical-safety impact; cheap to build. |
| 16 | No Chart Review tabbed viewer (Encounters / Notes / Results / Meds / Imaging / Procedures with timeline). | |
| 17 | No Cadence visual scheduling grid (FullCalendar / multi-resource block calendar). | |
| 18 | No Best Practice Advisory pop-ups at point of care. | Pair with #3 CDS Hooks. |
| 19 | No order catalog with synonyms / smart defaults / dose ranges. | Pair with #6 order sets. |
| 20 | Patient Tracker polls every 30s; should push via existing WebSocket plumbing. | Saves bandwidth on metered connections. |

### P2 — Compliance & ops
| # | Gap | West-Africa-specific note |
|---|---|---|
| 21 | No break-the-glass emergency access workflow. `AuditEventLog` covers normal access only. | Aligns with NDPR/CDP "lawful basis" audit. |
| 22 | Granular consent scopes missing. `PatientConsent` lacks scope (treatment vs research vs disclosure), revocation history, secondary-use rules. | Required for research consortia (H3Africa). |
| 23 | No DICOM/PACS link — imaging stores metadata only; no WADO/DICOMweb endpoints. | Optional for district level. |
| 24 | No e-prescribing — internal pharmacy only. | Surescripts is US-only; instead integrate with **community pharmacy SMS routing** (already partially done in `PartnerSmsWebhookController`). |

---

## Recommended Implementation Sequence

Each numbered item below should ship as one PR, additive only, with backend + frontend + Liquibase + tests in the same PR per `agent/coordination.md`.

1. **FHIR R4 read API** (Patient, Encounter, Observation, Condition, MedicationRequest, DiagnosticReport, Immunization) + `CapabilityStatement` + Bundle search. HAPI-FHIR plain-server style. *Unlocks #3, #4, #14.*
2. **LOINC binding on `LabTestDefinition`** + **ICD-10 binding on `PatientProblem`** + **WHO ATC/RxNorm on `MedicationCatalogItem`**. Additive Liquibase migrations + DTO + UI fields. *Unlocks #1's data quality and #3's rules.*
3. **HL7 v2 MLLP TCP listener** (Spring Integration) for ADT^A01/A04/A08 and ORU^R01. Bridges existing REST adapter to a real port. Configurable per-hospital.
4. **CDS rule engine** firing on prescription create + order entry (drug-allergy, drug-drug, duplicate orders, pediatric dose). Backend service emitting BPA events; Angular advisory dialog.
5. **Storyboard patient banner** component on every chart route (problems, allergies, active encounter, code status).
6. **Chart Review tabbed component** consolidating existing endpoints into one Epic-style tab strip.
7. **Order set builder + CPOE picker** with synonyms, dose ranges, frequency templates. Targets malaria/sepsis/OB protocols.
8. **Cadence-style visual scheduling grid** (FullCalendar) for appointments and OR blocks.
9. **Inpatient eMAR** with barcode scan administration loop; reuse pharmacy + MAR entities.
10. **Break-the-glass workflow** with reason capture + audit, and **granular consent scopes**.
11. **Telehealth low-bandwidth mode** (audio + photo + chat) reusing existing chat module.
12. **DHIS2 ADX export** for immunization, ANC, malaria reporting (ties to FHIR `Immunization`).

---

## P0 — Shipped (2026-04-28)

All four interoperability blockers landed in PR [#139](https://github.com/DevFaso/hms/pull/139),
promoted via [#140](https://github.com/DevFaso/hms/pull/140) (develop → uat) and
[#141](https://github.com/DevFaso/hms/pull/141) (uat → main).

| # | Item | Code | Docs |
|---|---|---|---|
| 1 | FHIR R4 read API (Patient, Encounter, Observation, Condition, MedicationRequest, Immunization) | `hospital-core/src/main/java/com/example/hms/fhir/` | [`docs/fhir.md`](../docs/fhir.md) |
| 2 | HL7 v2 MLLP TCP listener (off by default; bounded thread pool; ORU^R01 + ADT^A01/A04/A08) | `hospital-core/src/main/java/com/example/hms/hl7/mllp/` | [`docs/hl7-mllp.md`](../docs/hl7-mllp.md) |
| 3 | CDS Hooks 1.0 (`hms-patient-view`, `hms-medication-allergy-check`) | `hospital-core/src/main/java/com/example/hms/cdshooks/` | [`docs/cds-hooks.md`](../docs/cds-hooks.md) |
| 4 | SMART-on-FHIR App Launch 1.0 (well-known config + CapabilityStatement OAuth extension) | `hospital-core/src/main/java/com/example/hms/fhir/smart/` | [`docs/smart-on-fhir.md`](../docs/smart-on-fhir.md) |

Quality gates that passed on PR #139:

- 13/13 GitHub CI checks (Backend JUnit + Jacoco @ 80% threshold, Frontend lint+format+headless, CodeQL ×3, agent prompt tests, dockerfile/yaml lint)
- SonarCloud quality gate, 0 PR issues after iterative cleanup (26 → 2 → 0)
- 17 new backend tests across `fhir`, `hl7.mllp`, `cdshooks` packages

## P1 — Active queue

Same numbering as the gap list above. Priority is top-down; each ships as one PR.

1. **Terminology binding** (gap #5) — LOINC on `LabTestDefinition`, ICD-10/11 on `PatientProblem`, WHO ATC + RxNorm on `MedicationCatalogItem`. Additive Liquibase + DTO + UI. *Unblocks deeper FHIR semantics and rule-based CDS.*
2. **MLLP / FHIR persistence** — wire ORU^R01 → `LabResult` and ADT → `Encounter`/`Patient` projections through EMPI. Currently the MLLP listener acks + logs only.
3. **CDS rule engine** (gap #3 expanded) — drug-drug, duplicate-order, pediatric-dose checks on top of the new terminology bindings.
4. **CPOE order-set builder** (gap #6) — versioned templates for malaria, sepsis, OB hemorrhage protocols.
5. **Storyboard patient banner** (gap #15) — persistent header (allergies / problems / active encounter / code status) on every chart route.
6. **Chart Review tabbed component** (gap #16) — consolidate existing endpoint data into the Epic-style tab strip.
7. **Cadence visual scheduling grid** (gap #17) — FullCalendar multi-resource block view.
8. **Inpatient eMAR** (gap #10) — barcode-scan administration loop, reusing the pharmacy + MAR entities.
9. **Break-the-glass workflow** (gap #21) + **granular consent scopes** (gap #22).
10. **Telehealth low-bandwidth** (gap #12) — audio + photo + chat on top of the existing chat module.
11. **DHIS2 ADX export** (gap #14) — immunization, ANC, malaria reporting tied to FHIR `Immunization`.
12. **Referral lifecycle** (gap #13) — accept/decline/complete states on `GeneralReferral`.

P2 items (#9 OpTime, #11 prior-auth, #18 BPA pop-ups UX, #19 order catalog UX, #20 push tracker, #23 DICOM/PACS, #24 e-prescribing) remain on the backlog and are expected to be picked up after P1 #1–#5 land.
