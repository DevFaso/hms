# e-Keneya (HMS) — Web tester engagement brief

*Audience: an external manual QA tester hired to test the staff web portal.*
*Owner: Bitnest Technologies. Last updated: 2026-09-07.*

---

## 1. What you are testing, in one paragraph

e-Keneya is a hospital management system: one shared patient record used by every
service of a health facility — reception, consultation, laboratory, imaging,
pharmacy, wards, billing — plus self-service pages for patients. It is
**multi-hospital**: a single deployment serves many facilities, and each facility
must only ever see its own data unless the patient has explicitly consented to
share. There are 30+ staff roles, each with its own menu and its own permissions.
The interface exists in **French, English and Spanish**.

Your job is to prove that the clinical journeys below work end to end, and — most
importantly — that **one hospital cannot see another hospital's records unless it
is supposed to**.

---

## 2. Scope

**In scope**

- The staff web portal (all roles), in a browser, on desktop and on mobile widths.
- The patient self-service pages of the same web portal (`/my-*` routes).
- The three interface languages.

**Out of scope unless separately agreed in writing**

- The patient Android and iOS applications (separate engagement, separate skills).
- The machine-to-machine interfaces (FHIR, HL7 v2, CDS Hooks, DHIS2 export).
- Load, stress and penetration testing. **Do not run automated scanners or load
  tools against any environment without written approval.**

---

## 3. Environments

| Environment | Portal | API | Use it? |
|---|---|---|---|
| **Dev** | `https://dev.e-keneya.com` | `https://api.dev.e-keneya.com` | **Yes — all testing happens here** |
| **Production** | `https://e-keneya.com` | `https://api.e-keneya.com` | **Never.** Real patients, real records. |

> **Before handover:** confirm the exact dev hostnames actually deployed, and confirm
> the tester's IP/account is allowed. The values above are the configured defaults
> (`app.frontend.base-url`), not a promise about what is currently running.

---

## 4. Accounts — provision these before day one

**The web portal is staff-only. There is no public self-registration** — the
registration endpoint deliberately returns `410 Gone`. Every account, including
patient portal accounts, is created by an administrator. Plan for this: a tester
who cannot create their own accounts is a tester who cannot start.

You must hand over working credentials for at least:

