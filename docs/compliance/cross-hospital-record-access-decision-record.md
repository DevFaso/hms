# Cross-hospital record access — decision record

**Status:** decided 2026-09-08. Revises the access posture recorded in the E8
preamble of `tasklist.md` (2026-09-07). No code shipped with this record; it
sets the shape of E8 #50 and re-scopes #49's second pass.

**What changes:** the default cross-hospital behaviour becomes **announced
availability plus an explicit pull**, not the silent automatic merge the
earlier decision implied. Automatic merge survives, but only for emergency
encounters and for hospitals that have finished classifying their departments.

**What does not change:** the treatment relationship stays the predicate
(#48), posture and patient opt-out stay inside it (#52), sensitive categories
still do not travel (#51), and every cross-hospital read is still accounted
for (#53). Nothing built in #580–#582 is discarded — this changes *when* rows
load, not whether the model is right.

---

## Why the earlier decision needed revising

Three facts, each verifiable in the tree today.

**1. The default is fail-open, and nothing is classified.**
`SensitivityClassifierImpl.travelsCrossHospital` returns `true` when the
effective category is `null` — untagged rows travel. As of 2026-09-08 dev
carries **0 tagged encounters and 0 departments with a default category**.
Under automatic merge, the safety property depends entirely on classification
having been done, and classification is at zero. The feature flag is the only
thing standing between the current state and a bulk disclosure of untagged
psychiatric, HIV and substance-use records. A flag is a deployment control,
not a safety mechanism.

**2. The jurisdiction is French-derived, and the model was American-shaped.**
Epic's Care Everywhere rests on HIPAA's treatment-payment-operations
provision. That provision is American and has no verified Burkinabè
counterpart. The nearest relevant precedent is France's DMP / Mon espace
santé, which resolves the same tension differently: the shared record is
created automatically for everyone (opt-out, not opt-in) **but the patient can
mask individual documents**, and access is differentiated by professional
category. The French answer is not "do not share" — it is "share by default,
with granular patient control". Our design has automatic sharing and a
**binary** opt-out. That asymmetry, not the sharing itself, is the real
distance from the closest precedent.

**3. Degradation matters more than the happy path.**
Under automatic merge, a classification error is a silent disclosure nobody
observes. Under announced-pull, the same error is one deliberate, attributable,
logged access. The second is recoverable and explainable to a patient; the
first is neither.

---

## What Epic actually does, and which half applies to us

Recorded because the earlier decision cited Epic without separating the two
mechanisms, which is the usual source of confusion.

**Same Epic instance (many facilities, one database).** There is no sharing
mechanism. There is one chart. Access is governed by role security, sensitive
category restrictions, break-the-glass and audit. Widespread — a health system
across its own hospitals, and Community Connect hosting independent hospitals
on the same instance.

**Different instances — Care Everywhere.** Query-based federation. Epic does
**not** know in advance where a patient has records. It broadcasts a
*demographic* patient-discovery query to organisations in the networks it
participates in (Carequality, CommonWell, eHealth Exchange, TEFCA), each
organisation matches on its own side, and successful matches are stored as
persistent links. The list of "hospitals this patient has visited" is a
**learned list accumulated from past matches**, not an index. Documents
(historically C-CDA, increasingly FHIR) are then requested per link, and a
clinician **reconciles** outside medications, problems and allergies into the
local chart rather than having them silently merged.

**We are the first case.** One shared database; `PatientHospitalRegistration`
is ground truth, not inference. We have no patient-matching problem — the most
error-prone part of Care Everywhere does not exist for us. What we lack is
Epic's clinician-mediated reconciliation step: our rows would simply appear.
The announced-pull model restores a deliberate human act in the place where
Epic puts reconciliation.

---

## The decision

### Default posture: announced availability, explicit pull

When a clinician opens a chart, the chart states that records exist elsewhere
and how much — **it does not load them**:

> Ce patient a des dossiers à l'Hôpital Saint-Camille — 3 consultations,
> dernière le 12 mars 2026. **Charger les dossiers**

The availability banner carries no PHI: hospital, count, most-recent date. One
click loads the rows and writes the `RECORD_SHARE` disclosure row.

Rationale: this works **today, at zero classification**, because safety comes
from the access being deliberate and attributable rather than from every
sensitive row having been tagged first. It also keeps nearly all of the
clinical value — what changes outcomes is a clinician *knowing* records exist;
fetching is one click behind that.

### Carve-out: emergency encounters merge automatically

Where the encounter is an emergency, foreign rows merge into the chart with no
interstitial. Friction is harm in an urgence, the treatment relationship is
unambiguous, and emergency access to health data has explicit standing in
comparable regimes (GDPR Article 9(2)(c), vital interests, is the analogue).
This is a distinction the law itself draws, not a convenience compromise.

### Graduation: posture moves a hospital to automatic merge

`RecordAccessPosture` (V157) already carries the switch. A hospital that has
classified its departments may be moved to full automatic merge. The path from
cautious to seamless is configuration, not a rewrite — which was the point of
building #52 before #49.

---

## UI contract (E8 #50)

Every row surfaced from another hospital MUST display, at the row, without
requiring a click:

| Field | Source | Status |
| --- | --- | --- |
| Hospital name | `metadata.sourceHospitalName` | shipped (#582) |
| Staff name | per-category, see below | partial |
| Date | `occurredAt` (DTO field) | shipped |
| Treatment / what happened | `summary` + `category` | shipped |

Provenance must be legible on the row itself, not in a tooltip or a details
pane. A clinician must never have to work out whether what they are reading
came from their own hospital.

**Staff name by category — current state:**

| Category | Metadata key | Status |
| --- | --- | --- |
| Encounter | `clinician` | present |
| Imaging order | `orderedBy` | present |
| Imaging report | `scanPerformedBy`, `reportFinalizedBy` | present |
| Surgical history | `performedBy` | present |
| **Prescription** | — | **missing**; available as `Prescription.staff` |
| **Lab result** | — | **missing**; available as `LabResult.releasedByDisplay` |

Both gaps are fillable from existing entity fields. **No migration required.**

**The blocking defect:** the portal's `TimelineEntry` interface
(`hospital-portal/src/app/services/patient.service.ts:222`) declares no
`metadata` field at all. The backend serialises provenance on every row and the
portal discards it. Until that type carries `metadata`, none of the above can
render.

---

## Order of work

1. **Repair the access-control layer first.** `CustomUserDetailsService`
   builds authorities from role codes only, so **235 `hasAuthority(...)`
   clauses across 25 distinct permission names are permanently false** and
   every `hasAuthority(X) or hasAnyRole(...)` guard silently collapses to its
   role list. `PermissionCatalog` drives what the *dashboard shows* while roles
   drive what the *API allows* — the portal offers what the backend refuses. A
   considered access model cannot sit on a layer that is not evaluating what it
   appears to evaluate. Note that wiring the catalogue into authorities would
   **widen access across 235 guards at once**; that is a security change, not a
   cleanup, and needs its own review.
2. **Classify departments, not rows.** `effectiveCategory` already falls back
   to the department default. Departments are countable; rows are not. This is
   the highest-leverage classification move available and a clinician can
   complete it in an afternoon.
3. **Invert the default to fail-closed.** A row should travel only if its
   department has been explicitly cleared, rather than travelling because
   nobody tagged it. One line in `travelsCrossHospital`, large safety payoff.
   Sequenced after step 2 so the change does not simply stop all sharing.
4. **#50 — the portal**, building the availability banner rather than the
   silent merge, plus the provenance contract above.
5. **#54 — break-the-glass**, which matters more once reach grows.

---

## Do not rely on the keyword heuristic

`PatientServiceImpl.SENSITIVE_KEYWORDS` / `SENSITIVE_DEPARTMENTS` substring-match
**English** literals against free text and department names at read time. In a
French deployment « psychiatrie », « VIH » and « toxicomanie » match nothing.
Dev carries only four departments, all "General Practices", so the path has
apparently never fired. It is not a safety net; it is a defect awaiting
deletion once the tag is populated (#49's second pass).

---

## Open — not resolved by this record

- **Legal sign-off remains a prerequisite, not a follow-up.** Counsel and the
  CIL must confirm whether treatment-purpose access without patient
  authorisation is lawful in Burkina Faso. Announced-pull is deliberately
  chosen to be defensible under a stricter reading, but it is not a substitute
  for the answer. Copying Epic's behaviour is not evidence of lawfulness in a
  jurisdiction Epic's legal basis does not reach.
- **Granular patient masking** (the DMP's document-level control) is not built.
  The opt-out is binary. If counsel reads Burkinabè law as DMP-shaped, this
  becomes required rather than optional.
- **Reconciliation.** Epic has clinicians accept outside medications, problems
  and allergies into the local record. We surface rows directly. Whether an
  acceptance step is wanted is undecided.
- **Allergies, imaging and surgical history cannot cross** — they attach to
  patient + hospital with no encounter link, so their category is unresolvable
  and they are withheld by construction. Allergies crossing is arguably the
  single highest-value safety item in the whole epic; resolving it needs a
  category source those rows do not currently have.
