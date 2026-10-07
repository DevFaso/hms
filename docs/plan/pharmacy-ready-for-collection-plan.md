# Plan — pharmacy "ready for collection" (audit gap G15)

Branch `feat/pharmacy-ready-for-collection` off `origin/develop` 4d3f17793.
Next free migration: **V178**. Every file:line below was read on that commit.
Java paths are relative to `hospital-core/src/main/java/com/example/hms/`
unless they say otherwise.

**Revision 2 (2026-10-07).** The Mode-A plan review returned REVISE. This
revision:

- applies the five blocking findings B1–B5 and the coordinator decision B6;
- applies every advisory;
- records the user's decisions of 2026-10-07 as decided (section 10, "Decided").

<!-- Revision 3 edits applied -->
**Revision 3 (2026-10-07).** Plan review pass 2 returned REVISE. This
revision:

- applies N1: the routing and clarification writes now take the prescription
  lock;
- applies advisories A1–A9;
- applies the coordinator decision A4, which puts the flag on
  `GET /pharmacy/dispense/settings`.

---

## 1. Summary

Today a hospital pharmacist can only record a fill at hand-over, so the
patient learns about their medication when it is already in their hand. This
change adds a two-step in-house fill:

1. The pharmacist **prepares** the fill. Stock comes off the shelf and is set
   aside.
2. The patient is told the fill is **ready for collection**: by SMS, in the
   patient portal, and in both mobile apps.
3. A later **hand-over** completes the fill exactly as today's one-step
   dispense does.

How the prepared fill is stored and handled:

- It is a `Dispense` row in the existing `DispenseStatus.PENDING`, a value no
  server code writes today.
- The prescription's own status does not change while the fill waits.
- Every path that creates, moves or voids a prepared fill first takes one
  lock: the prescription row.
- The pharmacist can cancel a preparation.
- Any withdrawal or edit by the prescriber voids it automatically, so a
  withdrawn or changed order can never be collected.
- A patient who has not come after 3 days gets one reminder.

The one-step dispense is unchanged. Partner and community pharmacies are out
of scope for v1.

## 2. Business context

**Problem.** The patient's only pharmacy SMS goes out after hand-over:

- `PharmacyServiceSupport.notifyDispensed`
  (`service/pharmacy/PharmacyServiceSupport.java:104-123`) runs only once the
  prescription is fully DISPENSED (`service/pharmacy/DispenseServiceImpl.java:341-344`).