| # | Role | Why the tester needs it |
|---|---|---|
| 1 | `SUPER_ADMIN` | Create hospitals, users, feature flags, audit search |
| 2 | `HOSPITAL_ADMIN` at **Hospital A** | Departments, beds, slots, order sets, consent admin |
| 3 | `HOSPITAL_ADMIN` at **Hospital B** | The other side of every cross-hospital test |
| 4 | `RECEPTIONIST` (A) | Registration, check-in, walk-in, waitlist |
| 5 | `DOCTOR` (A) | Consultation, orders, prescriptions, referrals |
| 6 | `SURGEON` (A) | Procedure orders, pre-op clearance |
| 7 | `NURSE` (A) | Nurse station, eMAR, vitals, handoff |
| 8 | `MIDWIFE` (A) | Maternity, labour, obstetric referrals |
| 9 | `LAB_TECHNICIAN` + `LAB_SCIENTIST`/`LAB_MANAGER` (A) | Specimen → result → approval queue |
| 10 | `RADIOLOGIST` (A) | Imaging orders and reports |
| 11 | `PHARMACIST` (A) | Dispensing, stock, checkout |
| 12 | `BILLING_SPECIALIST` or `CASHIER` (A) | Invoices, payments |
| 13 | `PATIENT` — one patient known at **both** A and B | The cross-hospital record suite |
| 14 | `PATIENT` — a second, unrelated patient | Negative tests (must never see #13's data) |
| 15 | **A doctor assigned to both A and B** | The hospital scope picker tests (X10) |

**Two blockers to settle before the tester starts:**

1. **Email delivery.** Verification links, account activation, appointment
   confirmation and reschedule/cancel links are all sent by email
   (`MAIL_USER` / `MAIL_PASS`). If no mail transport is configured on dev, those
   journeys cannot be tested at all. Either point dev at a test mailbox
   (recommended) or declare J2b and account activation out of scope in the contract.
2. **Test data.** The synthetic data seeder only runs on local developer profiles,
   not on the shared dev environment. Either seed dev deliberately, or accept that
   the tester's first day is spent creating patients, departments, beds and
   catalogue entries by hand — and budget for it.

---

## 5. The clinical journeys

Each journey below is one test charter. The tester should report, per journey:
what passed, what failed, and the evidence (screenshot + URL + time + account used).

### J1 — Front door: registration and check-in
**Roles:** receptionist · **Where:** `/reception`, `/patients`, `/registrations`

Register a brand-new patient; register a walk-in; check in a patient who has an
appointment; add someone to the waitlist; use the recall list; open the patient
snapshot drawer. Verify the reception cockpit panels (flow board, waitlist,
recalls, payment pending, insurance issues) reflect what you just did.

*Expect:* the patient exists once, is searchable by name and by record number, and
appears on the flow board with the correct status.
*Traps:* create the same patient twice on purpose — the duplicate must surface as
an EMPI candidate (see X3), not silently create two records.

### J2 — Booking a consultation appointment
**Roles:** receptionist, patient · **Where:** `/appointments`, `/appointments/calendar`, `/my-appointments`

**J2a — staff side:** book from the calendar grid, move an appointment, cancel one,
mark a no-show. Check slot availability rules (`/slot-admin` defines them) and that
double-booking is refused.
**J2b — patient side:** as the patient account, book, reschedule and cancel from
`/my-appointments`. Then follow the **links in the confirmation email**
(`/appointments/reschedule/{id}`, `/appointments/cancel/{id}`) *while signed out* —
they must send you to login and then land on the right appointment with the right
modal open, not on a 404.

*Expect:* statuses move `SCHEDULED → CONFIRMED → CHECKED_IN → COMPLETED`, with
`CANCELLED`, `NO_SHOW` and `RESCHEDULED` reachable from the right places only.
*Traps:* a cancelled or already-rebooked appointment reached from a stale email link
must produce a clear message, not a crash.

### J3 — The consultation
**Roles:** doctor · **Where:** `/encounters`, `/consultations`, `/patients/{id}`

Open the patient chart. Check the storyboard banner at the top (allergies, active
problems, last visit, resuscitation status). Walk the encounter through
`ARRIVED → TRIAGE → WAITING_FOR_PHYSICIAN → IN_PROGRESS → AWAITING_RESULTS →
COMPLETED`. Write a note, add a diagnosis with an ICD-10 code, record vitals,
add an allergy and a problem.

*Expect:* invalid status jumps are refused by the server, not just hidden in the UI.
*Traps:* an invalid ICD-10 code must be rejected at entry.

### J4 — Orders, prescriptions and the safety checks
**Roles:** doctor, pharmacist · **Where:** `/prescriptions`, `/admin/order-sets`, `/pharmacy/drug-interactions`

Prescribe. Then deliberately try to break the safety net:
- two drugs that interact — a critical pair must **block** signature and demand a
  documented override reason;
- a patient under 18 with a dose above the mg/kg ceiling;
- the same lab test or drug ordered twice (duplicate warning);
- a drug the patient is recorded as allergic to.

Apply an order set (`/admin/order-sets`) to an admission and confirm every
prescription, lab and imaging order it contains is created **in one go** — and that
each medication still goes through the checks above.

*Expect:* every override is recorded with its reason and appears in the audit trail.

### J5 — Laboratory
**Roles:** doctor, lab technician, lab scientist/manager · **Where:** `/lab`, `/lab-results`, `/lab-approval-queue`, `/in-basket`

Order a test → collect/register the specimen → enter a result → send it through the
approval queue → confirm the ordering doctor is notified in `/in-basket`. Enter an
out-of-range value and a critical value; check the abnormal flagging and the
escalation of a critical result nobody has acknowledged. Also cover
`/microbiology`, `/lab-qc-dashboard` and `/lab-inventory` if they are in the agreed scope.

*Expect:* a result cannot be released without passing validation; the patient sees
it in `/my-lab-results` only once released.

### J6 — Imaging
**Roles:** doctor, radiologist · **Where:** `/imaging`

Order imaging, produce a report, attach an image, move the report through its
statuses, confirm the ordering clinician sees it and the patient does not see a
draft.

### J7 — The surgical / inpatient pathway
**Roles:** surgeon, nurse, doctor · **Where:** `/procedure-orders`, `/admissions`, `/bed-board`, `/emar`, `/transfusions`, `/discharge`

> **Read this before you scope it.** There is **no operating-theatre scheduling
> board and no anaesthesia record** in the product. The surgical pathway is modelled
> as: a **procedure order** with statuses `ORDERED → SCHEDULED →
> PRE_OP_CLEARANCE_PENDING → READY_FOR_PROCEDURE → IN_PROGRESS → COMPLETED`
> (plus `CANCELLED`, `POSTPONED`), an **admission** to a bed in a `SURGICAL` ward,
> an encounter of type `SURGERY`, medication administration at the bedside, optional
> **transfusion**, then **discharge**. Test that pathway — do not send a tester
> hunting for a theatre module that does not exist.

Steps: raise a procedure order → pre-op clearance → admit the patient and assign a
bed on `/bed-board` → administer medication through `/emar` (see below) → raise and
fulfil a transfusion request → request discharge approval → produce the discharge
summary → confirm the bed is released.

**eMAR five rights:** at `/emar`, verify right patient, right drug, right dose,
right route, right time (±60 min window). Break each one in turn — each failure must
demand an override reason before the administration is recorded.

### J8 — Pharmacy, stock and money
**Roles:** pharmacist, cashier/billing · **Where:** `/pharmacy/*`, `/billing`, `/my-billing`

Dispense against a real prescription; watch stock decrease; receive goods; make a
stock adjustment; check expiry alerts; run `/pharmacy/checkout`; produce an invoice;
confirm the patient sees it in `/my-billing` and `/my-pharmacy-invoices`.

*Known limitation:* mobile-money payment runs on a **mock adapter that always
succeeds**. Test the flow, but do not report "no real payment happened" as a defect.

### J9 — Referral lifecycle
**Roles:** midwife/doctor (sender), specialist (receiver) · **Where:** `/referrals`

Create → submit → acknowledge → schedule → start → complete, with an attachment.
Also test the refusal path (`REJECTED`) and cancellation. Try to jump straight from
`DRAFT` to `COMPLETED` — the server must refuse.

### J10 — Privacy, consent and audit
**Roles:** doctor, hospital admin, patient · **Where:** `/consent-management`, `/audit-logs`, `/roi`, `/my-sharing`, `/my-records`

Grant and revoke consent per data domain; use a break-glass emergency access;
raise and fulfil a release-of-information (ROI) request; check the audit log shows
who opened which record and when; check the patient's own "who viewed my records"
view. Detailed cases are in section 6.

---

## 6. The cross-hospital medical record suite

This is the part that matters most, and the part a generic tester will not think of.

**Setup:** Hospital **A** and Hospital **B**. One patient — *the same human being* —
known to both. One doctor at A, one doctor at B, one doctor assigned to both.

| # | Test | What must happen |
|---|---|---|
| **X1** | **Isolation (the critical negative test).** Doctor at B searches for the patient and opens everything reachable. | B sees **nothing** created at A: no consultations, no results, no prescriptions, no documents. Default is *not shared*. |
| **X2** | **Multi-hospital registration.** Register the same patient at B via `/registrations`. | The patient is registered at both, each with its own record number, without merging clinical data. |
| **X3** | **Duplicate detection (EMPI).** Create a near-duplicate (same name, same date of birth, slight spelling difference). | It surfaces in `/reception/empi-candidates`; a merge links the identities; after merge, aliases still resolve and nothing is lost. |
| **X4** | **Consent grant, domain by domain.** At A, grant consent from A to B for one domain only (e.g. lab results). | B sees **that domain only**. Everything else stays invisible. |
| **X5** | **Sensitive domains.** Try the same with mental health, HIV status, substance use, genetics. | These require explicit, separate authorisation — a general consent must not open them. |
| **X6** | **Shared records viewer.** At B, open `/consent-management/shared-records`. | Shows exactly what was shared, labelled with the source hospital. |
| **X7** | **Revocation.** Revoke the consent at A, then refresh at B. | Access disappears immediately — including on a page B already had open. |
| **X8** | **Referral across hospitals.** Refer the patient A → B with attachments. | B receives the referral and its attachments **without** gaining access to the whole record. |
| **X9** | **Break-glass.** At B, with no consent, declare an emergency and force access. | Access is granted, **time-boxed** (15 min floor, 4 h ceiling), every read counted, and the session appears in the hospital admin's auditable list. |
| **X10** | **Scope switching.** Sign in as the doctor assigned to both, switch hospital with the scope picker on every scoped page. | The whole page swaps to the other hospital's data — no stale rows, no stuck spinner, no mixing. Reload and confirm the choice survives. |
| **X11** | **URL tampering (IDOR).** While scoped to B, take an ID that belongs to A — patient, encounter, lab order, invoice, document — and request it directly by URL. | **403 or 404 every time.** A single leak here is a critical defect. Repeat against the API with the browser dev tools. |
| **X12** | **Audit completeness.** After X1–X11, review `/audit-logs` and the patient's own record-access view. | Every access above appears, with the right actor, hospital, patient and time. Break-glass and ROI fulfilment show as disclosures. |

**X11 deserves its own budget.** Ask the tester to spend at least a full day only on
tampering: change IDs in URLs, replay requests from one hospital's session against
another's objects, and try every "download" and "export" link. This is where
multi-tenant systems fail.

---

## 7. Cross-cutting passes

| Pass | What to check |
|---|---|
| **Permissions matrix** | For each role, every menu item it should and should not see. Then try the forbidden URLs directly — the UI hiding a link is not access control. |
| **Languages** | Switch French / English / Spanish on every screen touched. No missing translations, no English leaking into French, dates and numbers formatted correctly. |
| **Accessibility** | Keyboard-only navigation, visible focus, screen-reader labels on modals and pickers, colour contrast. |
| **Responsive** | Phone and tablet widths — the product is used on phones at the bedside. Nothing must scroll horizontally. |
| **Poor connectivity** | Throttle to slow 3G and go offline mid-action. Loading, empty and error states must all be present and honest; no silent failures. |
| **Session & MFA** | Enrol MFA, challenge, backup codes, idle lock screen, session expiry, password reset, first-login account setup. |
| **Empty & error states** | A brand-new hospital with no data at all: every page must say something useful rather than showing a blank panel or a stuck spinner. |
| **Downtime mode** | `/downtime` — the documented degraded-operation path. |

---

## 8. How to report a defect

One defect per report. Required fields:

```
Title:        short, factual, no adjectives
Environment:  dev · browser + version · desktop/mobile
Account:      role + hospital used (never paste the password)
URL:          the exact address where it happened
Steps:        1. … 2. … 3. …
Expected:     what should have happened
Actual:       what happened
Evidence:     screenshot or short recording; browser console + network tab if it errored
Time (UTC):   so we can find it in the server logs
Severity:     see below
```

| Severity | Meaning |
|---|---|
| **Critical** | Data of one hospital or patient visible to another; data loss; login broken; a safety check bypassed |
| **High** | A journey cannot be completed; wrong clinical or financial data displayed |
| **Medium** | Journey completes with a workaround; wrong status, wrong label, broken filter |
| **Low** | Cosmetic, wording, alignment, missing translation |

---

## 9. Rules of engagement

1. **Dev environment only.** Never sign into production, even to "just look".
2. **No real patient data.** Invent names, dates of birth, phone numbers. Never
   enter a real person's medical information, including your own.
3. **Credentials are personal.** No sharing, no committing them anywhere, no
   screenshots that show a password field's contents.
4. **No automated scanners, crawlers or load tools** without written approval.
5. **Destructive tests** (bulk deletion, merges, tenant-wide changes) get announced
   before they run, so nobody else's session is wrecked mid-test.
6. Everything seen during the engagement is confidential.

---

## 10. Known limitations — do not report these as defects

| Area | Status today |
|---|---|
| Operating theatre scheduling / anaesthesia record | Not built. The surgical pathway is procedure orders + admission + eMAR + discharge (see J7). |
| Mobile-money payment | Mock adapter only; it always succeeds. Real operator connection happens at deployment. |
| FHIR API | Read-only. |
| HL7 MLLP sender allowlist | Backend only; no admin screen yet. |
| Referral expiry scheduler | Deliberately deferred; expired referrals are handled manually. |
| Email/SMS on dev | May be unconfigured — confirm before testing anything that sends a message. |

---

## 11. Day-one handover checklist

- [ ] Confirmed dev portal and API URLs, reachable from the tester's location
- [ ] The 15 accounts in section 4, with first-login instructions
- [ ] Two hospitals (A and B) existing on dev, with departments, wards, beds and slots
- [ ] A drug catalogue and a lab test catalogue with enough entries to prescribe and order
- [ ] Decision made on email transport (configured, or those journeys excluded)
- [ ] This brief, plus a defect tracker the tester can write to
- [ ] A named contact who can unblock accounts within one working day
- [ ] Signed confidentiality agreement

---

## 12. Notes for the hire

**Look for:** manual QA experience on a role-based business application; comfort
with browser dev tools (network tab, editing a request); disciplined, reproducible
bug reports. Healthcare or any multi-tenant SaaS background is a real advantage —
they will already understand why X1 and X11 matter. Automation (Playwright) is a
bonus, not a requirement, for a first pass.

**Ask a candidate this:** *"An application shows a user only their own company's
records. How would you convince yourself that another company's records are truly
unreachable?"* A good tester will immediately talk about bypassing the UI and
addressing objects directly. That is exactly test X11.

**Rough effort**, one tester, assuming accounts and test data are ready on day one:

| Phase | Days |
|---|---|
| Familiarisation and account walkthrough | 1–2 |
| Journeys J1–J10, first pass | 5–7 |
| Cross-hospital suite X1–X12 (X11 alone is a full day) | 3–4 |
| Cross-cutting passes (section 7) | 2–3 |
| Reporting and retest of fixes | 2–3 |
| **Total first cycle** | **13–19 days** |

Each later regression round is 2–3 days. If the budget is smaller, cut in this
order: section 7 first, then J6 and J8 — but **never cut section 6**. Cross-hospital
leakage is the one class of defect that ends the product.
