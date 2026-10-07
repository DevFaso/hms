# Plan — pharmacy "ready for collection" (audit gap G15)

Branch `feat/pharmacy-ready-for-collection` off `origin/develop` 4d3f17793.
Next free migration: **V178**. Every file:line below was read on that commit.
Java paths are relative to `hospital-core/src/main/java/com/example/hms/`
unless they say otherwise.

---

## 1. Summary

A hospital pharmacist can only record a fill at hand-over, so the patient
learns about their medication when it is already in their hand. This change
adds a two-step in-house fill:

1. The pharmacist **prepares** the fill. Stock comes off the shelf and is set
   aside.
2. The patient is told the fill is **ready for collection**, by SMS and in the
   patient portal and both mobile apps.
3. A later **hand-over** completes the fill exactly as today's one-step
   dispense does.

The prepared fill is a `Dispense` row in the existing, never-written
`DispenseStatus.PENDING`. The prescription's own status does not change.

- The pharmacist can cancel a preparation.
- It is voided automatically when the prescriber withdraws or edits the order,
  so a withdrawn prescription can never be collected.
- A patient who has not come after 3 days gets one reminder.
- The one-step dispense is unchanged.

Partner and community pharmacies are out of scope for v1 (section 10).

## 2. Business context

**Problem.** The patient's one pharmacy SMS goes out after hand-over:

- `PharmacyServiceSupport.notifyDispensed` (`service/pharmacy/PharmacyServiceSupport.java:104-123`)
  fires only once the prescription is fully DISPENSED
  (`service/pharmacy/DispenseServiceImpl.java:341-344`). Its own Javadoc
  (`:89-103`) says there is no "ready" state: "`DispenseStatus.PENDING` is
  never written".
- The SMS used to say "ready for pickup". #717 and #790 changed it to "has
  been dispensed" so that it stopped describing something that had not
  happened. The `sms.pharmacy.dispensed` keys are at:
  - `hospital-core/src/main/resources/messages_fr.properties:1168`
  - `messages.properties:1186`
  - `messages_en.properties:1158`
  - `messages_es.properties:1103`
- `tasklist.md:3693` still lists G15 as open and unowned. `tasklist.md:3560`
  says "a real 'ready for collection' state is a product decision".

**Current behaviour, in code.**

- **Dispense status.** `enums/DispenseStatus.java` has PENDING, COMPLETED,
  PARTIAL and CANCELLED. No server code writes PENDING. The entity default is
  COMPLETED (`model/pharmacy/Dispense.java:126-130`).
- **The client can write PENDING today.**
  - `DispenseRequestDTO.status` (`payload/dto/pharmacy/DispenseRequestDTO.java:69`)
    is copied onto the row as sent (`mapper/pharmacy/DispenseMapper.java:124`).
  - The fill-quantity sum excludes only CANCELLED
    (`repository/pharmacy/DispenseRepository.java:53-56`). It is used at
    `DispenseServiceImpl.java:870` and `StockOutRoutingServiceImpl.java:655`.
  - So a PENDING row posted by a client already counts as a fill.
  - The portal never sends `status` (`hospital-portal/src/app/pharmacy/dispensing.ts:325-352`).
- **One-step dispense.** `createDispense` (`DispenseServiceImpl.java:226-270`)
  wraps `createDispenseTransactionally` (`:274-361`), which runs in this order:
  1. Tenant and prescription checks: `loadAndValidatePrescription`
     (`:363-393`), which uses `DISPENSABLE_STATUSES` (`:146-153`) and
     `ControlledSubstanceGuard.requireDispensable`.
  2. The CDS check.
  3. Verification: `applyVerificationOutcome` (`:516-560`).
  4. The stock decrement: `consumeStockLot` (`:454-495`).
  5. The row insert.
  6. The prescription-status recompute: `updatePrescriptionStatusFromHistory`
     (`:867-900`), which notifies the prescriber through `PrescriberPharmacyNotifier`.
  7. The back-order and refill close-outs.
  8. The SMS.
  9. The audit event `DISPENSE_CREATED` (`enums/AuditEventType.java:99`).
- **Cancel.** `cancelDispense` (`:665-730`) accepts only COMPLETED and PARTIAL.
  It returns stock with a RETURN stock transaction, and recomputes the
  prescription status only from `RECOMPUTABLE_AFTER_CANCEL` (`:189-194`).
- **Work queue.** `getWorkQueue` (`:733-746`) lists `WORK_QUEUE_STATUSES`
  (`:162-164`). `attentionReason` (`:1035-1046`) checks, in order,
  `NEEDS_ATTENTION_STATUSES` (`:170-175`), then BACK_ORDER_OUTSTANDING, then
  CLARIFICATION_RESOLVED.
