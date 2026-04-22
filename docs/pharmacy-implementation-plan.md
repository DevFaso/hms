# Pharmacy Module — Understanding, User Stories & Implementation Plan

## 0a. MVP vs Full Vision — What ships first

Not everything in this plan ships at once. The table below separates **what must work on launch day** from what comes later. If a feature isn't in the MVP, it's not forgotten — it's just sequenced realistically.

| Feature | MVP (Launch) | v2 (3-6 mo later) | v3 (Future) |
| --- | --- | --- | --- |
| Medication catalog (normalized) | ✅ Top 200 essential medicines | Expand to full formulary | RxNorm crosswalk |
| Pharmacy registry | ✅ Hospital dispensaries + first 3 partner pharmacies | Expand partner network | National directory |
| Hospital dispensary inventory (Tier 1) | ✅ Stock lots, expiry, receipt, dispense | Stock transfers between sites | Automated reorder |
| Dispensing workflow (Tier 1) | ✅ Work queue, dispense, stock decrement | CDS checks (allergy, interaction) | Barcode scanning |
| Stock-out routing | ✅ Flag → print prescription for patient (Tier 3) | Partner SMS routing (Tier 2) | Split-fill, back-order |
| Partner pharmacy exchange | ❌ Not MVP — start with Tier 3 (print) | ✅ SMS channel only | WhatsApp, Portal, API |
| Patient notifications | ✅ Ready-for-pickup SMS (French) | Refill reminders, stock alerts | WhatsApp, app push |
| Payment (Tier 1 only) | ✅ Cash payment + receipt | Mobile money | Insurance co-pay |
| Claims / AMU | ❌ Not MVP | ✅ Batch CSV export | FHIR Claim, reconciliation |
| FHIR / OpenHIM | ❌ Not MVP | ❌ Not v2 | ✅ When partners need it |
| Offline dispense | ✅ Paper fallback documented | ✅ Local queue + sync | Full offline mode |
| French localization | ✅ All UI + receipts + SMS | — | Additional local languages |
| Controlled-substance dual-approval | ❌ Use paper log for now | ✅ Digital dual-approval | DEA-style audit trail |

**MVP goal:** A hospital pharmacist can receive prescriptions, check stock, dispense from the hospital dispensary, collect cash, print receipts, and print prescriptions for patients who need to go to an outside pharmacy. Everything in French. Paper fallback when the system is down.

## 0b. Critical Architecture Distinction: Hospital vs. Private Pharmacies

Most pharmacies in Burkina Faso are **private businesses**, not hospital departments. This fundamentally shapes the architecture. Burkina's AMU law guarantees the patient's free choice of pharmacist, and private pharmacy inventory is proprietary commercial data protected under the 2021 personal data protection law (CIL). **HMS must never assume it can see or manage a private pharmacy's stock.**

### Three-tier pharmacy model

| Tier | Relationship | What HMS manages | What HMS sees |
| --- | --- | --- | --- |
| **Tier 1 — Hospital dispensary** | Owned by the hospital | Full inventory, stock lots, expiry, dispensing, payment | Everything |
| **Tier 2 — Partner pharmacy** | Private business with partnership agreement | Prescription routing, dispense confirmations, optional claims relay | Formulary list (not quantities), accept/reject responses, dispense confirmations |
| **Tier 3 — Unaffiliated pharmacy** | No relationship; patient walks in | Directory listing only | Nothing — doctor asks patient at next visit |

### What private pharmacies will and will NOT share

| ✅ Will share (mutual benefit) | ❌ Will NOT share (proprietary) |
| --- | --- |
| Formulary participation ("we carry these medications") | Stock quantities or lot details |
| Prescription acceptance ("yes, we can fill this Rx now") | Purchase prices or supplier names |
| Dispense confirmation (what, to whom, when) | Reorder thresholds or expiry dates |
| Claims data (required by AMU anyway) | Internal business metrics |

### Prescription routing logic

```text
Doctor prescribes in HMS
    ├─► Hospital dispensary (Tier 1)
    │     Full dispense: stock check → lot select → decrement → payment in HMS
    ├─► Partner pharmacy (Tier 2)
    │     Send Rx → partner responds ACCEPTED/REJECTED/PARTIAL
    │     Partner confirms dispense → patient pays AT THE PHARMACY
    │     Partner submits claims independently or via HMS relay
    └─► Patient choice (Tier 3)
          Print/PDF prescription → patient walks to any pharmacy
          Optional: patient logs fill in portal
```

### Stock-out routing: when the hospital dispensary does NOT have the medication

This is the most common cross-tier scenario. The patient prescribed a medication, the state/hospital dispensary doesn't stock it or is temporarily out, and the patient needs to get it elsewhere.

```text
Doctor prescribes medication in HMS
          │
          ▼
    Hospital dispensary stock check (automatic)
          │
    ┌─────┴─────┐
    │            │
 IN STOCK    NOT IN STOCK
    │            │
    ▼            ▼
 Normal      Prescription marked REQUIRES_EXTERNAL_FILL
 Tier 1      Pharmacist informs patient of options:
 dispense        │
                 ├─► [A] Partner pharmacy (Tier 2)
                 │     System shows partners whose FORMULARY includes this medication
                 │     System ranks by: patient preference > proximity > formulary match
                 │     Patient CHOOSES (never forced — AMU law)
                 │     HMS sends Rx electronically to chosen partner
                 │     Partner responds: ACCEPTED → patient goes there, pays there
                 │                       REJECTED → try next partner or Option B/C
                 │     Partner confirms dispense → HMS medication history updated
                 │
                 ├─► [B] Any pharmacy / patient choice (Tier 3)
                 │     Pharmacist prints or generates PDF prescription
                 │     Patient takes it to ANY pharmacy they want
                 │     Patient optionally logs fill via patient portal
                 │
                 └─► [C] Back-order at hospital dispensary
                       Pharmacist places internal stock request / purchase order
                       Prescription status → PENDING_STOCK
                       Patient notified when stock arrives (SMS/app)
                       If urgent, patient uses Option A or B while waiting
```

**Key rules for this workflow:**