- Its own Javadoc (`:89-103`) says: "`DispenseStatus.PENDING` is never written".
- The wording was changed from "ready for pickup" to "has been dispensed"
  (#717, #790) so that it stopped describing something that had not happened.
  The `sms.pharmacy.dispensed` keys are in `hospital-core/src/main/resources/`:
  `messages_fr.properties:1168`, `messages.properties:1186`,
  `messages_en.properties:1158`, `messages_es.properties:1103`.
- `tasklist.md:3693` lists G15 as open and unowned. `tasklist.md:3560` calls a
  real "ready for collection" state a product decision.

**Current behaviour, in code.**

**Dispense status.** `enums/DispenseStatus.java` defines PENDING, COMPLETED,
PARTIAL and CANCELLED. The entity default is COMPLETED
(`model/pharmacy/Dispense.java:126-130`).

**The client can write PENDING today.**

- `DispenseRequestDTO.status` (`payload/dto/pharmacy/DispenseRequestDTO.java:69`)
  is copied onto the row as sent (`mapper/pharmacy/DispenseMapper.java:124`).
- The fill-sum query excludes only CANCELLED
  (`repository/pharmacy/DispenseRepository.java:53-56`). Its callers are
  `DispenseServiceImpl.java:870` and `StockOutRoutingServiceImpl.java:655`.
- So a client-posted PENDING row already counts as a fill.
- The portal never sends `status` (`hospital-portal/src/app/pharmacy/dispensing.ts:325-352`).

**The portal also counts fills itself.** It treats every dispense that is not
CANCELLED as a fill, in `hospital-portal/src/app/prescriptions/prescriptions.ts`:

- `:1047`, the routing snapshot's "latest fill";
- `:1109`, the dispensed-to-date sum, which reads `countableFills`;
- `:1134`, the `countableFills` filter itself.

**Pharmacy services.**

- **`createDispense`** (`DispenseServiceImpl.java:226-270`) wraps
  `createDispenseTransactionally` (`:274-361`), which runs these steps in order:
  1. tenant and prescription checks — `loadAndValidatePrescription` (`:363-393`),
     which reads with plain `findById` and checks `DISPENSABLE_STATUSES`
     (`:146-153`) and `ControlledSubstanceGuard.requireDispensable`;
  2. CDS;
  3. verification — `applyVerificationOutcome` (`:516-560`);
  4. stock decrement — `consumeStockLot` (`:454-495`);
  5. insert;
  6. status recompute and prescriber notification — `updatePrescriptionStatusFromHistory`
     (`:867-900`);
  7. back-order and refill close-outs;
  8. SMS — sent inside the transaction;
  9. audit — `DISPENSE_CREATED` (`enums/AuditEventType.java:99`).
- **`cancelDispense`** (`:665-730`) accepts only COMPLETED and PARTIAL. It
  returns stock (RETURN) and recomputes the status only from
  `RECOMPUTABLE_AFTER_CANCEL` (`:189-194`).
- **The work queue.** `getWorkQueue` (`:733-746`) lists `WORK_QUEUE_STATUSES`
  (`:162-164`). The order of attention reasons is set in `attentionReason`
  (`:1035-1046`).
- **Scope checks.** `enforceHospitalScope(Pharmacy, UUID)` (`:1156-1166`) throws
  `pharmacy.notfound`, whereas a missing dispense throws `dispense.notfound`.
  That difference is an existence oracle on the dispense-id endpoints.
- **`GlobalExceptionHandler`** has no handler for
  `OptimisticLockingFailureException` or `PessimisticLockingFailureException`.
  They fall to the `RuntimeException` catch-all (`exception/GlobalExceptionHandler.java:350`).
  `DataIntegrityViolationException` returns 400 (`:155-160`).

**Withdrawal (#812).**

- `PrescriptionStatus.isWithdrawn()` (`enums/PrescriptionStatus.java:40-42`)
  covers CANCELLED and DISCONTINUED.
- Withdrawal is final (`service/PrescriptionServiceImpl.java:641-645`).
- Partner offers are closed in `closePartnerOffersOnWithdrawal` (`:672-680`),
  called at `:859`.
- `updatePrescription` loads with plain `findById` (`:808`).
- The prescription has `@Version` (`model/Prescription.java:384`). Ready never
  writes the prescription row, so `@Version` can never catch a race between
  ready and withdrawal.

**TRANSMISSION_FAILED (#818)** is all four of the following:

- dispensable (`:152`);
- routable (`service/pharmacy/StockOutRoutingServiceImpl.java:97-105`);
- dispatchable (`service/impl/PrescriptionSmsDispatchServiceImpl.java:84-91`);
- clarifiable (`service/pharmacy/PrescriptionClarificationService.java:73-80`).

**Patient read surface.**

- `GET /me/patient/medications` (`controller/PatientPortalController.java:156`)
  is built in `service/impl/PatientMedicationServiceImpl.java:156-185`. Its
  `resolveStatus` (`:239-250`) collapses everything to ACTIVE, ON_HOLD,
  DISCONTINUED or COMPLETED.
- `GET /me/patient/prescriptions` (`:168`) is built in
  `PatientPortalServiceImpl.getMyPrescriptions` (`:362-369`).
- The staff read `GET /patients/{id}/medications` (`controller/PatientMedicationController.java:65`)
  uses the same `toResponse`.
- Both apps mirror `PrescriptionStatus` exhaustively: Android at
  `MedicationModels.kt:163`, iOS at `MedicationModels.swift:140`. An installed
  build shows any value it does not know as "unknown".

**Desired outcome.**

- The patient is told, in their language, when the medication is actually
  waiting, and sees the same fact in the portal and in both apps.
- The pharmacist sees what is prepared and for how long.
- Hand-over closes the fill exactly as today.
- Nothing prepared can be handed over against an order that is withdrawn,
  edited, or otherwise not dispensable.

## 3. Actors & permissions

| Actor | May | Gate |
|---|---|---|
| PHARMACIST, PHARMACY_VERIFIER, HOSPITAL_ADMIN, SUPER_ADMIN *with a pinned hospital* | Mark ready, hand over, cancel a preparation | Same `@PreAuthorize` as `POST /pharmacy/dispense` (`controller/pharmacy/DispenseController.java:46`), plus a non-null `requireActiveHospitalId()` for every write, as in `requireHospitalScopeForWrite` (`:1129-1133`) |
| Same roles | See the preparation on the work queue | `GET /pharmacy/dispense/work-queue` (`DispenseController.java:36`) |
| PHARMACIST, PHARMACY_VERIFIER, DOCTOR, HOSPITAL_ADMIN, SUPER_ADMIN | See the PENDING row in a prescription's dispense history | `listByPrescription` (`:68`); NURSE and MIDWIFE are **not** on this gate |
| The above, plus NURSE and MIDWIFE | See it in a patient's dispense history | `listByPatient` (`:80`) |
| STORE_MANAGER | Nothing new; sees PENDING rows when listing a pharmacy's dispenses | `listByPharmacy` (`:92`) |
| Prescriber | Withdraws or edits the order; either action voids the preparation | Existing `updatePrescription` rules; no new endpoint |
| Patient (ROLE_PATIENT), or a verified proxy with medication access | Sees "ready for collection at <pharmacy> since <time>" on their own rows; receives the SMS | `PatientChartAccess.requireOwnRecord`; the SMS goes to `patient.phoneNumberPrimary` only |
| Staff with chart access | Sees the same readiness fields on `GET /patients/{id}/medications` | Existing `PatientChartAccess.require` gate |

**Tenancy and refusals.**

- On `hand-over` and `cancel-ready`, every failure to find or scope the row
  answers **404 `dispense.notfound` with an identical body**:
  - the id does not exist;
  - the dispense's pharmacy belongs to another hospital;
  - the pharmacy has no hospital;
  - the caller has no hospital scope (null scope).

  These endpoints must **not** call `enforceHospitalScope`, which answers
  `pharmacy.notfound` (`:1156-1166`). They compare the hospital themselves and
  throw `dispense.notfound`.
- The same oracle on `getDispense` and `cancelDispense` already exists today.
  It is recorded as tasklist debt and is out of scope here.
- `ready` reuses `loadAndValidatePrescription`. A prescription in another
  hospital answers 404 `prescription.notfound` (`:370-376`), as dispense does
  today.
- `ready` is refused for any pharmacy that is not a `HOSPITAL_DISPENSARY`
  (`requireDispensary`, `:1147-1154`).
- **Voiding on withdrawal or edit is not a pharmacist request.** It runs inside
  the prescriber's already-authorised transaction, which may be a super-admin
  in global view. So it does **no** hospital-scope check of its own (see
  rule 8).
- A patient calling a staff endpoint gets 403 from `@PreAuthorize`.

## 4. User stories with acceptance criteria

### US-1 — The pharmacist prepares a fill and the patient is told

**AC-1 — Mark ready.**

- *Given* a prescription at the active hospital that is SIGNED, TRANSMITTED,
  PARTIALLY_FILLED, PENDING_STOCK, PARTNER_REJECTED or TRANSMISSION_FAILED,
  and an in-house dispensary.
- *When* a pharmacist sends `POST /pharmacy/dispense/ready` with a quantity,
  an optional lot and an optional product scan.
- *Then*, all under the prescription row lock (rule 1):
  - a `Dispense` row is created with `status=PENDING` and `dispensedAt=null`;
  - both `dispensedBy` (NOT NULL column) and `preparedBy` are set to the
    caller;
  - `createdAt` is the ready time;
  - the lot and the inventory are decremented, with a DISPENSE stock
    transaction;
  - the prescription status is unchanged and the prescriber is not notified;
  - `DISPENSE_READY` is audited;
  - after commit, `sms.pharmacy.readyForCollection` is sent in the patient's
    locale;
  - the response is 201.

**AC-2 — Ready refused for an order that cannot be dispensed.**

- *Given* a prescription that is CANCELLED, DISCONTINUED,
  PENDING_CLARIFICATION, PARTNER_ACCEPTED, SENT_TO_PARTNER, DISPENSED, DRAFT or
  PENDING_SIGNATURE.
- *When* ready is requested.
- *Then* it is refused with **400**, the same error as today's dispense (`:380-382`).
- The same 400 applies when:
  - a controlled substance has no 2FA;
  - a required co-sign is missing;
  - the lot has expired;
  - a CRITICAL CDS alert has no override reason.
- Nothing is written, no stock moves, and no SMS is sent.

**AC-3 — One open preparation per prescription; no stock spent twice.**

With an open PENDING fill:

- a second ready → **409** `dispense.ready.alreadyOpen`;
- `POST /pharmacy/dispense` (one-step) → **409** `dispense.ready.openPreparation`.

Concurrency:

- Two concurrent readies leave exactly one PENDING row. The loser gets 409:
  rule 1 serialises the two, and its re-check sees the open preparation.
- A ready racing a one-step dispense ends with exactly one of the two
  committed. The other gets 409, and stock is decremented once.
- Postgres ITs prove both.
- The V178 partial unique index is the last line of defence. A Postgres IT
  checks it directly: a raw second PENDING insert for the same prescription
  is rejected.

### US-2 — The patient collects and the pharmacist hands over

**AC-4 — Hand-over.**

- *Given* a PENDING dispense.
- *When* a pharmacist sends `POST /pharmacy/dispense/{id}/hand-over`, with an
  optional patient wristband scan.
- *Then* one conditional UPDATE (rule 7) sets these fields on the row:
  - `status=COMPLETED`;
  - `dispensedAt` = now;
  - `dispensedBy` = the hand-over user (overwriting the preparer);
  - the merged verification columns (rule 5);
  - `updatedAt`.
- `preparedBy` is kept.
- The prescription status is recomputed (DISPENSED or PARTIALLY_FILLED) and the
  prescriber is notified, as today.
- The back-order and approved-refill close-outs run.
- When the order is fully DISPENSED, the `sms.pharmacy.dispensed` receipt goes
  out after commit.
- `DISPENSE_HANDED_OVER` is audited.
- The response is 200.

**AC-5 — Hand-over is idempotent.**

- A repeated hand-over of a row that is COMPLETED **and** has
  `prepared_by IS NOT NULL` (it was prepared, so this is a replay) returns
  **200** with the same body. Nothing else happens: no audit, no SMS, no
  notification.
- A COMPLETED row with `prepared_by IS NULL` was a one-step fill and was never
  prepared. Handing it over returns **409** `dispense.ready.notPending`.
- Two concurrent hand-overs: exactly one runs the side effects.

**AC-6 — Hand-over re-checks the order and the patient.**

| Situation | Response |
|---|---|
| The prescription, re-read under the lock, is no longer in `DISPENSABLE_STATUSES` | **409** `dispense.ready.prescriptionNotDispensable` |
| The lot has expired | **400**, as the one-step path; never overridable |
| A supplied patient scan does not match | **400**, as the one-step path; never overridable |
| `ControlledSubstanceGuard.requireDispensable` now fails | **400**, as the one-step path |

In every case nothing changes.

### US-3 — The pharmacist cancels a preparation

**AC-7 — Cancel preparation.**

- *Given* a PENDING dispense.
- *When* a pharmacist sends `POST /pharmacy/dispense/{id}/cancel-ready` with a
  `reason` of STOCK_UNAVAILABLE, PATIENT_DECLINED, NOT_COLLECTED or OTHER.
- *Then*:
  - one conditional UPDATE sets `status=CANCELLED`, `updatedAt` and
    `cancelReason`;
  - the stock is returned (a RETURN stock transaction) — for
    STOCK_UNAVAILABLE as well;
  - the prescription is unchanged;
  - `DISPENSE_READY_CANCELLED` is audited with the reason code;
  - after commit, `sms.pharmacy.readyCancelled` is sent.
- A row that is not PENDING → **409** `dispense.ready.notPending`.
- A cancel racing a hand-over: exactly one wins.
- The existing `POST /{id}/cancel` keeps refusing PENDING (`:680-682`).

### US-4 — A withdrawn or changed order is never collected

**AC-8 — Withdrawal voids the preparation.**

- *Given* a prescription with a PENDING fill.
- *When* the prescriber moves it to CANCELLED or DISCONTINUED.
- *Then*, in the same transaction and under the same prescription row lock
  (rule 1):
  - the fill is cancelled with reason PRESCRIPTION_WITHDRAWN;
  - its stock is returned;
  - the void is audited.
- After commit, `sms.pharmacy.readyCancelled` is sent.
- A later hand-over → **409**.
- The patient's views show no readiness.
- The same holds for a super-admin withdrawing in global view.

**AC-9 — Any edit voids the preparation.** *Decided by the user.*

- **Any** successful `PUT /prescriptions/{id}` on a non-withdrawn order with an
  open preparation voids it, with reason PRESCRIPTION_CHANGED.
- The effects and the SMS are the same as AC-8.

**AC-10 — Routing elsewhere is blocked while prepared.**

- While a PENDING fill is open, these return **409**
  `dispense.ready.openPreparation` and change nothing:
  - stock-out routing (partner, print, back order);
  - SMS dispatch, including the TRANSMISSION_FAILED retry;
  - a clarification request.
- Each of these writes first loads the prescription with `findByIdForUpdate`
  and only then checks for an open preparation (rule 1, N1). A ready racing a
  route-to-partner therefore cannot commit alongside it.

### US-5 — The pharmacist sees what is waiting

**AC-11 — Work queue.**

- The flag reaches the portal through `GET /pharmacy/dispense/settings` (A4).
- Each row with an open preparation carries `readyForCollection`, made of
  `dispenseId`, `readyAt`, `preparedByName`, `quantity`, `unit` and
  `reminderSentAt`.
- When `readyAt` is older than `pharmacy.ready-for-collection.uncollected-after`
  (default P7D), and no higher-precedence reason applies, the row's
  `attentionReason` is `READY_UNCOLLECTED`.
- In the portal:
  - the row shows a "Ready for collection" badge with its age;
  - **Hand over** and **Cancel preparation** replace **Dispense** and **Route**;
  - **Mark ready** shows only when `readyForCollectionEnabled` is true;
  - in recent dispenses, a PENDING row is labelled "Ready for collection" and
    has no plain Cancel.

**AC-11b — The portal does not count a preparation as a fill.**

- In `prescriptions.ts`, `countableFills` (`:1134`) and the routing-snapshot
  `find` (`:1047`) exclude PENDING as well as CANCELLED.
- The sum at `:1109` follows, because it reads `countableFills`.

### US-6 — The patient sees it

**AC-12 — Readiness on the medication reads.**

- These reads carry `readyForCollectionAt` and `readyForCollectionPharmacyName`
  on each row whose prescription has an open PENDING fill:
  - `GET /me/patient/medications`;
  - `GET /me/patient/prescriptions`;
  - the proxy medications read;
  - the staff `GET /patients/{id}/medications`.
- The fields are null:
  - when there is no open preparation;
  - always, for a withdrawn prescription;
  - after hand-over or cancel.
- The patient portal (`my-medications`), Android and iOS show "Ready for
  collection at {pharmacy}" with the time.
- Old app builds ignore the fields.

**AC-13 — One reminder.** *Decided by the user.*

- A PENDING fill not collected after P3D gets exactly one
  `sms.pharmacy.readyReminder`, sent by a daily sweep that holds a ShedLock
  lock and claims each row before sending.
- No reminder is sent:
  - after the fill is handed over, cancelled or withdrawn;
  - when `pharmacy.ready-for-collection.reminder.enabled=false`.
- No automatic cancel.

### Cross-cutting

**AC-14 — Tenancy and roles.**

- On hand-over and on cancel-ready, these three must answer 404
  `dispense.notfound` with identical response bodies:
  - a dispense in another hospital;
  - a random id;
  - a null-scope caller.
- The test asserts that `status`, `error` and `message` are equal (A2). It
  excludes `timestamp`, which differs on every call, and `path`, which contains
  the id; `errorBody` writes both.
- Role gates are asserted by reflection: the `@PreAuthorize` value on each new
  controller method equals the one on `dispense()`. No slice test is needed
  (see the webmvctest-slice-scanning lesson).

**AC-15 — The client cannot set the dispense status.**

- `POST /pharmacy/dispense` with `status` PENDING or CANCELLED → **400**
  `dispense.status.notAssertable`.
- COMPLETED, PARTIAL, or no status behave as today.

**AC-16 — i18n.**

- Every new SMS and UI string exists in EN, FR and ES.
- These gates pass:
  - `npm run i18n:parity`, `i18n:referenced`, `i18n:enums` and `i18n:translated`;
  - the backend bundle parity;
  - the Android and iOS string parity.

**AC-17 — Feature flag** (B6).

- With `pharmacy.ready-for-collection.enabled=false`:
  - `POST /ready` → **404**;
  - `GET /pharmacy/dispense/settings` returns `{"readyForCollectionEnabled":false}`,
    and the portal hides **Mark ready**;
  - hand-over and cancel-ready still work.
- The reminder sweep has its own flag.

**AC-18 — Migration and schema.**

- V178 applies on Postgres (`LiquibaseSchemaIT`).
- V178 is registered (`MigrationRegistrationTest`).
- `EntitySchemaValidationIT` passes.
- The partial indexes are **not** declared as JPA `@Index` (B3).

## 5. Business rules & edge cases

**Dispense states.** New transitions are in bold.

```
(ready)            -> **PENDING**
PENDING            -> **COMPLETED**   (hand-over)
PENDING            -> **CANCELLED**   (cancel-ready | withdrawal void | edit void)
(one-step)         -> COMPLETED | PARTIAL        (unchanged)
COMPLETED|PARTIAL  -> CANCELLED                  (unchanged, /cancel)
```

The prescription status moves only at hand-over, through
`updatePrescriptionStatusFromHistory`.

### 1. One lock rule (B1)

**Every path that creates, moves or voids a prepared fill first takes
`PrescriptionRepository.findByIdForUpdate`** (`repository/PrescriptionRepository.java:120-122`,
PESSIMISTIC_WRITE). Under that lock it re-checks the prescription status and
`existsOpenPreparation`. The paths are:

| Path | Lock taken |
|---|---|
| `ready` | Replaces the `findById` in `loadAndValidatePrescription` for this path |
| One-step `POST /pharmacy/dispense` | Same method, so both paths take it; the open-preparation check runs under it |
| `hand-over` and `cancel-ready` | Load the dispense id → prescription id without a lock; take the prescription lock; then run the conditional UPDATE on the dispense |
| `PrescriptionServiceImpl.updatePrescription` | Changes `findById` (`:808`) to `findByIdForUpdate`, so withdrawal and edit hold the prescription before voiding |
| Writes that route the order away, or ask a question about it, while it might be prepared (N1): `StockOutRoutingServiceImpl.routeToPartner` (`:203`), `printForPatient` (`:274`), `backOrder` (`:312`), and `PrescriptionClarificationService` request-clarification | Load through `PrescriptionRepository.findByIdForUpdate` **before** the `existsOpenPreparation` guard. Routing: `findPrescriptionForWrite` (`:587`) uses the locked read instead of `findPrescription`'s `findById` (`:562`). Clarification: the write path's `findInScope` (`:158`) gets a locked variant (reads keep `findById`). SMS dispatch already locks (`PrescriptionSmsDispatchServiceImpl.java:136`) and only gains the guard |

**Lock order is always prescription, then dispense.** This removes the
deadlock between hand-over (which would otherwise lock dispense, then
prescription) and withdrawal (prescription, then dispense).

**What this closes:**

- ready vs. withdrawal. Ready never writes the prescription, so `@Version`
  never fires there;
- ready vs. one-step dispense. The partial index does not cover COMPLETED rows;
- ready vs. ready;
- ready vs. routing to a partner, print, back order or a clarification request
  (N1). Each of those writes the prescription, but ready does not, so without
  the lock both could commit.

**Lock and optimistic-lock failures map to 409.** Add two
`GlobalExceptionHandler` entries:

- `PessimisticLockingFailureException`, which includes `CannotAcquireLockException`
  and Postgres deadlock detection;
- `ObjectOptimisticLockingFailureException` / `OptimisticLockingFailureException`.

Both return 409 with the generic key `concurrent.modification` (EN, FR, ES
below). Today both fall to the `RuntimeException` handler at `:350`.

**How every 409 is thrown (A3).** Every 409 in this feature is
`new ConflictException(MessageUtil.resolve(key))`.

- `GlobalExceptionHandler.handleConflictException` (`:46-57`) splits a message
  on its first `:` into a `field` and the message. A colon in the translated
  text would therefore cut the sentence in two.
- So no 409 message text, in any locale, contains a colon. See the reworded
  `dispense.ready.openPreparation`.

### 2. PENDING is not a fill

- Backend: change `sumQuantityDispensedForPrescription` to
  `status NOT IN :excluded` with `{CANCELLED, PENDING}` at both callers
  (`:870`, `StockOutRoutingServiceImpl.java:655`).
- Portal: AC-11b.

### 3. The prepared quantity is fixed

Hand-over takes no quantity. To change it, cancel and prepare again, or use
the one-step dispense.

### 4. Hand-over writes COMPLETED

This is what the portal's one-step path writes; it never sends PARTIAL. A
partial fill shows on the prescription as PARTIALLY_FILLED.

### 5. Verification is split by who is present

**At ready**, the full `DispenseVerificationService.verify` runs with the
product scan: DRUG, EXPIRY and a PATIENT scan if one is sent (none expected).
CDS also runs, and an override reason is accepted. The resulting
`verificationStatus`, `productScanValue`, `scanVerifiedAt` and
`verificationOverrides` are stored.

**At hand-over**, only **EXPIRY** and **PATIENT** are evaluated: the lot's
expiry against now, and the supplied patient scan. DRUG is not re-evaluated.
Both failures are refusals and never overridable.

**Merge rule** for the stored row:

- `verificationStatus`:
  - OVERRIDDEN stays OVERRIDDEN;
  - otherwise VERIFIED if a scan was supplied at either step;
  - otherwise NOT_VERIFIED.
- `patientScanValue` takes the hand-over scan, if supplied.
- `scanVerifiedAt` is the latest scan time.
- `verificationOverrides` and `verification_override_reason` are unchanged.
  V138's `ck_dispense_override_reason` (`V138__dispense_verification.sql:111-115`)
  requires a non-blank reason whenever the status is OVERRIDDEN, and the
  preserved values satisfy it.

NOT_VERIFIED is the honest default when no scan exists at either step, exactly
as on the one-step path (`Dispense.java:184-193`).

### 6. Hand-over re-checks under the lock

The status of `DISPENSABLE_STATUSES` and `ControlledSubstanceGuard` are
evaluated on the locked prescription.

### 7. Conditional transitions (B2)

**Hand-over and cancel-ready are each one `@Modifying(flushAutomatically = true,
clearAutomatically = false)` JPQL UPDATE … `WHERE id=:id AND status=PENDING`.**

The persistence context is **not** cleared (A1). Clearing it mid-transaction
would detach `existing` and everything else that `updatePrescription` still
uses after `:859`. Instead, only that one row is resynced: if the `Dispense` is
managed, call `entityManager.refresh(dispense)` right after the update;
otherwise detach it and re-read it.
It sets every column the transition changes:

- **hand-over**: `status`, `dispensedAt`, `dispensedBy`, `verificationStatus`,
  `patientScanValue`, `scanVerifiedAt`, `updatedAt`;
- **cancel-ready**: `status`, `cancelReason`, `updatedAt`.

`updatedAt` must be set explicitly. Bulk JPQL skips the `@PreUpdate` in
`BaseEntity` (`model/BaseEntity.java:39-40`).

**No code mutates the managed `Dispense` between the update and the refresh.**
The entity has no `@DynamicUpdate`, so an unrefreshed managed copy would flush
its stale PENDING back.

If the update count is 0:

- re-read the row;
- replay rules as in AC-5;
- otherwise 409.

The response DTO is built from the **refreshed or re-read** row.

**How hand-over and cancel-ready find the prescription to lock (A8).** They
use a scalar query, `select d.prescription.id from Dispense d where d.id = :id`,
and do not rely on the LAZY `prescription` mapping.

- An empty result is the identical 404.
- The scope check comes next, then the lock.

Ready keeps `idempotencyKey` through the existing pre-check and race wrapper.

### 8. Withdrawal and edit void the preparation

`voidPreparedFill` lives in a **small dedicated component**,
`service/pharmacy/PreparedFillVoider.java`, modelled on
`partner/WithdrawnOrderPartnerHandler.java`.

- `updatePrescription` calls it next to `closePartnerOffersOnWithdrawal`
  (`:855-860`):
  - on the way into withdrawal (the `:676` predicate), with
    PRESCRIPTION_WITHDRAWN;
  - on any other successful edit of a non-withdrawn order, with
    PRESCRIPTION_CHANGED (user decision 1).
- It does **not** call `requireHospitalScopeForWrite` or `enforceHospitalScope`.
  The prescriber's transaction is already authorised and may be a global-view
  super-admin.
- It is the **only** implementation of the conditional cancel, the stock
  RETURN, the audit and the after-commit SMS (A7).
- `cancelReady` does three things: it checks scope (identical 404), takes the
  prescription lock, then delegates to the voider with the pharmacist's reason.
- It runs in the same transaction, so a failure rolls the withdrawal back.

### 9. A patient who never comes (user decision 2)

- One reminder at P3D. The claim column is `ready_reminder_sent_at`.
- The queue cue `READY_UNCOLLECTED` appears at P7D.
- There is no automatic cancel.

### 10. Cases where no preparation can exist

- PARTNER_ACCEPTED is not dispensable, so it can never be prepared.
- A refill approval does not touch a prepared fill.

### 11. SMS (user decision 3)

All patient SMS are best-effort and sent **after commit**, through
`TransactionCallbacks.afterCommit` (`utility/TransactionCallbacks.java:41`):

- ready → `readyForCollection`;
- cancel, withdrawal or edit-void → `readyCancelled`;
- reminder → `readyReminder`;
- hand-over → the existing receipt, kept for parity.

The locale lookup and the render happen **inside** the transaction. Only plain
strings cross into the callback (see out-of-session-lazy-proxies).

The existing in-transaction `notifyDispensed` (`:342-344`) moves to the same
pattern. Today a rolled-back dispense can still send its SMS.

### 12. Patient with no phone or no first name

- No phone: the SMS is skipped, as today (`PharmacyServiceSupport.java:111-114`).
- No first name: the SMS **is sent**, with `""` as the name (`:115`).
- Either way, ready succeeds, and the portal and apps still show it.

### 13. Legacy client-written PENDING rows

V178 converts them to COMPLETED. They were already counted as fills, so their
accounting does not change. AC-15 stops new ones.

### 14. Readiness ordering and time zone

- `readyAt` is `Dispense.createdAt`, a server-local `LocalDateTime` like every
  other timestamp on this entity, serialised without an offset. The portal and
  apps render it the same way they render `dispensedAt` today.
- Any list that sorted dispenses by `dispensedAt` now meets NULL for PENDING
  rows. It must sort by `coalesce(dispensedAt, createdAt)` descending. That
  covers:
  - `lastPharmacyActionsFor` (`:792`);
  - the recent-dispenses list;
  - the portal's `eventTime(fill.dispensedAt, fill.createdAt)` (`prescriptions.ts:1050`),
    which already does this.

### 15. Payments and claims (user decision 4)

- A payment **may** be recorded against a PENDING fill. `PharmacyPaymentServiceImpl:61-70`
  does not change.
- Insurance claims stay a hand-over matter. Blocking a claim on a PENDING
  dispense is a **follow-up**, because `PharmacyClaimServiceImpl:69-78` does
  not check status today.

## 6. Architecture fit

### Data model — V178

The SQL file is `V178__dispense_ready_for_collection.sql`. Register it in
`changelog.xml` with `runOnChange="false"` and `splitStatements="true"`; it
has no DO blocks. It does the following:

1. **Backfill legacy rows.**
   `UPDATE clinical.dispenses SET status='COMPLETED' WHERE status='PENDING';`
   — rule 13. *Coordinator: before merge, run
   `SELECT status, count(*) FROM clinical.dispenses GROUP BY status` on dev and
   prod. 0 PENDING rows are expected.*
2. **Make `dispensed_at` nullable.**
   `ALTER TABLE clinical.dispenses ALTER COLUMN dispensed_at DROP NOT NULL;`
   The KPI paths filter `dispensed_at IS NOT NULL` already
   (`V105__kpi_dashboard_materialized_views.sql:64`, `KpiDashboardServiceImpl.java:216`).
3. **Add three nullable columns**, each with `ADD COLUMN IF NOT EXISTS`:
   - `prepared_by UUID NULL`, with `fk_disp_prepared_by` → `security.users(id)`;
   - `ready_reminder_sent_at TIMESTAMP NULL`;
   - `cancel_reason VARCHAR(40) NULL`.
4. **Add the partial unique index.**
   `CREATE UNIQUE INDEX IF NOT EXISTS uq_disp_one_pending_per_rx ON clinical.dispenses(prescription_id) WHERE status='PENDING';`
5. **Add a partial index for the reminder sweep.**
   `CREATE INDEX IF NOT EXISTS idx_disp_pending_created ON clinical.dispenses(created_at) WHERE status='PENDING';`
6. **Leave the partial indexes out of JPA (B3).** They are **only** in SQL.
   - Do **not** declare them as `@Index` on `Dispense`. H2 `create-drop` builds
     tables from entities, would create a **full** unique index on
     `prescription_id`, and would break every test with two fills of the same
     prescription.
   - `EntitySchemaValidationIT` (`ddl-auto=validate`) does not check indexes.
7. **CHECK constraints.**
   - `dispenses.status` has none. It is a bare `VARCHAR(30)` (`V43__pharmacy_module_phase1.sql:185`).
   - The table's CHECKs are:
     - `chk_disp_qty_positive` (`V43:198`);
     - two on `verification_status` from V138:
       - the value set `NOT_VERIFIED`/`VERIFIED`/`OVERRIDDEN` (`V138__dispense_verification.sql:107`);
       - `ck_dispense_override_reason` (`:111-115`): OVERRIDDEN requires a non-blank
         `verification_override_reason`.
   - The hand-over merge (rule 5) only writes values those constraints allow.
   - Nothing in `db/migration` constrains `prescriptions.status`.
   - *Coordinator to verify prod* with `\d clinical.dispenses`. Hand-run `R__`
     scripts are not visible in the repo.
   - **Verified by the coordinator, read-only, 2026-10-07:** prod and dev
     `clinical.dispenses` hold **0 rows** (so step 1's conversion is a no-op
     there; it stays for other environments); the only CHECKs on
     `clinical.dispenses` are `chk_disp_qty_positive`,
     `ck_dispense_verification_status` and `ck_dispense_override_reason`
     (none on `status`); `dispensed_at` is NOT NULL today;
     `clinical.prescriptions` has no CHECK constraints.

### Decision: reuse `DispenseStatus.PENDING`, add no `PrescriptionStatus` value

**Why PENDING:**

- It already means "dispense not complete".
- It has no CHECK constraint and no server writer.
- It maps one-to-one onto FHIR `MedicationDispense.status = preparation`.
- It lives on the fill, so refills and partial fills each get their own
  readiness.
- A new READY value would leave PENDING as dead weight.

**Why not a prescription status:**

- Seven gate sets would change:
  - `DISPENSABLE` (`:146`), `WORK_QUEUE` (`:162`), `NEEDS_ATTENTION` (`:170`),
    `RECOMPUTABLE_AFTER_CANCEL` (`:189`);
  - `ROUTABLE`, `DISPATCHABLE`, `CLARIFIABLE`;
  - `NOTIFIED_EVENTS` (`PrescriberPharmacyNotifier.java:35`).
- It would overwrite statuses such as PENDING_STOCK, which would then need a
  "previous status" column.
- Installed apps would show it as "unknown", because their enums are
  exhaustive mirrors.

**Why the stock moves at ready:** the bag is physically set aside. Cancel
returns it with RETURN, as `cancelDispense` does (`:686-704`).

### Backend components

| Component | Change |
|---|---|
| `enums/DispenseStatus.java` | Javadoc: PENDING means "prepared, awaiting collection" |
| New `enums/ReadyCancelReason.java` | API values STOCK_UNAVAILABLE, PATIENT_DECLINED, NOT_COLLECTED, OTHER; system values PRESCRIPTION_WITHDRAWN, PRESCRIPTION_CHANGED, refused from the API |
| `model/pharmacy/Dispense.java` | `dispensedAt` nullable (drop `@NotNull`, keep `@Builder.Default` for the one-step path); add `preparedByUser` (LAZY), `readyReminderSentAt`, `cancelReason`; **no new `@Index`** |
| `repository/pharmacy/DispenseRepository.java` | Sum query with an excluded collection; `existsByPrescription_IdAndStatus`; `findFirstByPrescription_IdAndStatus`; batched `findByPrescription_IdInAndStatus`; conditional `@Modifying(flushAutomatically = true, clearAutomatically = false)` methods, each followed by a refresh of that one row `completePreparedFill`, `cancelPreparedFill`, `claimReadyReminder`; the sweep finder |
| `service/pharmacy/DispenseService(Impl).java` | `markReadyForCollection` (wrapper and transactional body, sharing an extracted front half with create; takes the lock); `handOver`; `cancelReady`; the open-preparation guard and lock in create; the work-queue decoration |
| New `service/pharmacy/PreparedFillVoider.java` | `voidPreparedFill(prescription, reason)`. No scope calls. Shares the conditional cancel, the stock return and the SMS with `cancelReady` |
| `service/pharmacy/PharmacyServiceSupport.java` | `notifyReadyForCollection`, `notifyReadyCancelled`, `notifyReadyReminder`; after-commit sending (rule 11) |
| New `service/pharmacy/ReadyForCollectionReminderScheduler.java` | A `void` method with `@SchedulerLock` (lesson #563) and `@Scheduled(cron="${pharmacy.ready-for-collection.reminder.cron:0 0 10 * * *}")`; claims in REQUIRES_NEW, then sends; covered by `SchedulerLockCoverageTest` |
| `StockOutRoutingServiceImpl` (`findPrescriptionForWrite` `:587`, guard near `:660`), `PrescriptionSmsDispatchServiceImpl` (already locks, `:136`), `PrescriptionClarificationService` (locked write-path `findInScope`, `:158`) | Locked load, then the `existsOpenPreparation` 409 guard (N1) |
| `service/PrescriptionServiceImpl.java` | `findByIdForUpdate` at `:808`; the voider call at `:855-860` |
| `exception/GlobalExceptionHandler.java` | 409 handlers for pessimistic and optimistic lock failures (rule 1) |
| `PatientMedicationServiceImpl` (`:156`), `PrescriptionService.getPrescriptionsForPortalPatient` (from `PatientPortalServiceImpl.java:366`) | Batched readiness lookup |
| `payload/dto/medication/PatientMedicationResponseDTO.java`, `payload/dto/PrescriptionResponseDTO.java` (next to `pharmacyName` `:115`) | The two readiness fields |
| `payload/dto/pharmacy/WorkQueuePrescriptionDTO.java` | `readyForCollection` |
| `DispenseController` `GET /settings` and a new `payload/dto/pharmacy/DispenseSettingsDTO` | `{readyForCollectionEnabled}`; the same `@PreAuthorize` as the work queue (`:36`); the `Page` response does not change (A4) |
| `enums/AuditEventType.java` | Add `DISPENSE_READY`, `DISPENSE_READY_CANCELLED`, `DISPENSE_HANDED_OVER`. `DisclosureCategoryTest` must still pass |
| `controller/pharmacy/DispenseController.java` | Three new POSTs with the same `@PreAuthorize` as `dispense()`. No `@WriteAudited(skip)`, so the conventional DATA_* audit row is also written |

### API contract

All endpoints are under `/pharmacy/dispense`. Each status code has a single
meaning per endpoint.

**`POST /ready`**

- Body: `DispenseRequestDTO`. `status` must be absent; `patientScanValue` is
  ignored.
- 201 → `DispenseResponseDTO` with `status:"PENDING"`, `dispensedAt:null`, and
  the new fields `preparedBy`, `preparedByName`, `readyAt`.
- Errors:
  - 400 — not dispensable, CDS, verification, or `status` present;
  - 404 — prescription or pharmacy outside scope, or the flag is off;
  - 409 — a preparation is already open, or a concurrent change.

**`POST /{id}/hand-over`**

- Body: `{ "patientScanValue"?: string, "notes"?: string }`.
- 200 → `DispenseResponseDTO` with status COMPLETED. A replay that meets AC-5
  returns the same body.
- Errors:
  - 400 — expired lot, patient mismatch, or controlled substance;
  - 404 — `dispense.notfound`, with an identical body for every scope failure;
  - 409 — not PENDING (and not a replay), prescription not dispensable, or a
    concurrent change.

**`POST /{id}/cancel-ready`**

- Body: `{ "reason": "STOCK_UNAVAILABLE" | "PATIENT_DECLINED" | "NOT_COLLECTED" | "OTHER" }`.
- 200 → `DispenseResponseDTO` with status CANCELLED.
- Errors:
  - 400 — the reason is missing or is a system value;
  - 404 — `dispense.notfound`, with an identical body for every scope failure;
  - 409 — not PENDING, or a concurrent change.

**`POST /` (existing)**

- Unchanged, except for two new errors:
  - 400 when `status` is PENDING or CANCELLED;
  - 409 when a preparation is open.

**`GET /settings` (new, A4)**

- Same roles as the work queue.
- 200 → `{ "readyForCollectionEnabled": boolean }`.
- No body, and no other status codes beyond the standard 401 and 403.

**`GET /work-queue` (existing)**

- The `Page` response itself is unchanged.
- Each row gains `readyForCollection`, a nullable object.
- `attentionReason` can now be `"READY_UNCOLLECTED"`.

**Patient and medication reads** — `GET /me/patient/medications`,
`/me/patient/prescriptions`, the proxy medications read, and the staff
`/patients/{id}/medications` — each row gains:

- `readyForCollectionAt`: an ISO local date-time;
- `readyForCollectionPharmacyName`.

**The offline queue.** It matches only `POST …/pharmacy/dispense`
(`offline-dispense.interceptor.ts:66-70`), so the new endpoints are not queued.
A replayed one-step dispense that meets an open preparation gets 409, which
the queue already handles.

### Feature flag (B6 — coordinator decision)

- A plain property, `pharmacy.ready-for-collection.enabled`, read with `@Value`.
  - In `application.properties`:
    `pharmacy.ready-for-collection.enabled=${PHARMACY_READY_FOR_COLLECTION_ENABLED:true}`.
  - **Default ON.**
- It is **not** an `app.feature-flags` entry, for three reasons:
  - `FeatureFlagService` has no `isEnabled`;
  - `applySubscriptionPlanGate` would force the flag OFF for subscribed tenants;
  - `/feature-flags` is `permitAll` and not tenant-scoped.
- It reaches the portal through a small authenticated endpoint,
  `GET /pharmacy/dispense/settings`, with the same roles as the work queue
  (A4, coordinator decision). The endpoint returns `{readyForCollectionEnabled}`.
- The work-queue `Page` response is not changed.
- It gates `POST /ready` and the **Mark ready** button only.
- Reminders have their own flag, `pharmacy.ready-for-collection.reminder.enabled`,
  `${PHARMACY_READY_REMINDER_ENABLED:true}`.
- The windows are set by:
  - `pharmacy.ready-for-collection.reminder-after=P3D`;
  - `pharmacy.ready-for-collection.uncollected-after=P7D`.

### Portal — staff (`hospital-portal/src/app/`)

- **`services/pharmacy.service.ts`**
  - `WorkQueuePrescription` (`:297`) gains `readyForCollection?`.
  - A new `getDispenseSettings()` call. The dispensing page loads it once and keeps it in a signal.
  - `DispenseResponse` gains `preparedByName?` and `readyAt?`.
  - New calls `markReady`, `handOver` and `cancelReady`, next to `:751` and `:805`.
- **`pharmacy/dispensing.{ts,html}`**
  - A **Mark ready for collection** submit next to **Dispense** (`dispensing.html:214-226`),
    gated by the flag.
  - Row actions swap to **Hand over** (confirm, optional scan) and **Cancel
    preparation** (reason select) in place of `:334-345`.
  - In the recent list (`:408-445`), PENDING is labelled and has no plain
    Cancel; the list is sorted by `coalesce`.
  - `QUEUE_ATTENTION_REASONS` gains `READY_UNCOLLECTED`.
  - Follow the signals, keyboard and axe conventions of the
    angular-portal-component skill.
- **`prescriptions/prescriptions.ts`** (`:1047`, `:1134`): exclude PENDING, as
  AC-11b.
- **`core/enum-label.service.ts`**: relabel the `dispenseStatus.PENDING`
  fallback (`:335`, currently `'Pending'`) to `'Ready for collection'`.

### Patient portal

- `services/patient-portal.service.ts` (`:970`, `:977`): add the two fields to
  the types.
- `patient-portal/my-medications/*`: a chip. The keys go under
  `PORTAL.MEDICATIONS.*`, the namespace the component already uses
  (`my-medications.component.html:5`).

### Apps

Neither app's enums change. CI is the only Swift compiler.

- **Android.**
  - `core/models/MedicationModels.kt`: `MedicationDto` (`:9`) and
    `PrescriptionDto` (`:131`) gain `readyForCollectionAt: String? = null` and
    `readyForCollectionPharmacyName: String? = null`.
  - `features/medications/MedicationsScreen.kt` (near `:150`, `:227`): render
    the chip.
  - Strings in `res/values{,-fr,-es}/strings.xml`.
- **iOS.**
  - `Core/Models/MedicationModels.swift`: `MedicationDTO` (`:5`) and
    `PrescriptionDTO` (`:109`) gain the two fields as optionals.
  - `Features/Medications/MedicationsView.swift`: render the chip.
  - Strings in `Resources/{en,fr,es}.lproj/Localizable.strings`.

### i18n — backend SMS and errors

FR is the product default. `''` escapes an apostrophe for MessageFormat. The
SMS text is logistics only, with no clinical content. The SMS arguments are:
{0} first name, {1} medication, {2} pharmacy.

| key | EN (`messages.properties` and `messages_en.properties`) | FR | ES |
|---|---|---|---|
| `sms.pharmacy.readyForCollection` | `Hello {0}, your prescription ({1}) is ready for collection at {2}. Thank you.` | `Bonjour {0}, votre ordonnance ({1}) est prête à être retirée à {2}. Merci.` | `Hola {0}, su receta ({1}) está lista para recoger en {2}. Gracias.` |
| `sms.pharmacy.readyReminder` | `Hello {0}, your prescription ({1}) is still waiting for you at {2}. Thank you.` | `Bonjour {0}, votre ordonnance ({1}) vous attend toujours à {2}. Merci.` | `Hola {0}, su receta ({1}) todavía le espera en {2}. Gracias.` |
| `sms.pharmacy.readyCancelled` | `Hello {0}, your prescription ({1}) is no longer ready for collection at {2}. Please contact the pharmacy before you come.` | `Bonjour {0}, votre ordonnance ({1}) n''est plus prête à être retirée à {2}. Veuillez contacter la pharmacie avant de vous déplacer.` | `Hola {0}, su receta ({1}) ya no está lista para recoger en {2}. Póngase en contacto con la farmacia antes de acudir.` |
| `dispense.ready.alreadyOpen` | `This prescription already has a fill prepared for collection.` | `Cette ordonnance a déjà une délivrance préparée en attente de retrait.` | `Esta receta ya tiene una dispensación preparada para recoger.` |
| `dispense.ready.openPreparation` | `A fill is prepared for this prescription. Hand it over or cancel the preparation first.` | `Une délivrance est préparée pour cette ordonnance. Remettez-la au patient ou annulez la préparation d''abord.` | `Hay una dispensación preparada para esta receta. Entréguela o cancele la preparación primero.` |
| `dispense.ready.notPending` | `This fill is no longer waiting for collection.` | `Cette délivrance n''est plus en attente de retrait.` | `Esta dispensación ya no está pendiente de recogida.` |
| `dispense.ready.prescriptionNotDispensable` | `This prescription can no longer be handed over.` | `Cette ordonnance ne peut plus être remise au patient.` | `Esta receta ya no se puede entregar.` |
| `dispense.status.notAssertable` | `The dispense status cannot be set directly; use the ready-for-collection actions.` | `Le statut de la délivrance ne peut pas être défini directement ; utilisez les actions de retrait.` | `El estado de la dispensación no se puede definir directamente; use las acciones de recogida.` |
| `concurrent.modification` | `This record was changed by someone else at the same time. Reload and try again.` | `Cet enregistrement a été modifié par quelqu''un d''autre au même moment. Rechargez et réessayez.` | `Otra persona modificó este registro al mismo tiempo. Vuelva a cargar e inténtelo de nuevo.` |

### i18n — UI

These keys go in the portal JSON, in all three locales. The apps mirror the
patient strings.

| key | EN | FR | ES |
|---|---|---|---|
| `PHARMACY.MARK_READY` | Mark ready for collection | Marquer prête à retirer | Marcar lista para recoger |
| `PHARMACY.READY_FOR_COLLECTION` | Ready for collection | Prête à retirer | Lista para recoger |
| `PHARMACY.READY_SINCE` | Ready since {{date}} | Prête depuis le {{date}} | Lista desde el {{date}} |
| `PHARMACY.HAND_OVER` | Hand over | Remettre au patient | Entregar al paciente |
| `PHARMACY.HAND_OVER_CONFIRM` | Hand this prepared fill over to the patient? | Remettre cette délivrance préparée au patient ? | ¿Entregar esta dispensación preparada al paciente? |
| `PHARMACY.CANCEL_READY` | Cancel preparation | Annuler la préparation | Cancelar la preparación |
| `PHARMACY.CANCEL_READY_CONFIRM` | Cancel this preparation? The stock is returned and the patient is told it is no longer ready. | Annuler cette préparation ? Le stock est réintégré et le patient est informé qu'elle n'est plus prête. | ¿Cancelar esta preparación? Las existencias se reintegran y se informa al paciente de que ya no está lista. |
| `PHARMACY.CANCEL_READY_REASON.STOCK_UNAVAILABLE` | Stock missing or damaged | Stock manquant ou endommagé | Existencias faltantes o dañadas |
| `PHARMACY.CANCEL_READY_REASON.PATIENT_DECLINED` | Patient declined | Refusée par le patient | Rechazada por el paciente |
| `PHARMACY.CANCEL_READY_REASON.NOT_COLLECTED` | Not collected | Non retirée | No recogida |
| `PHARMACY.CANCEL_READY_REASON.OTHER` | Other | Autre | Otro |
| `PHARMACY.READY_SUCCESS` | Marked ready for collection. | Marquée prête à retirer. | Marcada como lista para recoger. |
| `PHARMACY.READY_FAILED` | Could not mark the prescription ready. | Impossible de marquer l'ordonnance comme prête. | No se pudo marcar la receta como lista. |
| `PHARMACY.HAND_OVER_SUCCESS` | Handed over to the patient. | Remise au patient. | Entregada al paciente. |
| `PHARMACY.HAND_OVER_FAILED` | Could not record the hand-over. | Impossible d'enregistrer la remise. | No se pudo registrar la entrega. |
| `PHARMACY.CANCEL_READY_SUCCESS` | Preparation cancelled. | Préparation annulée. | Preparación cancelada. |
| `PHARMACY.CANCEL_READY_FAILED` | Could not cancel the preparation. | Impossible d'annuler la préparation. | No se pudo cancelar la preparación. |
| `PHARMACY.ATTENTION.READY_UNCOLLECTED` | Ready for {{days}} days and not collected | Prête depuis {{days}} jours et non retirée | Lista desde hace {{days}} días y no recogida |
| `PHARMACY.PREPARED_BY` | Prepared by {{name}} | Préparée par {{name}} | Preparada por {{name}} |
| `PORTAL.ENUM.DISPENSE_STATUS.PENDING` (relabel) | Ready for collection | Prête à retirer | Lista para recoger |
| `PORTAL.ENUM.AUDIT_EVENT_TYPE.DISPENSE_READY` | Fill prepared for collection | Délivrance préparée pour retrait | Dispensación preparada para recoger |
| `PORTAL.ENUM.AUDIT_EVENT_TYPE.DISPENSE_READY_CANCELLED` | Prepared fill cancelled | Délivrance préparée annulée | Dispensación preparada cancelada |
| `PORTAL.ENUM.AUDIT_EVENT_TYPE.DISPENSE_HANDED_OVER` | Prepared fill handed over | Délivrance préparée remise | Dispensación preparada entregada |
| `PORTAL.MEDICATIONS.READY_AT` / app `medication_ready_for_collection` | Ready for collection at {{pharmacy}} | Prête à retirer à {{pharmacy}} | Lista para recoger en {{pharmacy}} |
| `PORTAL.MEDICATIONS.READY_SINCE` / app `medication_ready_since` | Since {{date}} | Depuis le {{date}} | Desde el {{date}} |

The aria-labels of the new buttons use the same keys.

### Audit and logging

- Audit descriptions carry ids, quantities and reason codes only. No
  medication name, patient name or phone number.
- Phone numbers are masked in logs.
- `entityType` is `DISPENSE` (`:118`).

## 7. Security & privacy

| Threat | Control |
|---|---|
| Guessing a dispense id in another hospital, or using the 404 body to learn that an id exists | Every scope failure on hand-over and cancel-ready returns the same `dispense.notfound` body (AC-14). The older endpoints' oracle is recorded as debt |
| A client sets PENDING through the old endpoint | AC-15; V178 converts legacy rows |
| A prepared fill is handed over after withdrawal or edit, including in the ready-vs-withdrawal race | Rule 1 lock; void under that lock (AC-8, AC-9); the hand-over re-checks under the lock |
| Double collection, or the same stock spent by both a prepared fill and a one-step fill | Rule 1 lock and re-check; the partial unique index; conditional transitions; the idempotency key |
| A deadlock surfaces as a 500 | Fixed lock order (prescription, then dispense); 409 handlers |
| A stale flush rewrites the status after a bulk update | B2 and A1: one UPDATE, then `entityManager.refresh` (or detach and re-read) of that row only, with no mutation in between |
| The wrong person collects, or expired stock is handed over | EXPIRY and PATIENT checked at hand-over, never overridable |
| PHI in the SMS | The same content class as the existing `sms.pharmacy.dispensed`; sent to the patient's own primary phone only |
| PHI in logs or audit | Ids and codes only |
| An SMS is sent for a transaction that rolled back | After-commit sending |
| A patient sees another patient's readiness | Readiness is computed only inside the existing own-record and chart-gated reads |
| An external pharmacy is marked "ready" | `requireDispensary` |
| Unauthenticated or tenant-blind flag reads | The flag is read only from `GET /pharmacy/dispense/settings`, which is authenticated and role-gated like the work queue (A4) |

## 8. Test plan

"Falsify" means revert exactly the change named; the test must then fail
(falsify-exactly-the-change).

The **Postgres ITs** are new Testcontainers classes, modelled on
`src/test/java/com/example/hms/service/EducationProgressWritesPostgresIT.java`
(`@Testcontainers`, `postgres:16-alpine`). Name: `PreparedFillConcurrencyPostgresIT`.
H2 cannot exercise partial indexes or `SELECT … FOR UPDATE` contention.

| AC | Test | Falsify by |
|---|---|---|
| AC-1 | `DispenseServiceImplTest`: PENDING; `dispensedAt` null; `dispensedBy`/`preparedBy` = caller; lot decremented; status untouched; no notifier call; `DISPENSE_READY`; after-commit SMS rendered in FR, EN and ES; lock method used | Write COMPLETED; recompute at ready; send the SMS synchronously; use `findById` |
| AC-1 | `PharmacyServiceSupportTest`: locale and swallow; a missing first name still sends with "" | Drop the try/catch |
| AC-2 | Parameterised over non-dispensable statuses, plus the controlled, expired and CDS cases: 400, no save, no SMS | Remove the status check on the ready path |
| AC-3 | Unit: second ready → 409; one-step with an open preparation → 409. **Postgres IT**: (a) two concurrent readies → 1 row and 1 × 409; (b) ready vs one-step → exactly one committed, stock decremented once; (c) a **direct JDBC insert** of a second PENDING row → unique violation | (a)/(b): remove the `findByIdForUpdate` from create/ready (the race reappears, verified by a CountDownLatch-ordered test); (c): drop the index from V178 |
| AC-4 | Unit and IT: COMPLETED; `dispensedAt`, `dispensedBy` and `updatedAt` set in the DB; `preparedBy` kept; prescription DISPENSED; notifier called; refill closed; receipt after commit | Skip the recompute; omit `updatedAt` from the UPDATE (the IT asserts it changed) |
| AC-4/B2 | IT: after hand-over the persisted status is COMPLETED (re-read in a new transaction), proving no stale flush | Remove the `refresh` and set a field on the managed entity afterwards. A companion unit test proves `existing` in `updatePrescription` is still managed after a void |
| AC-5 | Unit: a COMPLETED replay with `preparedBy` → 200 with zero side-effect interactions; COMPLETED with `preparedBy` null → 409. IT: two concurrent hand-overs → one audit row | **Accept a COMPLETED source in the conditional UPDATE and re-run the side effects** (the test sees a second audit/SMS) |
| AC-6 | Unit: the prescription under lock is not dispensable → 409; expired lot → 400; patient mismatch → 400; controlled guard re-run → 400; the verification merge follows rule 5 | Remove the locked re-check; drop OVERRIDDEN from the merge |
| AC-7 | Unit and IT: CANCELLED, `cancelReason`, RETURN transaction, cancelled SMS; non-PENDING → 409; `/cancel` refuses PENDING | Skip the stock return |
| AC-8 | `PrescriptionServiceImplTest` and **Postgres IT** "ready vs withdrawal": whichever commits first, the end state never has a PENDING fill on a withdrawn prescription; the void runs for a global-view super-admin | Remove `findByIdForUpdate` from `updatePrescription`; add a scope call to the voider (the global-view test goes red) |
| AC-9 | `PrescriptionServiceImplTest`: any edit voids with PRESCRIPTION_CHANGED | Void only on withdrawal |
| AC-10 | Routing, dispatch and clarification tests: 409 while prepared; routing and clarification writes load through `findByIdForUpdate` (N1). **Postgres IT** "ready vs route-to-partner": exactly one of the two commits | Remove each guard; restore `findById` in `findPrescriptionForWrite` (the IT race reappears) |
| AC-11 | Work-queue unit tests: batched (repository call count); READY_UNCOLLECTED at P7D and not at P6D, below status reasons; `GET /settings` reflects the property. `dispensing.spec.ts`: button swap; flag hides Mark ready; PENDING label | Drop the decoration; invert the age comparison |
| AC-11b | `prescriptions.spec.ts` (or the component spec): a PENDING fill counts in neither the dispensed-to-date sum nor the snapshot | Restore `!== 'CANCELLED'` alone |
| AC-12 | `PatientMedicationServiceImplTest` (portal and staff reads) and a portal-prescriptions test; `my-medications.component.spec.ts`; Android `MedicationModelsTest.kt`; iOS new `MediHubPatientTests/MedicationModelsTests.swift` (confirm `project.yml`) | Return readiness regardless of prescription status |
| AC-13 | `ReadyForCollectionReminderSchedulerTest` plus `SchedulerLockCoverageTest` | Remove the claim condition |
| AC-14 | Unit: foreign-hospital, random-id and null-scope → equal `status`, `error` and `message` on both endpoints, excluding `timestamp` and `path`. Reflection: each new controller method's `@PreAuthorize` value equals `dispense()`'s | Call `enforceHospitalScope` (the body becomes `pharmacy.notfound`) |
| AC-15 | Unit: PENDING or CANCELLED in the body → 400 | Remove the check |
| AC-16 | `npm run i18n:parity`, `i18n:referenced`, `i18n:enums`, `i18n:translated`; backend bundle parity; app string parity | Delete one FR key |
| AC-17 | Unit: flag off → ready 404, hand-over 200; `GET /settings` reports false | Gate hand-over on the flag |
| AC-18 | `MigrationRegistrationTest`, `LiquibaseSchemaIT`, `EntitySchemaValidationIT`; an H2 test with two fills of one prescription still passes (no full index from JPA) | Add `@Index(unique=true)` on `prescription_id` |
| Rule 1 | `GlobalExceptionHandler` unit: `PessimisticLockingFailureException` and `ObjectOptimisticLockingFailureException` → 409 `concurrent.modification` | Remove the handlers |

**Gates before every push:**

- the full backend suite, including the Docker ITs;
- Karma;
- lint and format;
- the axe smoke on `/pharmacy/dispensing`.

After any merge, check the changelog and the i18n files by hand.

## 9. Rollout

1. **V178 ships with the code.** It is additive and relaxing, plus the backfill
   and the indexes. Run the PENDING count first. No grants change.
2. **Flags.**
   - `PHARMACY_READY_FOR_COLLECTION_ENABLED` defaults to true. The feature is
     opt-in per action, so this works as a kill switch.
   - `PHARMACY_READY_REMINDER_ENABLED` defaults to true.
   - Railway needs no new variables unless the user wants the feature OFF.
3. **Compatibility.**
   - Every new field is additive and nullable.
   - Old portal bundles and installed apps ignore the new fields.
   - No mirrored enum gains a value.
4. **Rollback.**
   1. Set the flag OFF. Open preparations remain finishable.
   2. Before a code revert, hand over or cancel every row returned by
      `SELECT id, prescription_id FROM clinical.dispenses WHERE status='PENDING'`.
      Old code counts PENDING as a fill and cannot cancel it.
   3. V178 is forward-only and harmless to old code.
5. **Prod.** The develop → main sync happens only on the user's "sync". That
   sync is a prod deploy.

## 10. Out of scope / decisions / open questions

### Decided (user, 2026-10-07)

1. **Any** prescriber edit voids an open preparation (AC-9).
2. Reminder SMS at 3 days. Queue "uncollected" cue at 7 days. **No automatic
   cancel** (AC-13, rule 9).
3. Patient SMS:
   - "ready for collection" at ready;
   - "no longer ready, contact the pharmacy" on cancel, withdrawal or edit-void;
   - the receipt at hand-over, kept for parity.

   All are sent after commit (rule 11).
4. Payment **is allowed** on a prepared (PENDING) fill. Insurance claims stay
   at hand-over. Blocking a claim on PENDING is a follow-up (rule 15).

### Decided (coordinator)

5. A STOCK_UNAVAILABLE cancel returns the stock. The loss is recorded through
   the existing stock-adjustment page (ADJUSTMENT). A `returnToStock=false`
   option is a follow-up.
6. Partner and community pharmacies are out of v1.
   - A partner's ACCEPT already texts the patient "you may go there"
     (`SmsPartnerNotificationChannel.notifyPatientAccepted`, `:111`).
   - The future reply code "4 <ref>" would touch `PartnerSmsReplyParser.Action`
     (`:40`, patterns `:50` and `:53`, mapping `:134-136`) and
     `PartnerExchangeService` (`:313-346`), plus templates in 3 locales.
   - It goes on `tasklist.md` as a follow-up.
7. The feature flag is a plain property, default ON (B6).

### Out of scope, recorded as tasklist debt in T17

- The `getDispense` and `cancelDispense` scope oracle (`pharmacy.notfound` vs
  `dispense.notfound`).
- Blocking a claim on a PENDING fill.
- `returnToStock=false`.
- Partner reply code 4.
- Pharmacy SMS that respects patient preferences.
- FHIR `MedicationDispense`.

### Open, with recommended defaults

- **Tell the prescriber at ready time?** Default **no**. They hear DISPENSED or
  PARTIALLY_FILLED at hand-over (G6).
- **Offline queueing of ready and hand-over?** Default **no**.
- **Proxy (family) SMS?** Default **no**. Proxies see readiness through the
  proxy medications read.

## 11. Task list

- [x] **T1 — V178 and the entity.** AC-18, AC-3 (index).
  - `V178__dispense_ready_for_collection.sql` and `changelog.xml`.
  - `model/pharmacy/Dispense.java`:
    - make `dispensedAt` nullable;
    - add `preparedByUser`, `readyReminderSentAt` and `cancelReason`;
    - **no `@Index`**.
  - `enums/DispenseStatus.java` (Javadoc) and `enums/ReadyCancelReason.java`.
  - `MigrationRegistrationTest`, `LiquibaseSchemaIT`, `EntitySchemaValidationIT`.
- [x] **T2 — PENDING is not a fill, backend and portal.** AC-4, AC-11b.
  - `DispenseRepository` sum with an excluded collection; update the callers
    `:870` and `StockOutRoutingServiceImpl:655`.
  - New finders and the conditional `@Modifying(flushAutomatically = true,
    clearAutomatically = false)` transitions, each followed by a single-row `refresh` (A1).
  - `prescriptions/prescriptions.ts` (`:1047`, `:1134`) and its spec.
- [x] **T3 — One lock rule and the 409 mapping.** AC-3, AC-8, AC-10 (B1, N1).
  - `loadAndValidatePrescription` uses `findByIdForUpdate` for create and
    ready.
  - `PrescriptionServiceImpl.updatePrescription` (`:808`) uses `findByIdForUpdate`.
  - Routing and clarification writes use it too (N1):
    `StockOutRoutingServiceImpl.findPrescriptionForWrite` (`:587`), and a
    locked variant of `PrescriptionClarificationService.findInScope` (`:158`)
    for the request-clarification write.
  - `GlobalExceptionHandler`: pessimistic and optimistic lock failures → 409
    `concurrent.modification`, with the message in 4 bundles.
  - Handler unit test.
  - **Update the stubs this breaks, in the same commit (A6).**
    `DispenseServiceImplTest`, `PrescriptionServiceImplTest`,
    `StockOutRoutingServiceImplTest` and `PrescriptionClarificationServiceTest`
    stub `prescriptionRepository.findById`. They must stub `findByIdForUpdate`
    on the write paths; otherwise the push turns CI red.
- [x] **T4 — Client status hole and the one-step guard.** AC-15, AC-3.
  - `createDispenseTransactionally`; `DispenseMapper.java:124`.
  - Messages `dispense.status.notAssertable` and `dispense.ready.openPreparation`.
- [x] **T5 — Patient SMS and after-commit sending.** AC-1, AC-7, AC-13, AC-16.
  - `PharmacyServiceSupport`: the three new methods; move `notifyDispensed` to
    after-commit.
  - The four `messages*.properties` files; `PharmacyServiceSupportTest`.
- [x] **T6 — Mark ready and the flag.** AC-1, AC-2, AC-3, AC-17.
  - `markReadyForCollection`, with the shared front half extracted.
  - `POST /ready`; `DISPENSE_READY`, **plus `PORTAL.ENUM.AUDIT_EVENT_TYPE.DISPENSE_READY` in `assets/i18n/{en,fr,es}.json` in the same commit** (A5; `i18n:enums` reads the Java enum).
  - `GET /settings` and `DispenseSettingsDTO` (A4).
  - The `pharmacy.ready-for-collection.enabled` `@Value` and the
    `application.properties` placeholder.
  - `DispenseResponseDTO` and mapper fields.
  - Tests.
- [x] **T7 — Hand-over.** AC-4, AC-5, AC-6, AC-14.
  - `handOver`:
    - the lock;
    - the locked re-check;
    - EXPIRY and PATIENT evaluation, and the merge (rule 5);
    - one UPDATE and a re-read;
    - the replay rule;
    - the recompute and close-outs;
    - the receipt SMS;
    - `DISPENSE_HANDED_OVER`, **plus its `PORTAL.ENUM.AUDIT_EVENT_TYPE.DISPENSE_HANDED_OVER` key ×3 in the same commit** (A5).
  - Identical 404 bodies; `HandOverRequestDTO`; the controller.
  - Unit tests and the reflection role test.
- [x] **T8 — Cancel preparation and the voider.** AC-7, AC-8, AC-9, AC-14.
  - `cancelReady`: the scope check (identical 404), the lock, then delegate.
  - The new `PreparedFillVoider`, the only implementation of the conditional
    cancel, the stock return and the SMS (A7). It makes no scope calls.
  - `DISPENSE_READY_CANCELLED`, **plus its
    `PORTAL.ENUM.AUDIT_EVENT_TYPE.DISPENSE_READY_CANCELLED` key ×3 in the same
    commit** (A5).
  - `CancelReadyRequestDTO`; the controller.
  - Wire the voider in `PrescriptionServiceImpl` (`:855-860`, any edit and any
    withdrawal).
  - `PrescriptionServiceImplTest`.
- [x] **T9 — Postgres concurrency IT.** AC-3, AC-5, AC-8, AC-10, AC-4/B2.
  - New `PreparedFillConcurrencyPostgresIT`, modelled on
    `EducationProgressWritesPostgresIT`.
  - Covers:
    - ready vs ready;
    - ready vs one-step;
    - ready vs withdrawal;
    - **ready vs route-to-partner (N1)**;
    - hand-over vs hand-over;
    - the direct-insert index check;
    - the persisted state after hand-over.
- [ ] **T10 — Guards while prepared.** AC-10.
  - The `existsOpenPreparation` 409 guard, placed **after** the locked load from
    T3, in `StockOutRoutingServiceImpl`, `PrescriptionSmsDispatchServiceImpl`
    and `PrescriptionClarificationService`, with their tests.
- [ ] **T11 — Work queue.** AC-11.
  - `WorkQueuePrescriptionDTO.readyForCollection`. The `Page` response is
    unchanged.
  - Batched decoration.
  - `READY_UNCOLLECTED`, at the lowest precedence.
  - `coalesce(dispensedAt, createdAt)` in `lastPharmacyActionsFor` (`:792`).
  - The `uncollected-after` property; tests.
- [ ] **T12 — Patient and staff medication reads.** AC-12.
  - `PatientMedicationResponseDTO`, `PrescriptionResponseDTO`,
    `PatientMedicationServiceImpl` (portal and staff),
    `getPrescriptionsForPortalPatient`; tests.
- [ ] **T13 — Reminder sweep.** AC-13.
  - `ReadyForCollectionReminderScheduler` (void, `@SchedulerLock`, claim
    REQUIRES_NEW, then send); properties; `SchedulerLockCoverageTest`; test.
- [ ] **T14 — Portal pharmacy UI.** AC-11, AC-16, AC-17.
  - `services/pharmacy.service.ts`; `pharmacy/dispensing.{ts,html,scss,spec.ts}`.
  - `core/enum-label.service.ts:335`.
  - `assets/i18n/{en,fr,es}.json`: the `PHARMACY.*` keys and
    `PORTAL.ENUM.DISPENSE_STATUS.PENDING`. The `AUDIT_EVENT_TYPE` keys
    already landed in T6, T7 and T8.
  - The settings call.
  - Pass `i18n:enums` and `i18n:translated`.
- [ ] **T15 — Patient portal.** AC-12, AC-16.
  - `services/patient-portal.service.ts`; `patient-portal/my-medications/*`;
    the `PORTAL.MEDICATIONS.*` keys.
- [ ] **T16 — Android.** AC-12.
  - `MedicationModels.kt`, `MedicationsScreen.kt`, `strings.xml` ×3,
    `MedicationModelsTest.kt`.
- [ ] **T17 — iOS.** AC-12.
  - `MedicationModels.swift`, `MedicationsView.swift`, `Localizable.strings`
    ×3, `MedicationModelsTests.swift`.
- [ ] **T18 — Bookkeeping.**
  - `tasklist.md`: mark G15 (`:3693`) done, with the PR number.
  - Add the debt items from section 10 ("Out of scope").
  - Update the `PharmacyServiceSupport` Javadoc (`:89-103`).