- **Withdrawal (#812).**
  - `PrescriptionStatus.isWithdrawn()` means CANCELLED or DISCONTINUED
    (`enums/PrescriptionStatus.java:40-42`).
  - Withdrawal is final (`service/PrescriptionServiceImpl.java:641-645`).
  - Partner offers are closed on the way in: `closePartnerOffersOnWithdrawal`
    (`:672-680`), called from `updatePrescription` at `:859`. This plan
    extends that hook.
- **TRANSMISSION_FAILED (#818)** is:
  - dispensable in-house (`DISPENSABLE_STATUSES`, `:152`);
  - routable (`service/pharmacy/StockOutRoutingServiceImpl.java:97-105`);
  - dispatchable (`service/impl/PrescriptionSmsDispatchServiceImpl.java:84-91`);
  - clarifiable (`service/pharmacy/PrescriptionClarificationService.java:73-80`).
- **The patient read surface shows no fill.**
  - `GET /me/patient/medications` (`controller/PatientPortalController.java:156`)
    is built by `PatientMedicationServiceImpl.toResponse` (`service/impl/PatientMedicationServiceImpl.java:156-185`).
    Its `resolveStatus` (`:239-250`) collapses every status to ACTIVE,
    ON_HOLD, DISCONTINUED or COMPLETED.
  - `GET /me/patient/prescriptions` (`:168`) is built by
    `PatientPortalServiceImpl.getMyPrescriptions` (`service/impl/PatientPortalServiceImpl.java:362-369`),
    which returns the raw `PrescriptionStatus`.
- **The apps mirror `PrescriptionStatus` exhaustively.** Android has it at
  `patient-android-app/.../core/models/MedicationModels.kt:163`, iOS at
  `patient-ios-app/MediHubPatient/Core/Models/MedicationModels.swift:140`. An
  installed build shows any value it does not know as "unknown".

**Desired outcome.**

- The patient is told, in their language, when the medication is actually
  waiting for them, and sees the same fact in the portal and the apps.
- The pharmacist's queue shows what is prepared and how long it has waited.
- Hand-over closes the fill exactly as today: the prescriber notification,
  the back-order and refill close-outs, and the receipt SMS.
- Nothing prepared can be handed over against an order that was withdrawn,
  edited, or is otherwise not dispensable.

## 3. Actors & permissions

| Actor | May | Gate |
|---|---|---|
| PHARMACIST, PHARMACY_VERIFIER, HOSPITAL_ADMIN, and SUPER_ADMIN *with a pinned hospital* | Mark ready, hand over, cancel a preparation | Same `@PreAuthorize` as `POST /pharmacy/dispense` (`controller/pharmacy/DispenseController.java:46`). Every write also needs a non-null `roleValidator.requireActiveHospitalId()`, as `requireHospitalScopeForWrite` does (`DispenseServiceImpl.java:1129-1133`) |
| The same roles | See the preparation on the work queue | `GET /pharmacy/dispense/work-queue` (`DispenseController.java:36`) |
| DOCTOR, NURSE, MIDWIFE (existing read roles) | See the PENDING row in a prescription's or patient's dispense history | Existing `listByPrescription` and `listByPatient` gates (`:68`, `:80`). Nothing new |
| Prescriber | Withdraw or edit the order; either one voids the preparation automatically | Existing `updatePrescription` rules. No new endpoint |
| Patient (ROLE_PATIENT), and a verified proxy with medication access | See "ready for collection at <pharmacy> since <time>" on their own medications and prescriptions; receive the SMS | `PatientChartAccess.requireOwnRecord`, already on these reads. SMS goes to `patient.phoneNumberPrimary` only |
| STORE_MANAGER | Nothing new. Listing dispenses by pharmacy (`:92`) will show PENDING rows | — |

**Tenancy and refusals** follow the conventions already in `DispenseServiceImpl`:

- A dispense whose pharmacy belongs to another hospital returns 404
  `dispense.notfound`, which looks the same as a missing row
  (`enforceHospitalScope`, `:1156-1166`).
- A prescription in another hospital returns 404 `prescription.notfound`
  (`:370-376`).
- A null scope (global view) returns 404 on every write.
- Ready is refused for any pharmacy that is not a `HOSPITAL_DISPENSARY`
  (`requireDispensary`, `:1147-1154`). A partner or community pharmacy can
  never be prepared in-house.
- A patient calling a staff endpoint gets 403 from `@PreAuthorize`.

## 4. User stories with acceptance criteria

### US-1 — The pharmacist prepares a fill and the patient is told

**AC-1. Mark ready.**

- *Given* a prescription at the active hospital in SIGNED, TRANSMITTED,
  PARTIALLY_FILLED, PENDING_STOCK, PARTNER_REJECTED or TRANSMISSION_FAILED,
  and an in-house dispensary.
- *When* a pharmacist sends `POST /pharmacy/dispense/ready` with a quantity,
  an optional lot and an optional product scan.
- *Then:*
  - a `Dispense` row is created with `status=PENDING`, `dispensedAt=null`,
    `preparedBy` set to the caller, and `createdAt` as the ready time;
  - the lot and the inventory are decremented, with a DISPENSE stock
    transaction;
  - the prescription status does not change;
  - no prescriber notification is written;
  - `DISPENSE_READY` is audited;
  - after commit, one `sms.pharmacy.readyForCollection` SMS goes to the
    patient's primary phone, in the patient's locale;
  - the response is 201 with the PENDING dispense.

**AC-2. Ready is refused when the order cannot be dispensed.**

- *Given* a prescription in CANCELLED, DISCONTINUED, PENDING_CLARIFICATION,
  PARTNER_ACCEPTED, SENT_TO_PARTNER, DISPENSED, DRAFT or PENDING_SIGNATURE.
- *When* ready is requested.
- *Then* it is refused with the same error as today's dispense: "not in a
  dispensable state" (`:380-382`). No row is written, no stock moves, no SMS
  goes out.
- The same applies, with today's dispense errors, to:
  - a controlled substance without completed 2FA, or a missing co-signature;
  - an expired lot;
  - a CRITICAL CDS alert sent without an override reason.

**AC-3. Only one open preparation per prescription.**

- *Given* a prescription with an open PENDING fill:
  - a second ready returns 409 `dispense.ready.alreadyOpen`;
  - `POST /pharmacy/dispense` returns 409 `dispense.ready.openPreparation`.
- Two concurrent readies leave exactly one row. The V178 partial unique index
  rejects the second, and that rejection returns 409, not 500.

### US-2 — The patient collects and the pharmacist hands over

**AC-4. Hand-over completes the fill.**

- *Given* a PENDING dispense.
- *When* a pharmacist sends `POST /pharmacy/dispense/{id}/hand-over`, with an
  optional patient wristband scan.
- *Then:*
  - the row becomes COMPLETED, with `dispensedAt` = now and `dispensedBy` =
    the hand-over user; `preparedBy` is kept;
  - the prescription status is recomputed to DISPENSED or PARTIALLY_FILLED,
    and the prescriber is notified as today;
  - the back-order and approved-refill close-outs run as today;
  - the existing `sms.pharmacy.dispensed` receipt goes out after commit when
    the order is fully DISPENSED;
  - `DISPENSE_HANDED_OVER` is audited.

**AC-5. Hand-over is idempotent.**

- Repeating a hand-over on a dispense that is already COMPLETED returns 200
  with the same body. No second audit row, SMS or notification is written.
- Two concurrent hand-overs: exactly one of them runs the side effects.

**AC-6. Hand-over re-checks safety.** Each case below returns 409 or the
one-step path's error, and changes nothing:

- the prescription is no longer in `DISPENSABLE_STATUSES` (409);
- the lot expired while the fill was waiting (never overridable, as in the
  one-step path);
- a patient scan was sent and does not match (never overridable);
- `ControlledSubstanceGuard.requireDispensable` now fails.

### US-3 — The pharmacist cancels a preparation

**AC-7. Cancel preparation.**

- *Given* a PENDING dispense.
- *When* a pharmacist sends `POST /pharmacy/dispense/{id}/cancel-ready` with
  `reason` set to STOCK_UNAVAILABLE, PATIENT_DECLINED, NOT_COLLECTED or OTHER.
- *Then:*
  - the row becomes CANCELLED and the stock is returned with a RETURN stock
    transaction;
  - the prescription status does not change;
  - `DISPENSE_READY_CANCELLED` is audited with the reason code;
  - after commit, the patient gets `sms.pharmacy.readyCancelled`.
- On a row that is not PENDING, cancel-ready returns 409.
- A cancel that races a hand-over: exactly one of the two wins.
- The existing `POST /{id}/cancel` keeps refusing PENDING rows (`:680-682`).

### US-4 — A withdrawn or changed order is never collected

**AC-8. Withdrawal voids the preparation.**

- *Given* a prescription with a PENDING fill.
- *When* the prescriber moves it to CANCELLED or DISCONTINUED.
- *Then*, in the same transaction:
  - the PENDING fill is cancelled with reason PRESCRIPTION_WITHDRAWN;
  - the stock is returned;
  - the cancellation is audited.
- After commit, the patient gets `sms.pharmacy.readyCancelled`.
- Any later hand-over of that dispense returns 409, and the patient read
  surface shows no readiness.

**AC-9. An edit voids the preparation.** The same as AC-8, with reason
PRESCRIPTION_CHANGED, when the prescriber edits the order through
`PUT /prescriptions/{id}` while a fill is prepared. The prepared bag matches
the old drug or dose.

**AC-10. Other routes are blocked while a fill is prepared.** Each of these
returns 409 `dispense.ready.openPreparation` and changes nothing:

- stock-out routing (partner, print or back order);
- SMS dispatch to a community pharmacy, including the TRANSMISSION_FAILED
  retry;
- a pharmacist clarification request.

### US-5 — The pharmacist sees what is waiting

**AC-11. Work queue.**

- The row of a prescription with an open preparation carries
  `readyForCollection`, an object with `dispenseId`, `readyAt`,
  `preparedByName`, `quantity`, `unit` and `reminderSentAt`.
- When `readyAt` is older than `pharmacy.ready-for-collection.uncollected-after`
  (default P7D) and no stronger reason applies, `attentionReason` is
  `READY_UNCOLLECTED`.
- In the portal:
  - the row shows a "Ready for collection" badge with the age;
  - **Hand over** and **Cancel preparation** replace **Dispense** and
    **Route**;
  - the recent-dispenses list labels PENDING as "Ready for collection" and
    does not offer the plain Cancel on it.

### US-6 — The patient sees it

**AC-12. Patient read surface.**

- On `GET /me/patient/medications`, `GET /me/patient/prescriptions` and the
  proxy medications read, each row whose prescription has an open PENDING
  fill carries `readyForCollectionAt` and `readyForCollectionPharmacyName`.
- Both fields are null:
  - when there is no open preparation;
  - always, for a withdrawn prescription;
  - again, after hand-over or cancel.
- The patient portal (`my-medications`), Android and iOS show "Ready for
  collection at {pharmacy}" with the time.
- Older app builds ignore the new fields.

**AC-13. One reminder.**

- A PENDING fill not collected after `pharmacy.ready-for-collection.reminder-after`
  (default P3D) gets exactly one `sms.pharmacy.readyReminder`.
- It is sent by a daily sweep guarded by ShedLock, which claims the row
  before sending.
- No reminder goes out:
  - for a fill that was handed over, cancelled or withdrawn;
  - when `pharmacy.ready-for-collection.reminder.enabled=false`.

### Cross-cutting

**AC-14. Tenancy and roles.**

- A dispense id from another hospital returns 404 on hand-over and on
  cancel-ready.
- A write with a null scope returns 404.
- ROLE_PATIENT gets 403.

**AC-15. The client cannot set the dispense status.**

- `POST /pharmacy/dispense` with `status` PENDING or CANCELLED returns 400
  `dispense.status.notAssertable`.
- COMPLETED, PARTIAL or no status behave as today.
- This closes the hole at `DispenseMapper.java:124`.

**AC-16. i18n.**

- Every new SMS and UI string exists in EN, FR and ES. The backend has two
  EN files, `messages.properties` and `messages_en.properties`.
- `npm run i18n:parity` and `i18n:referenced` pass, and so does the Android
  and iOS string parity.

**AC-17. Feature flag off.**

- `POST /ready` returns 404 and the portal hides **Mark ready**.
- Hand-over and cancel-ready still work, so fills prepared before the switch
  can be finished.
- The reminder sweep follows its own flag.

**AC-18. Migration.**

- V178 applies on Postgres (`LiquibaseSchemaIT`) and is registered
  (`MigrationRegistrationTest`).
- `EntitySchemaValidationIT` passes.

## 5. Business rules & edge cases

**Dispense state machine.** New transitions are in bold.

```
(ready)            -> **PENDING**
PENDING            -> **COMPLETED**   (hand-over)
PENDING            -> **CANCELLED**   (cancel-ready | prescriber withdrawal | prescriber edit)
(one-step)         -> COMPLETED | PARTIAL        (unchanged)
COMPLETED|PARTIAL  -> CANCELLED                  (unchanged, /cancel)
```

The prescription status does not move at ready or at cancel-ready. It moves
only at hand-over, through the existing `updatePrescriptionStatusFromHistory`.

1. **At most one open PENDING fill per prescription.**
   - The service checks first.
   - The V178 partial unique index enforces it:
     `uq_disp_one_pending_per_rx ON clinical.dispenses(prescription_id) WHERE status='PENDING'`.
   - A `DataIntegrityViolationException` from that index is translated to 409.
     It must be told apart from the idempotency-key index, which the existing
     race recovery (`:249-268`) handles.
2. **PENDING is not a fill.**
   - `sumQuantityDispensedForPrescription` must exclude PENDING as well as
     CANCELLED: change it to `status NOT IN :excluded` at both callers,
     `DispenseServiceImpl.java:870` and `StockOutRoutingServiceImpl.java:655`.
   - Otherwise a preparation would count toward DISPENSED, and the partner
     "remaining" arithmetic in `FillAccounting` would be wrong.
3. **The prepared quantity is fixed.** Hand-over takes no quantity. To change
   it, cancel the preparation and prepare again, or use the one-step dispense.
4. **Hand-over writes COMPLETED.** That is what the portal's one-step path
   writes today; it never sends PARTIAL. The fact that a fill was partial
   lives on the prescription (PARTIALLY_FILLED).
5. **Verification is split by who is present.**
   - At ready: product scan, the DRUG and EXPIRY checks, and CDS (an override
     reason is accepted).
   - At hand-over: EXPIRY again, because the lot can expire while the fill
     waits, and PATIENT, if the wristband is scanned.
   - The verification fields on the row are updated at hand-over, and
     `verificationStatus` takes the worse of the two outcomes.
6. **Hand-over re-reads the prescription under a row lock.**
   - It loads the prescription with `PrescriptionRepository.findByIdForUpdate`
     (`repository/PrescriptionRepository.java:120-122`, PESSIMISTIC_WRITE)
     before checking its status.
   - Withdrawal writes the same row, so the lock serialises the two.
   - Without it, the race would surface as a 500: `Prescription` has
     `@Version` (`model/Prescription.java:384`), and there is no global
     `OptimisticLockingFailureException` handler.
7. **Idempotency and concurrency.**
   - Ready accepts an `idempotencyKey`, through the same pre-check and race
     recovery wrapper as `createDispense`.
   - Hand-over and cancel-ready move the row with a conditional bulk update:
     `UPDATE Dispense SET status=… WHERE id=:id AND status=PENDING`, which
     returns the number of rows changed.
   - If that number is 0, the row is re-read. COMPLETED on a hand-over
     returns 200 with no side effects. Anything else returns 409.
   - This is the claim-then-act pattern. `Dispense` and `BaseEntity` have no
     `@Version`, and V178 does not add one.
8. **Withdrawal and edit void the preparation.**
   - `PrescriptionServiceImpl.updatePrescription` (`:855-860`) calls a new
     `DispenseService.voidPreparedFill(prescription, reason)` next to
     `closePartnerOffersOnWithdrawal`.
   - On the way into withdrawal (the same predicate as `:676`), the reason is
     PRESCRIPTION_WITHDRAWN.
   - On any edit of an order that is not withdrawn, the reason is
     PRESCRIPTION_CHANGED.
   - The void runs in the same transaction, so if it fails the withdrawal
     rolls back too. This is deliberate: a withdrawn order must never keep a
     bag waiting to be collected.
9. **A patient who never comes.**
   - One reminder at P3D, claimed through the `ready_reminder_sent_at` column.
   - The READY_UNCOLLECTED queue cue at P7D.
   - **No auto-cancel.** Putting a bag back on the shelf is a physical act the
     system cannot do.
10. **No preparation can exist for these cases.**
    - PARTNER_ACCEPTED is not dispensable, so it can never be prepared.
    - Approving a refill while a fill is prepared does not change the
      prepared fill.
11. **Patient SMS is best-effort and sent after commit.**
    - A rolled-back ready, cancel or withdrawal never texts the patient.
    - The locale lookup and the bundle render happen inside the transaction,
      and only plain strings go into the after-commit callback. This follows
      the out-of-session-lazy-proxies lesson; the locale resolver runs a
      query.
    - The existing `notifyDispensed` call (`:342-344`) runs inside the
      transaction, so a rolled-back dispense can still send "délivrée". It is
      moved to the same after-commit pattern.
12. **A patient with no phone or no first name.** The SMS does nothing, as
    today (`PharmacyServiceSupport.java:105-114`). Ready still succeeds, and
    the portal and the apps still show it.
13. **Legacy PENDING rows written by a client.**
    - V178 converts them to COMPLETED. They were already counted as fills, so
      this keeps their accounting and stops them appearing as "ready".
    - AC-15 stops new ones being written.

## 6. Architecture fit

### Data model — V178

The file is `V178__dispense_ready_for_collection.sql`, registered in
`changelog.xml` per the liquibase-migration skill (`runOnChange="false"`,
`splitStatements="true"`; it has no DO blocks). It does five things:

1. **Legacy backfill** (rule 13):
   `UPDATE clinical.dispenses SET status='COMPLETED' WHERE status='PENDING';`
   *The coordinator must first run
   `SELECT status, count(*) FROM clinical.dispenses GROUP BY status` on dev
   and prod. The expected PENDING count is 0.*
2. **`dispensed_at` becomes nullable:**
   `ALTER TABLE clinical.dispenses ALTER COLUMN dispensed_at DROP NOT NULL;`
   - The DB default `now()` stays.
   - PENDING rows carry NULL.
   - Both KPI paths already filter `dispensed_at IS NOT NULL`
     (`db/migration/V105__kpi_dashboard_materialized_views.sql:64` and
     `service/impl/KpiDashboardServiceImpl.java:216`), so preparations stay
     out of lead time with no change to the view.
3. **New columns:**
   - `prepared_by UUID NULL`, with `fk_disp_prepared_by` → `security.users(id)`;
   - `ready_reminder_sent_at TIMESTAMP NULL`.
   Both use `ADD COLUMN IF NOT EXISTS`.
4. **New indexes:**
   - `CREATE UNIQUE INDEX IF NOT EXISTS uq_disp_one_pending_per_rx … WHERE status='PENDING'`;
   - `CREATE INDEX IF NOT EXISTS idx_disp_pending_created … (created_at) WHERE status='PENDING'`,
     for the sweep and the patient reads.
5. **No CHECK constraint to change** (as far as the repo shows):
   - `clinical.dispenses.status` is a bare `VARCHAR(30)`
     (`V43__pharmacy_module_phase1.sql:185`). The only CHECK on the table is
     `chk_disp_qty_positive` (`:198`).
   - Nothing in `db/migration` constrains `prescriptions.status`. #818 added
     TRANSMISSION_FAILED without a migration.
   - The `R__` scripts and the hand-run prod scripts could not be inspected.
     **Coordinator to verify** with `\d clinical.dispenses` on prod.

### Decision: reuse `DispenseStatus.PENDING`; add no `PrescriptionStatus` value

**Why PENDING is the right value.**

- It already means "a dispense that is not complete", which is exactly a
  prepared fill.
- It is in the enum, has no CHECK constraint, and no server code writes it.
- It maps one-to-one onto FHIR `MedicationDispense.status = preparation`, if
  that resource is ever mapped. No mapper exists today.
- It lives on the Dispense, which is where readiness belongs: per fill, so
  refills and partial fills each get their own readiness.
- A new READY value would leave PENDING as dead weight and need the same
  rewiring.

**Why not a new prescription status.**

- The prescription status drives routing, and these gate sets would all need
  editing:
  - `DISPENSABLE` (`:146`), `WORK_QUEUE` (`:162`), `NEEDS_ATTENTION` (`:170`)
    and `RECOMPUTABLE_AFTER_CANCEL` (`:189`) in `DispenseServiceImpl`;
  - `ROUTABLE`, `DISPATCHABLE` and `CLARIFIABLE`;
  - `NOTIFIED_EVENTS` (`service/pharmacy/PrescriberPharmacyNotifier.java:35`).
- A READY prescription status would overwrite PENDING_STOCK, PARTIALLY_FILLED
  or TRANSMISSION_FAILED. Restoring the old value would need another "previous
  status" column, like `clarificationPreviousStatus`.
- Every installed app build would show the new value as "unknown", because
  the app enums are exhaustive mirrors. New JSON fields, by contrast, are
  ignored by Moshi and by Swift `Codable`.

**Why stock moves at ready.**

- The prepared bag is physically off the shelf.
- If on-hand stock stayed untouched, stock-out routing and other fills could
  spend units that are already in a bag.
- Cancel-ready returns the units using the same RETURN pattern as
  `cancelDispense` (`:686-704`).

### Backend components

- **`enums/DispenseStatus.java`**: Javadoc only. PENDING means "prepared,
  awaiting collection".
- **New `enums/ReadyCancelReason.java`**:
  - API values: STOCK_UNAVAILABLE, PATIENT_DECLINED, NOT_COLLECTED, OTHER.
  - System values, refused from the API: PRESCRIPTION_WITHDRAWN,
    PRESCRIPTION_CHANGED.
- **`model/pharmacy/Dispense.java`**:
  - `dispensedAt` becomes nullable: remove `@NotNull`. The `@Builder.Default`
    stays for the one-step path.
  - Add `preparedByUser` (LAZY) and `readyReminderSentAt`.
  - Add the `@Index` entries for the two new indexes.
- **`repository/pharmacy/DispenseRepository.java`**:
  - `sumQuantityDispensedForPrescription` takes a collection of excluded
    statuses.
  - New finders: `findFirstByPrescription_IdAndStatus`, and a batched
    `findByPrescription_IdInAndStatus` for the queue and the patient reads.
  - Conditional `@Modifying` transitions: `completePreparedFill`,
    `cancelPreparedFill`, `claimReadyReminder`.
  - A finder for the reminder sweep.
- **`service/pharmacy/DispenseService(Impl).java`**:
  - `markReadyForCollection(dto)`: a wrapper plus a `…Transactionally` body.
    Extract the front half of create that both paths share
    (`loadAndValidatePrescription`, `requireDispensary`, CDS, verification,
    `consumeStockLot`) into one private method; do not copy it.
  - `handOver(id, HandOverRequestDTO)`.
  - `cancelReady(id, reason)`.
  - `voidPreparedFill(prescription, reason)`.
  - The work-queue decoration.
  - The open-preparation guard in `createDispenseTransactionally`.
- **`service/pharmacy/PharmacyServiceSupport.java`**:
  - New `notifyReadyForCollection`, `notifyReadyCancelled`, `notifyReadyReminder`.
  - An after-commit variant (rule 11) using `utility/TransactionCallbacks.afterCommit`
    (`:41`).
- **New `service/pharmacy/ReadyForCollectionReminderScheduler.java`**:
  - a void method with `@SchedulerLock`. Lesson #563: never on a method that
    returns a primitive;
  - `@Scheduled(cron="${pharmacy.ready-for-collection.reminder.cron:0 0 10 * * *}")`;
  - claim in a REQUIRES_NEW transaction, then send;
  - covered by `SchedulerLockCoverageTest`.
- **An `existsOpenPreparation(prescriptionId)` guard** in:
  - `StockOutRoutingServiceImpl`, at the routing entry next to `:660`;
  - `PrescriptionSmsDispatchServiceImpl`, at the dispatch entry;
  - `PrescriptionClarificationService`, at the request entry.
- **`service/PrescriptionServiceImpl.java`** (`:855-860`): the
  `voidPreparedFill` hook.
- **Patient reads**: a batched readiness lookup in
  `service/impl/PatientMedicationServiceImpl.java` (`:156`) and in
  `PrescriptionService.getPrescriptionsForPortalPatient` (called from
  `PatientPortalServiceImpl.java:366`).
- **Patient DTO fields** on `payload/dto/medication/PatientMedicationResponseDTO.java`
  and on `payload/dto/PrescriptionResponseDTO.java` (next to `pharmacyName`,
  `:115`).
- **`enums/AuditEventType.java`**: add `DISPENSE_READY`,
  `DISPENSE_READY_CANCELLED`, `DISPENSE_HANDED_OVER`. `DisclosureCategoryTest`
  must still pass.
- **`controller/pharmacy/DispenseController.java`**: three new POSTs. There is
  no `@WriteAudited(skip)`, so each one also writes the conventional DATA_*
  audit row, like the existing POSTs.

### API contract

All endpoints are under `/pharmacy/dispense` and wrapped in `ApiResponseWrapper`.

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /ready` | `DispenseRequestDTO`. Same fields; `status` must be absent; `patientScanValue` is ignored | 201 `DispenseResponseDTO`: `status:"PENDING"`, `dispensedAt:null`, and the new `preparedBy`, `preparedByName`, `readyAt` | 400: not dispensable, CDS, or verification. 404: out of scope, or the flag is off. 409: a preparation is already open |
| `POST /{id}/hand-over` | `{ "patientScanValue"?: string, "notes"?: string }` | 200 `DispenseResponseDTO` (`COMPLETED`). A replay returns the same 200 | 404: out of scope or unknown. 409: not PENDING, or the prescription is no longer dispensable. 400: expiry, patient mismatch, or controlled substance |
| `POST /{id}/cancel-ready` | `{ "reason": "STOCK_UNAVAILABLE" \| "PATIENT_DECLINED" \| "NOT_COLLECTED" \| "OTHER" }` | 200 `DispenseResponseDTO` (`CANCELLED`) | 400: reason missing or a system value. 404. 409: not PENDING |
| `POST /` (existing) | unchanged | unchanged | **New** 400 when `status` is PENDING or CANCELLED. **New** 409 when a preparation is open |
| `GET /work-queue` (existing) | — | Rows gain `readyForCollection` (a nullable object), and `attentionReason` can be `"READY_UNCOLLECTED"` | — |
| `GET /me/patient/medications`, `GET /me/patient/prescriptions`, proxy medications | — | Rows gain `readyForCollectionAt` (ISO local date-time) and `readyForCollectionPharmacyName` | — |

**Offline queue.** `offline-dispense.interceptor.ts:66-70` matches only
`POST …/pharmacy/dispense` exactly, so the new endpoints are **not** queued
offline in v1. If a queued one-step dispense replays while a preparation is
open, it gets 409, and the queue's existing error handling applies. Nothing in
the queue changes.

### Portal — staff (`hospital-portal/src/app/`)

- **`services/pharmacy.service.ts`**:
  - `WorkQueuePrescription` (`:297`) gains `readyForCollection?`.
  - `DispenseResponse` gains `preparedByName?` and `readyAt?`.
  - New `markReady`, `handOver` and `cancelReady`, next to `createDispense`
    (`:751`) and `cancelDispense` (`:805`).
- **`pharmacy/dispensing.ts` and `.html`**:
  - The form gets a second submit button, **Mark ready for collection**,
    beside **Dispense** (`dispensing.html:214-226`). It is hidden when the
    flag is off.
  - A queue row with `readyForCollection` shows a badge with the age.
    **Hand over** (a short confirm with the optional patient scan) and
    **Cancel preparation** (with a reason select) replace Dispense and Route
    (`:334-345`).
  - The recent-dispenses list (`:408-445`) labels PENDING through the enum
    label, and hides the plain Cancel on PENDING rows.
  - `QUEUE_ATTENTION_REASONS` gains READY_UNCOLLECTED.
  - Follow the angular-portal-component skill: signals idiom, keyboard
    contract, axe smoke.
- **`core/feature-flags.service.ts`**: read the flag (see "Feature flag" below).

### Patient portal

- `services/patient-portal.service.ts` (`:970`, `:977`): the types gain the
  two fields.
- `patient-portal/my-medications/my-medications.component.{ts,html}`: a
  "Ready for collection at {pharmacy}" chip with the time.

### Apps

Neither app gets an enum change. CI is the only Swift compiler (see the
patient-mobile-apps memory).

- **Android**:
  - `core/models/MedicationModels.kt`: `MedicationDto` (`:9`) and
    `PrescriptionDto` (`:131`) gain `readyForCollectionAt: String? = null`
    and `readyForCollectionPharmacyName: String? = null`.
  - `features/medications/MedicationsScreen.kt`: a chip, near `:150` and
    `:227`.
  - Strings in `res/values{,-fr,-es}/strings.xml`.
- **iOS**:
  - `Core/Models/MedicationModels.swift`: `MedicationDTO` (`:5`) and
    `PrescriptionDTO` (`:109`) gain the same two fields as optionals.
  - `Features/Medications/MedicationsView.swift`: the chip.
  - Strings in `Resources/{en,fr,es}.lproj/Localizable.strings`.

### i18n — backend SMS and errors

FR is the product default. In `.properties`, `''` escapes an apostrophe for
MessageFormat. The SMS texts are about logistics only and carry no clinical
content. All three SMS keys take the same arguments: {0} first name,
{1} medication, {2} pharmacy.

| key | EN (`messages.properties` and `messages_en.properties`) | FR | ES |
|---|---|---|---|
| `sms.pharmacy.readyForCollection` | `Hello {0}, your prescription ({1}) is ready for collection at {2}. Thank you.` | `Bonjour {0}, votre ordonnance ({1}) est prête à être retirée à {2}. Merci.` | `Hola {0}, su receta ({1}) está lista para recoger en {2}. Gracias.` |
| `sms.pharmacy.readyReminder` | `Hello {0}, your prescription ({1}) is still waiting for you at {2}. Thank you.` | `Bonjour {0}, votre ordonnance ({1}) vous attend toujours à {2}. Merci.` | `Hola {0}, su receta ({1}) todavía le espera en {2}. Gracias.` |
| `sms.pharmacy.readyCancelled` | `Hello {0}, your prescription ({1}) is no longer ready for collection at {2}. Please contact the pharmacy before you come.` | `Bonjour {0}, votre ordonnance ({1}) n''est plus prête à être retirée à {2}. Veuillez contacter la pharmacie avant de vous déplacer.` | `Hola {0}, su receta ({1}) ya no está lista para recoger en {2}. Póngase en contacto con la farmacia antes de acudir.` |
| `dispense.ready.alreadyOpen` | `This prescription already has a fill prepared for collection.` | `Cette ordonnance a déjà une délivrance préparée en attente de retrait.` | `Esta receta ya tiene una dispensación preparada para recoger.` |
| `dispense.ready.openPreparation` | `A fill is prepared for this prescription: hand it over or cancel the preparation first.` | `Une délivrance est préparée pour cette ordonnance : remettez-la au patient ou annulez la préparation d''abord.` | `Hay una dispensación preparada para esta receta: entréguela o cancele la preparación primero.` |
| `dispense.ready.notPending` | `This fill is no longer waiting for collection.` | `Cette délivrance n''est plus en attente de retrait.` | `Esta dispensación ya no está pendiente de recogida.` |
| `dispense.status.notAssertable` | `The dispense status cannot be set directly; use the ready-for-collection actions.` | `Le statut de la délivrance ne peut pas être défini directement ; utilisez les actions de retrait.` | `El estado de la dispensación no se puede definir directamente; use las acciones de recogida.` |

### i18n — UI strings

Portal JSON keys go under `PHARMACY.*` and `MY_MEDICATIONS.*`. The apps
mirror the patient strings.

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
| Dispense status label `PENDING` (enum-label group for dispenses) | Ready for collection | Prête à retirer | Lista para recoger |
| `MY_MEDICATIONS.READY_AT` / app `medication_ready_for_collection` | Ready for collection at {{pharmacy}} | Prête à retirer à {{pharmacy}} | Lista para recoger en {{pharmacy}} |
| `MY_MEDICATIONS.READY_SINCE` / app `medication_ready_since` | Since {{date}} | Depuis le {{date}} | Desde el {{date}} |

- The aria-labels of the new buttons use the same keys.
- The executor reuses the namespace `my-medications` already uses, and does
  not create `MY_MEDICATIONS` if the component uses another prefix.

### Audit and logging

- Event descriptions carry ids, the quantity and the reason **code** only. No
  medication name, patient name or phone.
- Phone numbers in logs are masked, as `SmsPartnerNotificationChannel` does.
- `resourceId` is the dispense id; `entityType` is `DISPENSE` (`AUDIT_ENTITY`,
  `:118`).

### Feature flag

- The key is `pharmacy.readyForCollection`, read through the existing
  `app.feature-flags` mechanism: `config/FeatureFlagProperties.java`,
  `controller/FeatureFlagController.java` and the portal's
  `core/feature-flags.service.ts`.
- **Executor to confirm** the server-side read API those files expose. If
  there is none, use a `pharmacy.ready-for-collection.enabled` property and
  surface it to the portal through the same flag map.
- The flag gates `POST /ready` and the **Mark ready** button only.
- The reminder sweep has its own flag, `pharmacy.ready-for-collection.reminder.enabled`.

## 7. Security & privacy

| Threat | Control |
|---|---|
| Hand-over or cancel in another hospital by guessing a dispense id | The pharmacy's hospital is checked, and a mismatch returns the same 404 as a missing row (AC-14). Writes with a null scope are refused |
| A client posting PENDING to the old endpoint, to fake "ready" or to skew fill sums | AC-15 refuses a client-set PENDING or CANCELLED. V178 converts the legacy rows |
| Hand-over against a withdrawn or edited order | The withdrawal or edit transaction voids the preparation (AC-8, AC-9). Hand-over re-reads the prescription under a row lock (rule 6) |
| Medication handed to the wrong person days later | The PATIENT check runs at hand-over (rule 5). The wristband scan stays optional, as in the one-step path, and records NOT_VERIFIED honestly when skipped |
| Expired stock handed over after waiting | EXPIRY is re-checked at hand-over and is never overridable |
| Double collection or double stock decrement (two clicks, two pharmacists) | The partial unique index, the conditional transitions, and the idempotency key |
| PHI in SMS | The same content as the existing `sms.pharmacy.dispensed` (first name, medication, pharmacy), which is an accepted precedent. Sent only to the patient's own primary phone; nothing goes to proxies in v1 |
| PHI in logs or audit | Ids and codes only (section 6) |
| A text sent for an action that rolled back | SMS is sent after commit (rule 11) |
| A patient sees another patient's readiness | Readiness is computed only inside the existing own-record reads (`requireOwnRecord`) |
| Hospital staff mark a partner or community pharmacy's fill "ready" | `requireDispensary` runs on ready |

## 8. Test plan

Each test names its AC. "Falsify" means a local revert that must turn the
test red. Revert **exactly** the change named and nothing more
(falsify-exactly-the-change).

| AC | Test (file) | Falsify by |
|---|---|---|
| AC-1 | `DispenseServiceImplTest`: ready creates a PENDING row with `dispensedAt` null and `preparedBy` set; decrements the lot; leaves the prescription status alone; writes no prescriber notification; audits DISPENSE_READY; registers an after-commit SMS whose rendered text is checked in FR, EN and ES | Write COMPLETED instead of PENDING; call `updatePrescriptionStatusFromHistory` at ready; send the SMS synchronously |
| AC-1 | `PharmacyServiceSupportTest`: `notifyReadyForCollection` renders the key in the patient's locale and swallows gateway errors | Remove the try/catch; hard-code FR |
| AC-2 | `DispenseServiceImplTest`, parameterised over the non-dispensable statuses, plus controlled-substance, expired-lot and CDS cases: no save, no stock move, no SMS | Remove the `DISPENSABLE_STATUSES` check on the ready path |
| AC-3 | Unit: a second ready returns 409; a one-step create with an open preparation returns 409. IT in `PrescriptionToPharmacyFlowIT` (Postgres via Testcontainers where available): two concurrent readies leave one row and return one 409 | Remove the service check (the IT must still pass on the index alone). Drop the index from V178 (the IT must go red) |
| AC-4 | `DispenseServiceImplTest` and the IT: hand-over gives COMPLETED with `dispensedAt` set; the prescription becomes DISPENSED; the notifier is called with DISPENSED; the refill is closed; the receipt SMS goes after commit | Skip the recompute on hand-over |
| AC-5 | Unit: hand-over on a COMPLETED row returns the same DTO with no interactions. IT: two threads hand over and exactly one audit row is written | Replace the conditional update with load-and-save |
| AC-6 | Unit: a withdrawn or otherwise non-dispensable prescription returns 409; an expired lot is refused; a patient-scan mismatch is refused; the controlled-substance guard runs again | Remove the re-check, or the `findByIdForUpdate` call |
| AC-7 | Unit and IT: cancel-ready gives CANCELLED and a RETURN stock transaction, leaves the prescription unchanged, puts the reason in the audit, sends the cancelled SMS; on COMPLETED it returns 409; `/cancel` still refuses PENDING | Skip the stock return; allow a source status other than PENDING |
| AC-8 | `PrescriptionServiceImplTest` and the IT: withdrawing with an open preparation sets the dispense to CANCELLED (PRESCRIPTION_WITHDRAWN), returns the stock and registers the SMS; a later hand-over returns 409; the patient read shows no readiness | Remove the `voidPreparedFill` call at `:859` |
| AC-9 | `PrescriptionServiceImplTest`: an edit voids the preparation with PRESCRIPTION_CHANGED | Fire the hook only on withdrawal |
| AC-10 | `StockOutRoutingServiceImplTest`, `PrescriptionSmsDispatchServiceImplTest`, `PrescriptionClarificationServiceTest`: each returns 409 while a fill is prepared | Remove each guard, one at a time |
| AC-11 | `DispenseServiceImplTest` (work queue): the `readyForCollection` block is filled by one batched query (no N+1; verify the repository call count); READY_UNCOLLECTED appears at P7D and not at P6D, and a status reason outranks it. `dispensing.spec.ts`: the buttons swap; hand-over and cancel call the service; the PENDING label shows | Drop the decoration; invert the age comparison |
| AC-12 | `PatientMedicationServiceImplTest` and a portal-prescriptions test: the fields are set for an open preparation and null after hand-over, cancel and withdrawal. `my-medications.component.spec.ts`. Android `MedicationModelsTest.kt`: decodes with and without the fields. iOS: a new `MediHubPatientTests/MedicationModelsTests.swift` (confirm `project.yml` picks it up) | Return readiness for any PENDING row, whatever the prescription status |
| AC-13 | `ReadyForCollectionReminderSchedulerTest`: one SMS at P3D; none on the second run; none for a cancelled or withdrawn fill; none when disabled. `SchedulerLockCoverageTest` covers the new lock | Remove the claim-column check |
| AC-14 | `DispenseServiceImplTest`: a dispense in another hospital returns 404 on both writes; a null scope returns 404. A controller slice, if one is added, proves ROLE_PATIENT gets 403 | Remove `enforceHospitalScope` from hand-over |
| AC-15 | Unit: a POST with status PENDING or CANCELLED returns 400; COMPLETED and no status are unchanged | Remove the check |
| AC-16 | `npm run i18n:parity`, `npm run i18n:referenced`, the backend bundle-parity test (if there is one), and the Android and iOS string parity in CI | Delete one FR key |
| AC-17 | Unit: with the flag off, ready returns 404 and hand-over still returns 200 | Gate hand-over on the flag |
| AC-18 | `MigrationRegistrationTest`, `LiquibaseSchemaIT`, `EntitySchemaValidationIT` (Docker) | Unregister the changeSet |

**Gates before every push:**

- the full backend suite, not a slice (webmvctest-slice-scanning lesson);
- Karma, lint and format;
- the axe smoke on `/pharmacy/dispensing`.

After any merge from develop, check the changelog and the i18n files by hand
(stacked-PR protocol).

## 9. Rollout

1. **V178 ships with the code.** It adds and relaxes columns, backfills
   legacy rows and creates indexes. The coordinator runs the PENDING count
   query on dev and prod before merging. No new schema is involved, so the
   hand-run prod `R__prod_role_grants.sql` needs no change.
2. **The flag defaults ON.**
   - The feature is opt-in per action: a pharmacist has to choose **Mark
     ready**, and the one-step **Dispense** is unchanged. The flag is a kill
     switch.
   - The reminder flag also defaults ON, at P3D and 10:00 server time.
3. **Backward compatibility.**
   - Every new JSON field is additive and nullable.
   - Old portal bundles ignore `readyForCollection`.
   - Installed app builds ignore the patient fields; Moshi and `Codable`
     skip unknown keys.
   - No value is added to an enum the apps or portal mirror.
4. **Data backfill**: none beyond rule 13.
5. **Rollback.**
   1. Turn the flag off first. Open preparations can still be finished.
   2. Before reverting any code, hand over or cancel every open preparation.
      Find them with
      `SELECT id, prescription_id FROM clinical.dispenses WHERE status='PENDING'`.
      The old code counts PENDING as a fill and cannot cancel it.
   3. V178 is forward-only. A nullable `dispensed_at` and two unused columns
      do no harm to the old code.
6. **Prod deploy** happens only when the user says "sync" (the develop → main
   merge). Promoting to main is a prod deploy.

## 10. Out of scope / open questions

Each open question has a recommended default, so work is not blocked.

1. **Partner and community pharmacies (the SMS protocol).** Out of scope for v1.
   - When a partner accepts, the patient already gets "you may go there"
     (`sms.partner.patientAccepted`, sent by
     `SmsPartnerNotificationChannel.notifyPatientAccepted`, `:111`). That is
     the partner's equivalent of "ready".
   - An optional future reply code, `4 <ref>` = "prepared, waiting for the
     patient", would need:
     - `PartnerSmsReplyParser.Action` (`:40`) and its `[123]` patterns
       (`:50`, `:53`, mapping at `:134-136`);
     - a decision transition in `PartnerExchangeService` (`:313-346`);
     - partner instruction templates in all three locales;
     - a new patient SMS.
   - **Default: not in v1. Record it as a follow-up in `tasklist.md`.**
2. **Tell the prescriber when a fill is ready?**
   **Default: no.** It would be noise, and the prescriber already hears
   DISPENSED or PARTIALLY_FILLED at hand-over (G6).
3. **Keep the receipt SMS at hand-over**, even though the patient is at the
   counter?
   **Default: yes.** It matches the one-step path and leaves the patient a
   record.
4. **A patient who never collects.**
   **Default:** one reminder at P3D, the queue cue at P7D, and **no
   auto-cancel**. Putting the bag back on the shelf is physical work.
5. **Text the patient on cancel, withdrawal or edit?**
   **Default: yes**, with a neutral "no longer ready, contact the pharmacy"
   and no reason, so the patient does not make a wasted trip.
6. **STOCK_UNAVAILABLE (the bag is lost or damaged).**
   **Default:** the stock is still returned on cancel, and the pharmacist
   records the loss on the existing stock-adjustment page (ADJUSTMENT). A
   `returnToStock=false` option is a follow-up.
7. **The prescriber edits while a fill is prepared.**
   **Default: void** the preparation (PRESCRIPTION_CHANGED) rather than refuse
   the edit. Refusing would block a dose correction.
8. **Payments and claims against a PENDING dispense.**
   **Default: no change in v1.** `PharmacyPaymentServiceImpl:61-70` and
   `PharmacyClaimServiceImpl:69-78` do not check the dispense status. That is
   the same exposure that already exists for a cancelled COMPLETED fill.
   Follow-up: refuse a claim until the fill has been handed over.
9. **Patient notification preferences and in-app notifications.**
   **Default:** SMS only, without checking preferences, the same as every
   existing pharmacy SMS. A preference-aware patient notification for all
   pharmacy SMS is a follow-up.
10. **Queue ready and hand-over offline?**
    **Default: no** (section 6).
11. **SMS to proxies (family).**
    **Default: none.** Proxies see readiness only through the proxy
    medications read.
12. **FHIR `MedicationDispense`.** Not mapped today. Out of scope.

## 11. Task list

Each task is one commit. Run the section 8 gates before each push.

- [ ] **T1 — V178 migration and registration.** AC-18, plus the index for AC-3.
  - `db/migration/V178__dispense_ready_for_collection.sql` and `changelog.xml`.
  - `model/pharmacy/Dispense.java`: nullable `dispensedAt`, `preparedByUser`,
    `readyReminderSentAt`, the indexes.
  - `enums/DispenseStatus.java` (Javadoc) and the new `enums/ReadyCancelReason.java`.
  - Tests: `MigrationRegistrationTest`, `LiquibaseSchemaIT`, `EntitySchemaValidationIT`.
- [ ] **T2 — PENDING is not a fill.** AC-4, and groundwork for AC-3.
  - `DispenseRepository.java`: `sumQuantityDispensedForPrescription` with
    excluded statuses, the new finders, the conditional `@Modifying` transitions.
  - Update the callers at `DispenseServiceImpl.java:870` and `StockOutRoutingServiceImpl.java:655`.
  - Tests: the existing sum tests plus a PENDING case.
- [ ] **T3 — Close the client-set status hole; guard the one-step dispense.** AC-15, AC-3.
  - `DispenseServiceImpl.createDispenseTransactionally` and `DispenseMapper.java:124`.
  - Messages `dispense.status.notAssertable` and `dispense.ready.openPreparation`,
    in both EN files, FR and ES.
- [ ] **T4 — Patient SMS keys and after-commit sending.** AC-1, AC-7, AC-13, AC-16.
  - `PharmacyServiceSupport.java`: add `notifyReadyForCollection`,
    `notifyReadyCancelled`, `notifyReadyReminder`; move `notifyDispensed` to
    after-commit, rendering inside the transaction.
  - The four `messages*.properties` files.
  - `PharmacyServiceSupportTest`.
- [ ] **T5 — Mark ready.** AC-1, AC-2, AC-3, AC-14, AC-17.
  - `DispenseService(Impl).markReadyForCollection`: wrapper plus
    transactional body, with the shared front half extracted.
  - `DispenseController`: `POST /ready`.
  - `AuditEventType.DISPENSE_READY`; the flag read.
  - `DispenseResponseDTO` and `DispenseMapper`: `preparedBy`, `readyAt`.
  - `DispenseServiceImplTest`.
- [ ] **T6 — Hand-over.** AC-4, AC-5, AC-6, AC-14.
  - `DispenseService(Impl).handOver`: the row-locked prescription re-check,
    EXPIRY and PATIENT re-verification, the conditional transition, the
    recompute, the close-outs, the receipt SMS, `DISPENSE_HANDED_OVER`.
  - `HandOverRequestDTO`, the controller endpoint, tests.
- [ ] **T7 — Cancel preparation.** AC-7.
  - `DispenseService(Impl).cancelReady`, plus the shared `voidPreparedFill`:
    stock RETURN, audit `DISPENSE_READY_CANCELLED`, SMS.
  - `CancelReadyRequestDTO`, the controller endpoint, tests.
- [ ] **T8 — A withdrawn or edited order voids its preparation.** AC-8, AC-9.
  - `PrescriptionServiceImpl.updatePrescription` (`:855-860`).
  - `PrescriptionServiceImplTest`, `PrescriptionToPharmacyFlowIT`.
- [ ] **T9 — Guards while a fill is prepared.** AC-10.
  - `StockOutRoutingServiceImpl`, `PrescriptionSmsDispatchServiceImpl`,
    `PrescriptionClarificationService`, and their tests.
- [ ] **T10 — Work-queue projection.** AC-11.
  - `WorkQueuePrescriptionDTO`: `readyForCollection`.
  - `DispenseServiceImpl`:
    - `getWorkQueue`, batched;
    - `attentionReason` gains READY_UNCOLLECTED, at the lowest precedence;
    - `lastPharmacyActionsFor` uses `coalesce(dispensedAt, createdAt)`.
  - The `uncollected-after` property; tests.
- [ ] **T11 — Patient read surface.** AC-12.
  - `PatientMedicationResponseDTO`, `PrescriptionResponseDTO`.
  - `PatientMedicationServiceImpl`, `PrescriptionServiceImpl.getPrescriptionsForPortalPatient`.
  - `PatientMedicationServiceImplTest`, the portal-prescriptions test.
- [ ] **T12 — Reminder sweep.** AC-13.
  - `ReadyForCollectionReminderScheduler`: void, `@SchedulerLock`, claim in
    REQUIRES_NEW, then send.
  - The properties; `SchedulerLockCoverageTest`; the scheduler test.
- [ ] **T13 — Portal pharmacy UI.** AC-11, AC-16, AC-17.
  - `services/pharmacy.service.ts`, `pharmacy/dispensing.{ts,html,scss,spec.ts}`.
  - `core/enum-label.service.ts`: the dispense PENDING label.
  - `assets/i18n/{en,fr,es}.json`: the `PHARMACY.*` keys above.
  - The flag wiring.
- [ ] **T14 — Patient portal.** AC-12, AC-16.
  - `services/patient-portal.service.ts`, `patient-portal/my-medications/*`,
    the i18n JSON.
- [ ] **T15 — Android.** AC-12.
  - `core/models/MedicationModels.kt`, `features/medications/MedicationsScreen.kt`.
  - `res/values{,-fr,-es}/strings.xml`, `MedicationModelsTest.kt`.
- [ ] **T16 — iOS.** AC-12.
  - `Core/Models/MedicationModels.swift`, `Features/Medications/MedicationsView.swift`.
  - `Resources/{en,fr,es}.lproj/Localizable.strings`, `MediHubPatientTests/MedicationModelsTests.swift`.
- [ ] **T17 — Bookkeeping.** Documentation for all ACs.
  - `tasklist.md`: mark G15 (`:3693`) done with the PR number, and add the
    follow-ups from section 10 items 1, 6, 8 and 9.
  - Update the `PharmacyServiceSupport` Javadoc (`:89-103`) to describe the
    new state.