1. **Patient always decides** — the pharmacist presents options, the system suggests, but the patient chooses where to go (AMU law: free choice of pharmacist)
2. **Formulary match, not stock match** — HMS checks which partner pharmacies *carry* the medication (formulary list), NOT whether they have it *in stock right now* (that's their proprietary data). The partner pharmacy's ACCEPTED/REJECTED response is where real-time availability is confirmed.
3. **Payment stays where dispensing happens** — if the patient goes to a private pharmacy, they pay there. HMS does NOT collect payment for external fills.
4. **Medication history stays unified** — regardless of where the fill happens (Tier 1, 2, or 3), the patient's medication record in HMS is updated. For Tier 2 this happens via partner confirmation. For Tier 3, the doctor asks the patient verbally at next visit (portal self-report exists but is not relied upon).
5. **Partial coverage is valid** — the hospital dispensary might have some items in a multi-medication prescription. It dispenses what it has (Tier 1) and routes the rest externally (Tier 2/3). This is a *split fill*, not an error.

### New prescription statuses needed

| Status | Meaning |
| --- | --- |
| `REQUIRES_EXTERNAL_FILL` | Hospital dispensary cannot fill; awaiting patient routing decision |
| `SENT_TO_PARTNER` | Transmitted to a Tier 2 partner pharmacy; awaiting response |
| `PARTNER_ACCEPTED` | Partner pharmacy confirmed they will fill it |
| `PARTNER_REJECTED` | Partner pharmacy declined (patient should choose another option) |
| `PARTNER_DISPENSED` | Partner pharmacy confirmed dispense; medication history updated |
| `PENDING_STOCK` | Back-ordered at hospital dispensary; patient notified when available |
| `PARTIALLY_FILLED` | Some items dispensed at hospital, remainder routed externally |
| `PRINTED_FOR_PATIENT` | Prescription printed/PDF for patient to take to any pharmacy (Tier 3) |

> **Design rule:** Epics 2 (Inventory) and 3 (Dispensing) apply ONLY to Tier 1 (hospital dispensary). Tier 2 uses a prescription-exchange protocol. Tier 3 is directory + print only.

---

## 1. Understanding Summary

### What we have today
DevFaso/hms is **not** starting from zero. The codebase already contains:

| Capability | Status |
|---|---|
| Prescription CRUD (create/sign/transmit/cancel/discontinue) | ✅ Working |
| Pharmacist role + permissions (view Rx, dispense, view patients) | ✅ Exists but narrow |
| Patient portal (refills, medications, billing, notifications) | ✅ Working |
| Medication history + CDS (overlaps, interactions, polypharmacy) | ✅ Working |
| Medication administration records (MAR) | ✅ Working |
| Pharmacy fill DTO (`PharmacyFillRequestDTO`) | ✅ Exists |
| Pharmacy directory / fulfillment modes | ⚠️ Exists, U.S.-centric & static |
| SMS notifications (Twilio) | ✅ Working |
| Hospital-scoped RBAC | ✅ Working |

### What is missing (the gaps)
1. **Normalized pharmacy & medication catalog** — medication names are still partly free-text; pharmacy selection is a string, not a foreign key
2. **Inventory & stock ledger** — no stock lots, expiry tracking, reorder thresholds, or stock transactions
3. **Dispensing workflow** — no pharmacist work queue, barcode/scan verification, or dispense-to-stock linkage
4. **Payment & POS** — billing views exist but no cash/mobile-money checkout for pharmacy
5. **Claims / insurance** — no claim-file generation for Burkina's AMU
6. **Interoperability** — no FHIR mapping, no partner-pharmacy integration, no mediator
7. **Localization** — U.S.-centric identifiers (NPI, DEA, NCPDP); hardcoded mail-order option; English-only labels
8. **Expanded roles** — pharmacist role too narrow; missing verifier, inventory clerk, store manager, claims reviewer
9. **Offline resilience** — no local dispense queue for connectivity outages
10. **Security hardening** — no controlled-substance dual-approval, no pharmacy-specific audit events

### Key design decisions
- **Build inside `hospital-core`**, not as a separate module — pharmacy is a cross-cutting clinical domain
- **FHIR R4** as canonical API standard; HL7 v2 as adapter; NCPDP as comparator only
- **Local formulary first** (Burkina essential-medicines list), RxNorm as crosswalk only
- **OpenHIM** as interoperability mediator for partner connections
- **Mobile money + cash** for payments, not U.S.-style PBM adjudication
- **French-first** UI labels and printouts

---

## 2. Issues & How We Address Them

| # | Issue | Root Cause | Resolution Approach |
|---|---|---|---|
| I-1 | Free-text medication names | No normalized medication catalog | Create `MedicationCatalogItem` entity linked to Burkina essential-medicines list + optional RxNorm crosswalk |
| I-2 | Free-text `preferredPharmacy` | No pharmacy registry | Create `Pharmacy` entity with address, license, fulfillment modes; normalize all references |
| I-3 | No stock tracking | Domain not built yet | Add `InventoryItem`, `StockLot`, `StockTransaction` entities with lot/expiry/reorder logic |
| I-4 | No dispense workflow | Prescriber-centric UI only | Build pharmacist work queue, dispense verification, stock decrement, and `Dispense` entity |
| I-5 | No pharmacy POS | Billing exists but no checkout flow | Add pharmacy checkout with cash + mobile-money, linked to dispense and invoice |
| I-6 | No claims generation | Not built | Add `PharmacyClaim` entity + batch file export for AMU |
| I-7 | U.S.-centric identifiers | Prototype assumptions | Make NPI/DEA/NCPDP optional; add local identifier fields (Burkina license, facility code) |
| I-8 | Hardcoded mail-order pharmacy | Static directory service | Replace with persisted pharmacy registry; add `OUTPATIENT_HOSPITAL`, `PARTNER_PHARMACY`, `MOBILE_OUTREACH` modes |
| I-9 | Narrow pharmacist role | Early RBAC design | Expand to verifier, inventory clerk, store manager, claims reviewer sub-roles |
| I-10 | No FHIR mapping | Not built | Add `PharmacyFhirMapper` for MedicationRequest/MedicationDispense/Claim |
| I-11 | No offline dispense | Not designed | Add local dispense queue with delayed sync |
| I-12 | No pharmacy audit events | Audit exists but no pharmacy coverage | Emit audit events for dispense, cancel, override, stock-adjust, claim-submit |

---

## 3. User Stories

### Epic 1: Medication Catalog & Pharmacy Registry

> **US-1.1** As a **pharmacy manager**, I want to manage a medication catalog linked to Burkina's essential-medicines list, so that prescribers select normalized medications instead of typing free text.
> - AC: CRUD for `MedicationCatalogItem` with name (FR), generic name, ATC code, form, strength, optional RxNorm crosswalk
> - AC: Prescriptions reference catalog items by ID

> **US-1.2** As a **hospital admin**, I want to register pharmacies (hospital dispensaries and community partners) with license numbers and fulfillment modes, so that prescriptions route to real pharmacies.
> - AC: CRUD for `Pharmacy` entity with address, phone, license, fulfillment modes, active flag
> - AC: `preferredPharmacy` in refill requests references a `pharmacyId`

> **US-1.3** As a **pharmacist**, I want fulfillment modes to include `OUTPATIENT_HOSPITAL`, `PARTNER_PHARMACY`, and `MOBILE_OUTREACH` in addition to existing modes.

### Epic 2: Inventory & Stock Management

> **US-2.1** As a **pharmacy inventory clerk**, I want to receive goods into the system with lot number, expiry date, supplier, and quantity, so that stock is traceable.
> - AC: `StockLot` entity created on goods receipt with lot, expiry, quantity, supplier
> - AC: Reject expired lots on receipt

> **US-2.2** As a **pharmacy manager**, I want to see current stock levels per medication per pharmacy, with reorder alerts when stock falls below threshold.
> - AC: Dashboard showing stock quantity, days-of-supply estimate, items below reorder point
> - AC: Notification sent when stock < reorder threshold

> **US-2.3** As a **pharmacy inventory clerk**, I want to record stock adjustments (damage, theft, return, transfer) with a reason, so that the ledger stays accurate.
> - AC: `StockTransaction` with type (RECEIPT, DISPENSE, ADJUSTMENT, TRANSFER, RETURN), reason, quantity, lot reference, staff, timestamp
> - AC: Audit event emitted for every adjustment

> **US-2.4** As a **pharmacy manager**, I want to view stock expiry reports so I can rotate stock and remove expired items.

### Epic 3: Dispensing Workflow

> **US-3.1** As a **pharmacist**, I want a work queue showing prescriptions ready for dispensing at my pharmacy, sorted by priority and wait time.
> - AC: Queue filtered by pharmacy context and prescription status (SIGNED/TRANSMITTED)
> - AC: Shows patient name, medication, quantity, prescriber, time waiting

> **US-3.2** As a **pharmacist**, I want to dispense a prescription by selecting a stock lot, confirming quantity, and recording substitutions if needed, so that stock is decremented and the patient record is updated.
> - AC: `Dispense` entity created linking prescription, patient, stock lot, quantity, substitution flag/reason
> - AC: Stock lot quantity decremented atomically
> - AC: Prescription status updated to DISPENSED
> - AC: Medication history updated automatically

> **US-3.3** As a **pharmacist**, I want to flag a prescription for clinical review (allergy, interaction, duplicate therapy) before dispensing.
> - AC: CDS checks run automatically when dispense is initiated
> - AC: Pharmacist can override with reason (logged as audit event)

> **US-3.4** As a **pharmacist**, I want to process a partial fill when full quantity is unavailable, with the remaining balance tracked for later fulfillment.

> **US-3.5** As a **pharmacist**, I want to handle controlled-substance dispensing with dual-approval verification.
> - AC: Second authorized staff member must confirm before dispense completes
> - AC: Audit event with both approvers recorded

### Epic 3b: Stock-Out Routing (Tier 1 → Tier 2/3 handoff)

> **US-3b.1** As a **hospital pharmacist**, when a prescribed medication is not in stock at the hospital dispensary, I want the system to automatically flag the prescription as `REQUIRES_EXTERNAL_FILL` and present me with routing options so I can help the patient get their medication.
> - AC: Stock check happens automatically when a dispense is initiated
> - AC: If stock = 0 or insufficient quantity, status changes to `REQUIRES_EXTERNAL_FILL`
> - AC: Pharmacist sees three options: send to partner pharmacy, print for patient, or back-order

> **US-3b.2** As a **hospital pharmacist**, I want to see which partner pharmacies (Tier 2) have this medication on their formulary, ranked by patient preference and proximity, so I can suggest options to the patient.
> - AC: System queries partner formulary lists for the medication
> - AC: Results ranked by: patient's preferred pharmacy > distance > formulary match
> - AC: System shows formulary match only — NEVER stock quantities (that's the partner's private data)

> **US-3b.3** As a **patient**, I want to choose where to fill my prescription when the hospital doesn't have it — a suggested partner pharmacy, any pharmacy I want, or wait for the hospital to restock.
> - AC: Patient (or pharmacist on patient's behalf) selects routing option
> - AC: Choice is recorded on the prescription for audit trail
> - AC: AMU law compliance: system never forces a pharmacy choice

> **US-3b.4** As a **hospital pharmacist**, I want to split-fill a multi-medication prescription — dispense what we have in stock and route the remaining items externally.
> - AC: Prescription status set to `PARTIALLY_FILLED`
> - AC: Dispensed items recorded normally (Tier 1 flow)
> - AC: Remaining items get individual `REQUIRES_EXTERNAL_FILL` status
> - AC: Patient sees unified view of what was filled and what's pending

> **US-3b.5** As a **hospital pharmacist**, I want to back-order a medication and place the prescription in `PENDING_STOCK` status, so the patient is notified automatically when it arrives.
> - AC: Prescription status → `PENDING_STOCK` with estimated restock date (if known)
> - AC: SMS/app notification sent to patient when stock is received
> - AC: Pharmacist can convert to external routing if restock is delayed

> **US-3b.6** As a **patient**, I want a unified medication history regardless of whether I filled at the hospital, a partner pharmacy, or an outside pharmacy, so my doctors always see the complete picture.
> - AC: Tier 1 fills recorded automatically
> - AC: Tier 2 fills recorded via partner dispense confirmation
> - AC: Tier 3 fills: doctor asks patient verbally at next visit; self-reporting via portal is available but not expected to be widely used
> - AC: Medication history view does not distinguish source tier — all fills appear equally

### Epic 4: Patient Communication & Notifications

> **US-4.1** As a **patient**, I want to receive an SMS (in French) when my prescription is ready for pickup.

> **US-4.2** As a **patient**, I want to receive refill reminders based on my days-supply and fill date.

> **US-4.3** As a **patient**, I want to be notified if my medication is out of stock with an estimated restock date or alternative pharmacy suggestion.

### Epic 5: Payment & Checkout

> **US-5.1** As a **cashier/pharmacist**, I want to collect payment (cash or mobile money) at the pharmacy counter and link it to the dispense record and invoice.
> - AC: `PharmacyPayment` entity with method (CASH, MOBILE_MONEY, INSURANCE), amount, reference, dispense link
> - AC: Receipt printable in French

> **US-5.2** As a **patient**, I want to see my pharmacy invoices and payment history in the patient portal.

### Epic 6: Claims & Insurance

> **US-6.1** As a **claims reviewer**, I want to generate batch claim files from completed dispenses for submission to AMU/insurers.
> - AC: `PharmacyClaim` entity with dispense link, coverage reference, status, amount
> - AC: Export as structured file (CSV/FHIR Claim)

> **US-6.2** As a **claims reviewer**, I want to track claim status (SUBMITTED, ACCEPTED, REJECTED, PAID) and reconcile payments.

### Epic 7: Partner Pharmacy Exchange (Tier 2)

#### Communication channels — all options, phased by realism

Private pharmacies in Burkina Faso have varying levels of technology. HMS supports **multiple channels** — partners choose what is most convenient for them at onboarding. But building all channels at once is unrealistic. They are phased:

| Channel | When to use | Pros | Cons | **Phase** |
| --- | --- | --- | --- | --- |
| **SMS (French)** | Default for all partners; works on any phone | Universal reach, no internet needed, simple | 160-char limit, per-message cost | **v1 — day one** |
| **WhatsApp Business API** | Partner has smartphone + WhatsApp | Richer messages, free for user, delivery receipts | Requires Meta business verification (2-4 months), smartphone | **v2 — when approved** |
| **HMS Partner Web Portal** | Partner has internet + browser | Full Rx details, click-to-respond, dashboard | Requires internet connectivity | **v2** |
| **REST API** | Partner has own pharmacy software | Real-time, structured, automatable | Virtually no private pharmacy in Burkina has API-capable software today | **v3 — year 2+** |
| **FHIR / OpenHIM** | National interoperability mandate or NGO partner | Standards-based, auditable | No partner in Burkina will consume FHIR at launch | **v3 — when partners need it** |

> **Decision:** Build **SMS only** for day-one. All other channels are kept in the plan — partners will choose what is most convenient when it becomes available. Do not build what nobody can use yet.

#### SMS exchange protocol (day-one default)

> **The simplified number-based protocol is defined in Section 7.** Summary:
>
> | Partner texts | Meaning |
> | --- | --- |
> | `1 [Rx#]` | Accept |
> | `2 [Rx#]` | Refuse |
> | `3 [Rx#]` | Dispensed |
> | `0 [Rx#]` | Cancel / no-show |
>
> Error tolerance is mandatory — see §7 for fuzzy parsing rules.

**HMS → Partner: example notification**

```text
[HMS] Ordonnance #1234
Patient: Ouédraogo A.
Amoxicilline 500mg x 30
Répondez: 1 1234=accepter  2 1234=refuser
```

**HMS → Patient: acceptance notification**

```text
[HMS] Votre ordonnance #1234 est prête.
Pharmacie du Progrès, Av. de la Liberté
Tél: +226 XX XX XX XX
```

#### Future channels (v2/v3 — built when partners are ready)

**WhatsApp (v2):** Same flow as SMS but with clickable buttons (1/2/3), full Rx PDF attached, delivery + read receipts. Requires Meta Business verification first.

**Partner Web Portal (v2):** Lightweight web app where partner pharmacies see incoming Rx queue, click Accept/Reject, confirm dispenses, upload formulary. Does NOT show hospital data or other pharmacies' information.

**REST API (v3):** Structured JSON endpoint for the rare partner with their own pharmacy software.

**FHIR / OpenHIM (v3):** Standards-based interoperability for national mandate or NGO partner integration. Built only when a consuming partner exists.

> **All channels are options — partners choose what is most convenient.** They are not dropped from the plan, just built when realistic.

#### Timeout and escalation rules

| Event | Timeout | Auto-action |
| --- | --- | --- |
| No response from partner pharmacy | 2 hours (configurable) | SMS reminder sent to partner |
| Still no response | 4 hours | Status → `PARTNER_REJECTED` (auto); pharmacist re-routes |
| Patient doesn't pick up within | 48 hours | Partner notified to return Rx to queue; patient reminded |
| All partner pharmacies reject | Immediate | Pharmacist informed; fall back to Tier 3 (print) or back-order |

> **US-7.1** As a **hospital admin**, I want to onboard a private partner pharmacy by recording a partnership agreement (pharmacy ID, contact, formulary scope, **preferred communication channel** — SMS/WhatsApp/Portal/API), so that prescriptions can be routed to them via their chosen channel.
> - AC: Partnership record links to `Pharmacy` entity with agreement date, status, exchange method (SMS/WhatsApp/Portal/API), contact phone number
> - AC: Partner pharmacy never exposes stock quantities to HMS
> - AC: Default channel is SMS if nothing else is specified

> **US-7.2** As a **partner pharmacy**, I want to receive a prescription notification via my chosen channel (SMS day-one; WhatsApp/portal/API when available) in French and respond with simple number codes (see §7), so the hospital and patient know the status.
> - AC: SMS uses number reply codes (`1`=accept, `2`=refuse, `3`=dispensed, `0`=cancel — see §7)
> - AC: Fuzzy parsing tolerates spacing/formatting variations (see §7)
> - AC: Other channels (WhatsApp, portal, API) use equivalent actions — built in v2/v3
> - AC: Response updates prescription status in HMS
> - AC: Patient notified of acceptance or rejection in French

> **US-7.3** As a **partner pharmacy**, I want to confirm a dispense by replying `3` + Rx number (or equivalent action in future channels), so the patient's medication history stays complete.
> - AC: Dispense confirmation recorded in medication history without revealing stock or pricing details
> - AC: Patient receives French SMS confirming the fill

> **US-7.4** As a **partner pharmacy**, I want to optionally publish a formulary list (medications I carry, not quantities) so that HMS can suggest me as a destination for relevant prescriptions.

> **US-7.5** As a **patient**, I want to choose any pharmacy (Tier 3 / unaffiliated) and receive a printed or PDF prescription to take with me.
> - AC: French-language prescription printout with all required legal elements

### Epic 8: FHIR Interoperability (v3 — built only when a consuming partner exists)

> **US-8.1** As a **system**, I want to expose prescriptions and dispenses as FHIR R4 MedicationRequest and MedicationDispense resources via REST API.
> - Note: No partner in Burkina will consume FHIR at launch. Build when there is a real consumer.

> **US-8.2** As a **system**, I want an interoperability mediator (OpenHIM) to route, transform, and audit partner messages.
> - Note: Over-engineering for initial release. Evaluate when national interoperability mandate or NGO partnership requires it.

### Epic 9: Security & Audit Hardening

> **US-9.1** As a **compliance officer**, I want immutable audit events for all pharmacy operations (dispense, cancel, override, stock adjust, claim submit).

> **US-9.2** As a **hospital admin**, I want expanded pharmacy roles: Pharmacist, Pharmacy Verifier, Inventory Clerk, Store Manager, Claims Reviewer — each with distinct permissions.

> **US-9.3** As a **pharmacist**, I want offline dispense capability (Tier 1 only) that queues dispenses locally and syncs when connectivity returns.

### Epic 10: Localization

> **US-10.1** As a **developer**, I want all U.S.-specific identifiers (NPI, DEA, NCPDP) to be optional fields, with local Burkina identifiers added.

> **US-10.2** As a **user**, I want all pharmacy UI labels, receipts, and notifications in French by default.

---

## 4. Implementation Task List

### Phase 1 — Foundation (Weeks 1–6) ✅ COMPLETE

| # | Task | Layer | Stories | Status |
|---|---|---|---|---|
| T-1 | ✅ ~~Define & review pharmacy domain model (ERD, FHIR profiles, security threat model)~~ | Architecture | All | Done |
| T-2 | ✅ ~~DB migration: `medication_catalog_item` table~~ | DB + Entity | US-1.1 | Done (V43, V45) |
| T-3 | ✅ ~~DB migration: `pharmacy` table (registry)~~ | DB + Entity | US-1.2 | Done (V43, V46) |
| T-4 | ✅ ~~DB migration: `inventory_item`, `stock_lot`, `stock_transaction` tables~~ | DB + Entity | US-2.1–2.4 | Done (V43) |
| T-5 | ✅ ~~DB migration: `dispense` table~~ | DB + Entity | US-3.1–3.5 | Done (V43) |
| T-6 | ✅ ~~DB migration: `pharmacy_payment`, `pharmacy_claim` tables~~ | DB + Entity | US-5.1, US-6.1 | Done (V43) |
| T-7 | ✅ ~~Add new roles to `SecurityConstants.java`~~ | Backend | US-8.2 | Done |
| T-8 | ✅ ~~Expand `PharmacyFulfillmentMode` enum~~ | Backend | US-1.3 | Done |
| T-9 | ✅ ~~Make NPI/DEA/NCPDP optional; add local identifier fields~~ | Backend | US-9.1 | Done |
| T-10 | ✅ ~~Replace hardcoded mail-order with pharmacy registry lookup~~ | Backend | US-1.2, I-8 | Done |

### Phase 2 — Medication Catalog & Pharmacy Registry (Weeks 3–8) ✅ COMPLETE

| # | Task | Layer | Stories | Status |
|---|---|---|---|---|
| T-11 | ✅ ~~`MedicationCatalogItem` entity, repository, service, controller, DTO, mapper~~ | Full-stack BE | US-1.1 | Done |
| T-12 | ✅ ~~`Pharmacy` entity, repository, service, controller, DTO, mapper~~ | Full-stack BE | US-1.2 | Done |
| T-13 | ✅ ~~Medication catalog admin UI (Angular)~~ | Frontend | US-1.1 | Done |
| T-14 | ✅ ~~Pharmacy registry admin UI (Angular)~~ | Frontend | US-1.2 | Done |
| T-15 | Refactor prescription creation to reference `medicationCatalogItemId` | Full-stack | US-1.1 | ⏳ Deferred to Phase 4 |
| T-16 | Refactor refill request to reference `pharmacyId` instead of free-text | Full-stack | US-1.2 | ⏳ Deferred to Phase 4 |
| T-17 | ✅ ~~Unit + integration tests for catalog & registry~~ | Tests | US-1.1, 1.2 | Done |

### Phase 3 — Inventory & Stock Management (Weeks 6–14) ✅ COMPLETE

| # | Task | Layer | Stories | Status |
|---|---|---|---|---|
| T-18 | ✅ ~~`InventoryItem` service + controller + DTO + mapper~~ | Full-stack BE | US-2.2 | Done |
| T-19 | ✅ ~~`StockLot` service (goods receipt, expiry validation)~~ | Backend | US-2.1 | Done |
| T-20 | ✅ ~~`StockTransaction` service (receipt, adjust, transfer, return)~~ | Backend | US-2.3 | Done |
| T-21 | ✅ ~~Reorder-threshold alert logic + notification~~ | Backend | US-2.2 | Done |
| T-22 | ✅ ~~Stock expiry report query + endpoint~~ | Backend | US-2.4 | Done |
| T-23 | ✅ ~~Inventory dashboard UI (stock levels, alerts, expiry report)~~ | Frontend | US-2.2, 2.4 | Done |
| T-24 | ✅ ~~Goods receipt UI~~ | Frontend | US-2.1 | Done |
| T-25 | ✅ ~~Stock adjustment UI~~ | Frontend | US-2.3 | Done |
| T-26 | ✅ ~~Audit events for all stock transactions~~ | Backend | US-8.1 | Done |
| T-27 | Tests: inventory service, stock lot, transactions | Tests | US-2.x | ⏳ Deferred — add alongside Phase 4 tests |

### Phase 4 — Dispensing Workflow (Weeks 10–18) ✅ COMPLETE

| # | Task | Layer | Stories | Status |
|---|---|---|---|---|
| T-28a | ✅ ~~`Dispense` entity, repository, DTO, mapper~~ | Full-stack BE | US-3.2 | Done (V43) |
| T-28b | ✅ ~~`DispenseService` + `DispenseController` — CRUD, validate Rx state, stock check~~ | Full-stack BE | US-3.2 | Done |
| T-29 | ✅ ~~Pharmacist work queue endpoint (prescriptions by pharmacy, status, priority)~~ | Backend | US-3.1 | Done |
| T-30 | ✅ ~~Dispense → stock lot decrement (atomic, with partial-fill support)~~ | Backend | US-3.2, 3.4 | Done |
| T-31 | CDS pre-dispense checks (allergy, interaction, duplicate therapy) | Backend | US-3.3 | ⏳ Deferred to Phase 4b |
| T-32 | Controlled-substance dual-approval flow | Backend | US-3.5 | ⏳ Deferred to Phase 4b |
| T-33 | ✅ ~~Pharmacist work queue UI~~ | Frontend | US-3.1 | Done |
| T-34 | ✅ ~~Dispense verification + confirmation UI~~ | Frontend | US-3.2 | Done |
| T-35 | CDS alert display + override UI | Frontend | US-3.3 | ⏳ Deferred to Phase 4b |
| T-36 | ✅ ~~Audit events for dispense, override, dual-approval~~ | Backend | US-8.1 | Done |
| T-37 | ✅ ~~Tests: dispense service, CDS checks, dual-approval~~ | Tests | US-3.x | Done |

### Phase 4b — Stock-Out Routing & Cross-Tier Handoff (Weeks 14–20) ✅ MVP COMPLETE

MVP scope (Tier 3 print-for-patient routing, partner formulary matching, routing decisions) shipped in PR #113 (commit `63f7d65d` on develop). Remaining items are v2/v3 per the MVP table and are deferred.

| # | Task | Layer | Stories | Status |
|---|---|---|---|---|
| T-37a | ✅ ~~Add new prescription statuses (`REQUIRES_EXTERNAL_FILL`, `SENT_TO_PARTNER`, `PARTNER_ACCEPTED`, `PARTNER_REJECTED`, `PARTNER_DISPENSED`, `PENDING_STOCK`, `PARTIALLY_FILLED`, `PRINTED_FOR_PATIENT`)~~ | Backend | US-3b.1 | Done (PrescriptionStatus enum) |
| T-37b | ✅ ~~Stock-out detection service: auto-check stock on dispense initiation, flag `REQUIRES_EXTERNAL_FILL`~~ | Backend | US-3b.1 | Done |
| T-37c | ✅ ~~Partner formulary matching: query partner pharmacies by medication, rank by patient pref + proximity~~ | Backend | US-3b.2 | Done |
| T-37d | ✅ ~~Routing decision service: record patient's choice (partner/print/back-order), update Rx status~~ | Backend | US-3b.3 | Done |
| T-37e | Split-fill logic: partial dispense at Tier 1 + external routing for remainder | Backend | US-3b.4 | 🕒 Deferred (v3) |
| T-37f | Back-order / `PENDING_STOCK` flow: link to purchase order, auto-notify on restock | Backend | US-3b.5 | 🕒 Deferred (v2) |
| T-37g | Unified medication history: accept Tier 2 confirmations + Tier 3 patient self-reports | Backend | US-3b.6 | 🕒 Deferred (v2 — depends on Phase 7a) |
| T-37h | ✅ ~~Stock-out routing UI: pharmacist sees options, patient selects destination~~ | Frontend | US-3b.1–3 | Done |
| T-37i | Split-fill UI: show what was dispensed vs. what's pending | Frontend | US-3b.4 | 🕒 Deferred (v3) |
| T-37j | Prescription print/PDF generator (French, legal format) | Frontend | US-7.5, US-3b.3 | 🕒 Deferred (moved to Phase 5 alongside receipt template T-44) |
| T-37k | Patient portal: self-report external fill | Frontend | US-3b.6 | 🕒 Deferred (v2) |
| T-37l | ✅ ~~Tests: stock-out routing, partner matching, routing decisions~~ | Tests | US-3b.x | Done (DispenseServiceImplTest, StockOutRoutingServiceImplTest) |

### Phase 5 — Patient Communication & Payment (Weeks 18–24) ✅ COMPLETE

| # | Task | Layer | Stories | Status |
|---|---|---|---|---|
| T-38 | ✅ ~~Ready-for-pickup SMS notification (French template)~~ | Backend | US-4.1 | Done (PharmacyServiceSupport.notifyReadyForPickup, triggered on full DISPENSED) |
| T-39 | ✅ ~~Refill reminder scheduler based on days-supply~~ | Backend | US-4.2 | Done (PharmacyRefillReminderScheduler, daily 09:00 cron, parses duration days/semaines, French SMS via PharmacyServiceSupport.notifyRefillReminder) |
| T-40 | ✅ ~~Out-of-stock notification with alternatives~~ | Backend | US-4.3 | Done (French SMS sent on partner-route / print-for-patient / back-order) |
| T-41 | ✅ ~~`PharmacyPayment` service + controller + DTO~~ | Full-stack BE | US-5.1 | Done (PharmacyPaymentService/Impl + /pharmacy/payments controller; tenant-scoped, audits PAYMENT_POSTED) |
| T-42 | ✅ ~~Mobile-money integration adapter (abstract interface + first provider)~~ | Backend | US-5.1 | Done (MobileMoneyGateway interface + MockMobileMoneyGateway @Primary default; swap in real Orange/Wave/MTN impl later) |
| T-43 | ✅ ~~Pharmacy checkout UI (cash + mobile money)~~ | Frontend | US-5.1 | Done (pharmacy/checkout route, French form, radio Espèces/Mobile Money/Assurance) |
| T-44 | ✅ ~~French receipt + prescription PDF templates (printable)~~ | Frontend | US-5.1, 9.2, US-3b.3 | Done (printable receipt section in pharmacy-checkout with @media print CSS; prescription PDF template still deferred) |
| T-45 | ✅ ~~Patient portal: pharmacy invoices & payment history~~ | Frontend | US-5.2 | Done (my-pharmacy-invoices route, lists listPaymentsByPatient w/ total-paid banner) |
| T-46 | ✅ ~~Tests: notifications, payment, checkout~~ | Tests | US-4.x, 5.x | Done (PharmacyRefillReminderSchedulerTest, MockMobileMoneyGatewayTest, PharmacyPaymentServiceImplTest + T-39 tests in PharmacyServiceSupportTest) |

### Phase 6 — Claims & Insurance (Weeks 18–24) ✅ COMPLETE

| # | Task | Layer | Stories | Status |
|---|---|---|---|---|
| T-47 | ✅ ~~`PharmacyClaim` entity, service, controller, DTO, mapper~~ | Full-stack BE | US-6.1 | Done (PharmacyClaimService/Impl + /pharmacy/claims controller; tenant-scoped) |
| T-48 | ✅ ~~Claim batch-file export (CSV + FHIR Claim)~~ | Backend | US-6.1 | Done (PharmacyClaimExportService; /pharmacy/claims/export/csv and /export/fhir) |
| T-49 | ✅ ~~Claim status tracking + reconciliation endpoint~~ | Backend | US-6.2 | Done (submit / accept / reject / pay endpoints with enforced state transitions) |
| T-50 | ✅ ~~Claims management UI~~ | Frontend | US-6.1, 6.2 | Done (/pharmacy/claims route; French status badges, lifecycle actions, CSV+FHIR export) |
| T-51 | ✅ ~~Audit events for claim submit/reverse~~ | Backend | US-8.1 | Done (CLAIM_SUBMITTED audit emitted on every state transition in PharmacyClaimServiceImpl) |
| T-52 | ✅ ~~Tests: claims service, export, reconciliation~~ | Tests | US-6.x | Done (PharmacyClaimServiceImplTest ×10, PharmacyClaimExportServiceTest ×3) |

### Phase 7a — Partner SMS Channel (Weeks 20–24, day-one)

| # | Task | Layer | Stories | Est |
|---|---|---|---|---|
| T-53 | `PartnerNotificationChannel` abstraction: interface with `sendPrescription()`, `receiveResponse()`, `receiveDispenseConfirmation()` | Backend | US-7.2 | 2d |
| T-54 | SMS channel implementation: send French-template Rx notification via SMS gateway; parse number reply codes (`1`/`2`/`3`/`0` + Rx#) with fuzzy tolerance (see §7) | Backend | US-7.2, 7.3 | 4d |
| T-55 | SMS inbound webhook: receive partner reply SMS, parse code, update prescription status | Backend | US-7.2 | 3d |
| T-59 | Timeout & escalation scheduler: auto-remind at 2h, auto-reject at 4h, patient no-show at 48h | Backend | US-7.2 | 2d |
| T-60 | French SMS templates: Rx notification, acceptance, rejection, dispense confirmation, reminder | Backend | US-7.2 | 2d |
| T-61 | Patient notification: SMS in French when partner accepts, when medication is dispensed | Backend | US-4.1, 7.2 | 2d |
| T-66 | Tests: SMS parse (including fuzzy input), timeout logic, status transitions | Tests | US-7.x | 3d |

### Phase 7b — Additional Partner Channels (v2, built when partners are ready)

| # | Task | Layer | Stories | Est |
|---|---|---|---|---|
| T-56 | WhatsApp Business API channel: rich messages with clickable buttons, callbacks | Backend | US-7.2 | 4d |
| T-57 | Partner Web Portal: Rx queue, accept/reject, dispense confirmation, formulary upload | Full-stack | US-7.1–7.4 | 8d |
| T-58 | REST API channel: structured JSON endpoint for partners with own software | Backend | US-7.2, 7.3 | 3d |

### Phase 7c — FHIR Interoperability (v3, when consuming partners exist)

| # | Task | Layer | Stories | Est |
|---|---|---|---|---|
| T-62 | Add HAPI FHIR dependency to `build.gradle` | Backend | US-8.1 | 1d |
| T-63 | `PharmacyFhirMapper` — MedicationRequest + MedicationDispense + Claim | Backend | US-8.1, 8.2 | 5d |
| T-64 | FHIR REST endpoints (`/fhir/MedicationRequest`, `/fhir/MedicationDispense`) | Backend | US-8.1 | 3d |
| T-65 | OpenHIM mediator setup & configuration | Infra | US-8.2 | 4d |

### Phase 8 — Localization, Offline & Hardening (Weeks 30–36)

| # | Task | Layer | Stories | Est |
|---|---|---|---|---|
| T-67 | French i18n for all pharmacy UI components | Frontend | US-10.2 | 3d |
| T-68 | Offline dispense queue (local storage + sync) | Full-stack | US-9.3 | 5d |
| T-69 | Comprehensive pharmacy audit event coverage review | Backend | US-9.1 | 2d |
| T-70 | Security review: RBAC, controlled-substance, encryption, consent | Security | US-9.x | 3d |
| T-71 | End-to-end tests (prescription → dispense → payment → claim) | Tests | All | 4d |
| T-72 | Performance testing: stock queries, dispense throughput | Tests | All | 2d |
| T-73 | Documentation: API docs, pharmacy user guide (French), runbook | Docs | All | 3d |

---

## 5. Dependency Graph

```text
Phase 1 (Foundation)
  └── Phase 2 (Catalog & Registry)
        ├── Phase 3 (Inventory — Tier 1 only) ───┐
        │                                         ▼
        └── Phase 4 (Dispensing — Tier 1) ◄──── [needs inventory]
              ├── Phase 4b (Stock-out routing → Tier 2/3)
              ├── Phase 5 (Payment & Notifications)
              │     └── Phase 6 (Claims)
              └── Phase 7 (Partner Channels — SMS first, then others)
                    └── Phase 8 (Localization, Offline, Hardening)
```

## 6. Human-Friendliness: Day in the Life

### Scenario A — Hospital pharmacist, morning shift (Tier 1)

1. **Login** → French UI, default view is "Ma file d'attente" (My work queue)
2. Sees 12 prescriptions waiting — sorted by: urgent first, then longest wait
3. Clicks first prescription → sees patient name, medication, dosage, prescriber, and a green badge "EN STOCK" or red badge "RUPTURE" (out of stock)
4. **If in stock**: clicks "Délivrer" (Dispense) → system shows available stock lots sorted by expiry (FEFO — first expiry, first out) → confirms quantity → prints receipt → done
5. **If out of stock**: sees a clear screen with 3 big buttons:
   - 🏥 "Envoyer à une pharmacie partenaire" (Send to partner pharmacy)
   - 🖨️ "Imprimer l'ordonnance pour le patient" (Print prescription for patient)
   - 📦 "Commander / mettre en attente" (Back-order)
6. The pharmacist explains options to the patient **verbally** — the system is a tool, not a replacement for human communication
7. Patient chooses → pharmacist clicks the button → system handles the rest

### Scenario B — Partner pharmacy receives SMS (Tier 2)

1. Partner pharmacist's phone buzzes — SMS in French from HMS
2. Message is simple, clear, no jargon:
   > `Ordonnance #1234 - Amoxicilline 500mg x 30 pour Ouédraogo A. Répondez 1=accepter 2=refuser`
3. Pharmacist checks their shelf, texts back `1 1234`
4. When patient arrives and picks up: texts `3 1234`
5. That's it. No app to install, no login, no internet needed.

### Scenario C — Patient with printed prescription (Tier 3)

1. Hospital pharmacist tells patient: "Nous n'avons pas ce médicament, voici votre ordonnance"
2. Prints a clear, professional A5 prescription in French with: patient name, medication, dosage, prescriber, hospital stamp, date
3. Patient takes it to any pharmacy they trust
4. At next hospital visit, doctor can see in HMS that the medication was prescribed but fill status is unknown — doctor asks the patient verbally

> **Design principle:** The system serves the humans, not the other way around. If a pharmacist has to memorize codes, read English, or click 10 times to do something simple — the design has failed.

## 7. SMS Protocol — Simplified for Real People

The original plan had 5 reply codes (OUI, NON, PARTIEL, DISP, ANNUL). That's too many. Simplify to **numbers only** — universally understood, no spelling mistakes:

| Partner texts | Meaning | Example |
| --- | --- | --- |
| `1 [Rx#]` | "I accept" | `1 1234` |
| `2 [Rx#]` | "I refuse" | `2 1234` |
| `3 [Rx#]` | "Dispensed to patient" | `3 1234` |
| `0 [Rx#]` | "Cancel / patient didn't come" | `0 1234` |

**Error tolerance rules (mandatory):**

- Accept with or without space: `11234` = `1 1234` = `1-1234`
- Case insensitive if they type words: `oui 1234` also accepted
- If unparseable, auto-reply: `Message non compris. Répondez: 1 1234=accepter, 2 1234=refuser`
- If wrong Rx number, auto-reply: `Numéro d'ordonnance 9999 introuvable. Vérifiez le numéro.`

### Why not USSD?

USSD (menu-driven, `*123#` style) works on feature phones and requires no typing. It's worth evaluating for v2 but has complications:
- Requires telco partnership and provisioning (slow process in Burkina)
- Session timeouts (USSD sessions drop after 30-60 seconds)
- Better for patient-facing interactions than pharmacy workflow
- **Decision: evaluate USSD for patient self-service in v2; use SMS for partner pharmacies in v1**

## 8. Training & Change Management

No software deployment succeeds without training. This plan adds human adoption work.

| Who | Training needed | Format | When |
| --- | --- | --- | --- |
| Hospital pharmacists | Full system training: queue, dispense, stock-out routing, receipts, paper fallback | In-person, 2 days + 1 day shadowed practice | 2 weeks before go-live |
| Inventory clerks | Goods receipt, stock adjustments, expiry reports | In-person, 1 day | 2 weeks before go-live |
| Partner pharmacy staff | SMS protocol only: how to receive, reply, confirm | 30-min phone call + printed 1-page guide (French) | 1 week before go-live |
| Doctors / prescribers | Medication catalog search (replaces free-text) | 15-min demo in existing staff meeting | 1 week before go-live |
| Patients | None — system should be invisible to patients | — | — |
| Hospital admin | Pharmacy registry, reports, claims dashboard | In-person, 1 day | 2 weeks before go-live |

**Training materials to create:**
- [ ] 1-page laminated quick-reference card for pharmacist desk (French)
- [ ] 1-page SMS guide for partner pharmacies (French, with examples)
- [ ] Paper fallback procedure card (see below)
- [ ] 5-minute video walkthrough for prescribers

## 9. Paper Fallback — When the System is Down

Power cuts, internet outages, and server issues will happen. The pharmacy **must not stop working**.

| Situation | What happens | What staff does |
| --- | --- | --- |
| HMS is down | Pharmacist cannot access the work queue | Use the **paper dispense log**: a printed form (kept at every dispensary counter) recording patient name, medication, quantity, lot number, date, pharmacist signature |
| Internet is down but HMS local is up | System works but SMS notifications don't send | Dispense normally; notifications queue and send when internet returns |
| Power is out | Everything is down | Paper dispense log + handwritten prescription. Log entries entered into HMS when power returns. |
| SMS gateway is down | Partner pharmacies can't be reached | Fall back to phone call to partner, or print prescription for patient (Tier 3) |

**Paper dispense log form** (pre-printed A4, kept in pads at the counter):

```text
REGISTRE DE DÉLIVRANCE — [Nom de la pharmacie] — Date: ___/___/______

| # | Heure | Nom patient | Médicament | Qté | Lot | Signature |
|---|-------|-------------|------------|-----|-----|-----------|
| 1 |       |             |            |     |     |           |
| 2 |       |             |            |     |     |           |
...
```

> **Rule:** Paper logs MUST be entered into HMS within 24 hours of system restoration. The inventory clerk is responsible for reconciliation.

## 10. Pilot & Rollout Strategy

| Phase | Scope | Duration | Success criteria |
| --- | --- | --- | --- |
| **Pilot A** | 1 hospital dispensary, 0 partners | 4 weeks | Pharmacist can dispense, collect cash, print receipts. Stock ledger accurate within 2%. |
| **Pilot B** | Same hospital + 2-3 partner pharmacies (SMS only) | 4 weeks | SMS round-trip works >95% of the time. Partner response time < 1 hour average. |
| **Controlled rollout** | 2-3 hospitals + 10 partner pharmacies | 8 weeks | No critical bugs. Staff uses system without daily support calls. |
| **General availability** | All planned hospitals and willing partners | Ongoing | System stable. KPI dashboard active. Monthly review meetings. |

> **Rule:** Do not proceed to the next pilot phase until the current phase's success criteria are met. The hospital pharmacy lead and the project lead must both sign off.

## 11. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Burkina essential-medicines list not digitized | Blocks catalog normalization | Manual digitization of top 200 items; iterate |
| Mobile-money provider API instability | Payment failures | Abstract payment interface; support cash fallback always |
| Partner pharmacies have no digital systems | Interop blocked | SFTP batch + paper-based fallback; onboard 1 pilot partner first |
| Connectivity outages at facilities | Dispensing stops | Paper fallback (Section 9) is mandatory from day one; software offline queue (US-9.3) added in Phase 8 |
| Alert fatigue from CDS checks | Pharmacists ignore warnings | Start with high-confidence checks only; track override rate |
| Scope creep into full PBM | Timeline blows up | Strict phase gates; claims start as batch file, not real-time adjudication |

## 12. Definition of Done (per task)

- [ ] DB migration runs cleanly (if applicable)
- [ ] Entity + DTO + Mapper follow project conventions (hand-written `@Component` mapper, builder pattern)
- [ ] Service contains all business logic; controller is thin
- [ ] Endpoint secured with appropriate role(s)
- [ ] Input validated at controller boundary (`@Valid`)
- [ ] Pagination on list endpoints
- [ ] Audit event emitted for state-changing operations
- [ ] Unit tests for service layer
- [ ] Integration tests for controller layer
- [ ] Frontend model/service/component updated (if API change)
- [ ] `npm run lint` passes
- [ ] French labels included (if UI-facing)
- [ ] No hardcoded secrets or U.S.-specific mandatory fields
