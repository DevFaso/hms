# Plan — pharmacy work-queue claim (audit gap G13)

Branch `feat/pharmacy-queue-claim` off `origin/develop` 1e2bb79c3 (production
== develop after the 2026-10-07 sync; G15 ready-for-collection #825 merged).
Migration owned: **V179**. Every file:line below was read on that commit.
Java paths are relative to `hospital-core/src/main/java/com/example/hms/`
unless they say otherwise. House example and source of the lock rule:
`docs/plan/pharmacy-ready-for-collection-plan.md` (G15, "rule 1").

---

## 1. Summary

Every pharmacist at a hospital sees the same work queue, and nothing says who
is working on which row. Two pharmacists can pick the same prescription, both
go to the shelf, and the second one only finds out at the final write, when
the server refuses an over-fill or a second preparation. This change lets a
pharmacist **claim** a queue row ("Being prepared by <name>", visible to
colleagues), **release** it, and **take over** a colleague's claim with an
audit row. A claim **expires** after 30 minutes (configurable). Expiry is
computed when the claim is read, so no sweep is needed. The queue gains
**Mine** and **Unclaimed** filters.

The claim is **advisory**: it coordinates people, it does not guard data.

- Nothing is ever refused because of a claim: recording a fill, routing the
  order, asking the prescriber a question, or withdrawing the order.
- When someone other than the holder acts, the claim is released and the act
  is audited as a take-over.
- Only a plain *claim* of a row someone else holds is refused (409), so nobody
  steals a row silently.
- The double fill itself is already prevented by the G15 lock, `@Version` and
  the quantity checks. This feature does not change those.

Claims live in a new table, `clinical.prescription_queue_claims` (V179), one
row per prescription, not in columns on `clinical.prescriptions`. Every path
that creates, moves or deletes a claim first holds the prescription row lock
(the G15 rule). Patients, the patient portal and both mobile apps are
unaffected.

## 2. Business context

**Problem.** `tasklist.md:4612-4616` records G13: "Nothing assigns a queued
prescription to one pharmacist, so two can start preparing the same order;
only the dispense itself is protected (`Prescription.version`, `@Version` at
`Prescription.java:384`, refuses the second write). Needed only for
multi-pharmacist queues. Open."

**Current behaviour, in code.**

- **The queue.** `DispenseServiceImpl.getWorkQueue`
  (`service/pharmacy/DispenseServiceImpl.java:1132-1162`) pages
  `prescriptionRepository.findByHospital_IdAndStatusIn(hospitalId, WORK_QUEUE_STATUSES, pageable)`
  (`repository/PrescriptionRepository.java:111`).
  - `WORK_QUEUE_STATUSES` (`:189-191`) is `DISPENSABLE_STATUSES` (`:173-180`)
    plus PARTNER_ACCEPTED. The dispensable statuses are SIGNED, TRANSMITTED,
    PARTIALLY_FILLED, PENDING_STOCK, PARTNER_REJECTED and TRANSMISSION_FAILED.
  - Each row is decorated with batched lookups: refills (`:1272`), routing
    decisions (`:1191`), back orders (`:1211`), the last pharmacy action
    (`:1233`), and G15's open preparation (`:1165-1177`).
  - `toWorkQueueDTO` (`:1424-1475`) then maps the row.
  - There is no notion of an owner.
- **The projection.** `payload/dto/pharmacy/WorkQueuePrescriptionDTO.java`
  (175 lines, `@JsonInclude(NON_NULL)`) carries:
  - patient and prescriber;
  - refill;
  - attention reason and the clarification cue;
  - G15's `readyForCollection` (`:91`, nested class `:130-139`).

  It has no claim.
- **The endpoint.** `GET /pharmacy/dispense/work-queue`
  (`controller/pharmacy/DispenseController.java:38-46`).
  - Roles: PHARMACIST, PHARMACY_VERIFIER, HOSPITAL_ADMIN and SUPER_ADMIN.
  - Default sort `createdAt`, page size 20.
  - No filter parameters.
- **What protects the data today.** None of this changes here.
  - **G15 rule 1.** Every one of these writes takes the prescription row lock
    first: fill, preparation, hand-over, cancel-ready, withdrawal, edit,
    routing, clarification, SMS dispatch.
    - The lock is `findByIdAndHospitalIdForUpdate`
      (`repository/PrescriptionRepository.java:132-135`) or
      `findByIdForUpdate` (`:120-122`).
    - Sites:
      - `loadAndValidatePrescription` (`DispenseServiceImpl.java:732-767`);
      - `lockPreparedFill` (`:548-566`);
      - `updatePrescription` (`service/PrescriptionServiceImpl.java:832-845`);
      - `findPrescriptionForWrite` (`service/pharmacy/StockOutRoutingServiceImpl.java:592-609`);
      - `findInScopeForUpdate` (`service/pharmacy/PrescriptionClarificationService.java:178-185`);
      - `dispatch` (`service/impl/PrescriptionSmsDispatchServiceImpl.java:138`).
  - `uq_disp_one_pending_per_rx` (V178): one open preparation per order.
  - `Prescription.version` (`model/Prescription.java:383-384`).
  - `validateQuantities` (`DispenseServiceImpl.java:1304`) and the
    dispensed-to-date sum refuse an over-fill.
- **The portal.** `hospital-portal/src/app/pharmacy/dispensing.ts` loads the
  queue on each pharmacy selection and after each action (`loadWorkQueue`,
  `:242-256`). It never polls.
  - The row actions are in `dispensing.html:452-509`.
  - On a prepared row (G15): **Hand over** and **Cancel preparation**.
  - Otherwise:
    - **Dispense** (`selectPrescription`, `dispensing.ts:367-377`);
    - **Route** (`routeFromQueue`, `:362-365`), which navigates to
      `/pharmacy/stock-routing/:id`;
    - the shared clarification component (`dispensing.html:497-508`).
  - Opening the dispense form tells nobody.
- **The offline queue.** `offline-dispense.interceptor.ts:41,62` queues only
  `POST /pharmacy/dispense`. It replays the request later with its idempotency
  key. A replayed fill is a fill that physically happened while the network
  was down.

**Desired outcome.**

- A pharmacist can say "I'm on this one", and colleagues see it on their next
  refresh, at most a minute later.
- A claim nobody finishes lapses on its own.
- Anyone may take a row over, and the take-over is on the record.
- Finishing the work ends the claim without a second click: a fill, a
  preparation, routing, or a question to the prescriber.
- The queue can show only "mine" or only "unclaimed".

## 3. Actors & permissions

| Actor | May | Gate |
|---|---|---|
| PHARMACIST, PHARMACY_VERIFIER, HOSPITAL_ADMIN, SUPER_ADMIN *with a pinned hospital* | Claim, renew, release (own), take over; filter the queue by claim | Same `@PreAuthorize` as the work queue (`DispenseController.java:39`), plus a non-null `requireActiveHospitalId()` for every write, as in `loadAndValidatePrescription` (`DispenseServiceImpl.java:737-739`) |
| Same roles | See every claim on the queue: the holder's name, since when, and whether it is theirs | `GET /pharmacy/dispense/work-queue` |
| Any queue role | Release **only its own** claim; a colleague's active claim is taken over, not released | Service rule (AC-5) |
| Prescriber (DOCTOR etc.) | Withdraws or edits the order; either ends any claim | Existing `updatePrescription` rules; no new endpoint |
| DOCTOR, NURSE, MIDWIFE, PHARMACIST (dispatch gate, `controller/PrescriptionController.java:279-280`) | An SMS dispatch that sends the order out ends any claim | Existing gate |
| STORE_MANAGER, and NURSE, MIDWIFE and DOCTOR outside dispatch | Nothing new; never see claims | Not on the work-queue gate |
| Patient, proxy, both mobile apps | **Unaffected.** No patient read, SMS or app model changes | — |

**There is no "pharmacy lead" role.** The code knows only PHARMACIST and
PHARMACY_VERIFIER: `hospital-core/src/main/java` has 91 ×
`ROLE_PHARMACIST`, 13 × `ROLE_PHARMACY_VERIFIER`, and no other pharmacy role.
Recommended default: **any queue role may take over** (section 10, Q2).

**Tenancy and refusals.**

- Every claim endpoint locks the prescription with
  `findByIdAndHospitalIdForUpdate(prescriptionId, activeHospitalId)`.
- The following answer the **same 404 `prescription.notfound`**, the body the
  fill and routing writes already use:
  - an unknown id;
  - a prescription at another hospital. It is never locked, because the
    hospital is in the locking query (#825 security finding 3);
  - a caller with no pinned hospital (null scope).
- No claim endpoint takes a dispense id or a claim id, so no second
  identifier can become an existence oracle.
- **The exit-path releases** (section 5, rule 4) run inside writes that are
  already authorised and already hold the lock.
  - These include a prescriber's global-view `updatePrescription` and the
    clarification write's global-view branch
    (`PrescriptionClarificationService.java:182-184`).
  - They make **no** scope call of their own, exactly like
    `PreparedFillVoider` (`service/pharmacy/PreparedFillVoider.java:31-42`).
- A patient calling a claim endpoint gets 403 from `@PreAuthorize`.

## 4. User stories with acceptance criteria

### US-1 — A pharmacist says "I'm on this one"

**AC-1 — Claim an unclaimed row.**

- *Given* a prescription at the active hospital that:
  - has a status in `WORK_QUEUE_STATUSES`;
  - has no open preparation;
  - has no active claim.
- *When* a pharmacist sends
  `POST /pharmacy/dispense/work-queue/{prescriptionId}/claim`.
- *Then*, under the prescription row lock:
  - a claim row exists, with `claimed_by` = the caller and `claimed_at` = now
    (the service `Clock`);
  - `PRESCRIPTION_QUEUE_CLAIMED` is audited, with ids only;
  - the response is **200** with the claim: `claimedByUserId`,
    `claimedByName`, `claimedAt`, `expiresAt`, and `mine:true`.

**AC-2 — Colleagues see it.**

- *Given* AC-1 has happened.
- *When* any queue role reads `GET /pharmacy/dispense/work-queue`.
- *Then* that row carries `claim`, with:
  - the holder's display name;
  - `claimedAt` and `expiresAt`;
  - `mine`, true only for the holder.
- Rows without an active claim carry no `claim`; `NON_NULL` omits it.
- The decoration is one batched query per page, with no N+1.

**AC-3 — A held row cannot be claimed silently.**

- *Given* an active claim by user A.
- *When* user B sends a plain claim.
- *Then* the response is **409** `workqueue.claim.heldByOther`. Nothing
  changes, and nothing is audited.
- Two concurrent claims by A and B on an unclaimed row leave exactly one
  holder. The other gets 409.
- A Postgres IT proves it: the second claim waits on the row lock, then sees
  the first one's claim.

**AC-4 — Renew.**

- The current holder of an active claim claims it again.
- `claimed_at` is refreshed and the response is 200.
- Nothing is audited: a renewal is not a new fact.

### US-2 — A pharmacist lets go

**AC-5 — Release.**

- *Given* an active claim by the caller.
- *When* the caller sends
  `POST /pharmacy/dispense/work-queue/{prescriptionId}/claim/release`.
- *Then*:
  - the claim row is deleted;
  - `PRESCRIPTION_QUEUE_CLAIM_RELEASED` is audited with reason `RELEASED`;
  - the response is **200** with `data: null`.
- Releasing a row with **no active claim** (none, or an expired one) answers
  **200** and is idempotent. An expired row is deleted and audited as AC-7
  says.
- Releasing **another user's active** claim answers **409**
  `workqueue.claim.notHolder`, and nothing changes.

### US-3 — A colleague takes over

**AC-6 — Take over.**

- *Given* an active claim by A.
- *When* B sends
  `POST /pharmacy/dispense/work-queue/{prescriptionId}/claim/take-over`.
- *Then*:
  - the **same row is updated in place**: `claimed_by` = B and
    `claimed_at` = now;
  - `PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER` is audited with A's user id and A's
    `claimed_at`;
  - the response is 200 with `mine:true`.
- A take-over of a row with no active claim behaves as AC-1. It is audited as
  CLAIMED, plus EXPIRED when a stale row was replaced.
- A take-over of one's own claim behaves as AC-4.

### US-4 — A claim nobody finishes lapses

**AC-7 — Expiry without a sweep.**

- A claim is **active** iff `claimed_at > now − pharmacy.work-queue.claim.ttl`.
  - The TTL defaults to `PT30M`.
  - `now` comes from the service `Clock`, at read time and at write time.
- An expired claim:
  - is not shown on the queue;
  - does not count for **Mine**, and counts as **Unclaimed**;
  - does not block a plain claim: no 409, no take-over needed.
- When a write replaces or deletes an expired claim, it audits
  `PRESCRIPTION_QUEUE_CLAIM_EXPIRED` for the old holder.
  - The description carries the old holder's user id and `claimed_at`.
  - The actor is the user whose write found the expired claim.
- The TTL is not stored per row, so changing it applies to existing claims at
  once.
- There is no scheduler and no `@SchedulerLock`.

### US-5 — Finishing the work ends the claim; acting over a claim is a take-over

**AC-8 — Advisory: nobody is refused because of a claim.**

- *Given* an active claim by A.
- *When* B, not A, does any of the following:
  - a one-step dispense;
  - mark ready (G15);
  - route to a partner;
  - print for the patient;
  - back order;
  - request clarification;
  - SMS dispatch.
- *Then* the action succeeds or fails **exactly as it does today**.
- If the action commits, then in the same transaction:
  - A's claim is deleted;
  - `PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER` is audited with the action code,
    for example `by DISPENSE`.
- An offline-queued dispense replayed after someone else claimed the row is
  still recorded. This AC covers the replay; the interceptor is unchanged.

**AC-9 — The holder's own action ends the claim.**

- When A does one of the AC-8 actions, A's claim is deleted and
  `PRESCRIPTION_QUEUE_CLAIM_RELEASED` is audited with one of these reasons:
  - `DISPENSED`: a one-step dispense, whatever its outcome;
  - `PREPARED`: mark ready;
  - `ROUTED`: to a partner, or printed;
  - `BACK_ORDERED`;
  - `CLARIFICATION_REQUESTED`;
  - `DISPATCHED`.
- If the action fails and rolls back, the claim is still there: the release
  is in the same transaction.

### US-6 — Interplay with G15, withdrawal and transmission failure

**AC-10 — A prepared fill is not claimable; preparing ends the claim.**

- *Given* a prescription with an open PENDING fill (G15).
- *When* anyone claims it or takes it over.
- *Then* the response is **409** `dispense.ready.openPreparation`, the
  existing G15 message, checked under the lock.
- Mark ready releases any claim (AC-8, AC-9).
- Hand-over and cancel-ready are unchanged. They never meet a claim, because
  none can exist while the fill is prepared.
- After cancel-ready, the row is an ordinary, unclaimed queue row.
- A claim racing a mark-ready serialises on the lock: whichever commits
  second sees the first. A Postgres IT covers both orders.

**AC-11 — Withdrawal and edit end the claim.**

- *Given* an active claim.
- *When* the prescriber, through `PUT /prescriptions/{id}`, either:
  - withdraws the order (CANCELLED or DISCONTINUED; withdrawal is final,
    #812); or
  - makes any other successful edit.
- *Then*, in `updatePrescription`'s transaction and under its lock:
  - the claim is deleted;
  - `PRESCRIPTION_QUEUE_CLAIM_RELEASED` is audited with reason `WITHDRAWN` or
    `CHANGED`.

  This happens next to the G15 void (`PrescriptionServiceImpl.java:900`).
- A claim on a withdrawn order is impossible.
  - Claim and take-over re-check the status under the lock.
  - Any status outside `WORK_QUEUE_STATUSES` answers **409**
    `workqueue.claim.notInQueue`: withdrawn, DISPENSED, PENDING_CLARIFICATION,
    SENT_TO_PARTNER, and so on.
- A Postgres IT runs claim against withdrawal in both orders. Neither order
  ends with a claim on a withdrawn order.

**AC-12 — TRANSMISSION_FAILED (#818).**

- A TRANSMISSION_FAILED row is on the queue and can be claimed like any
  other.
- A **failed** SMS dispatch leaves the claim untouched. The order is the
  hospital's again, and whoever is preparing it in-house keeps it.
  - This is the `noRollbackFor` path that writes TRANSMISSION_FAILED
    (`PrescriptionSmsDispatchServiceImpl.java:128, 428-432`).
- A **successful** dispatch ends the claim as AC-8 and AC-9 say, with reason
  `DISPATCHED`.
  - This is `applyDispatchToPrescription` (`:487-491`), which sets
    SENT_TO_PARTNER.

### US-7 — The pharmacist filters the queue

**AC-13 — Mine / Unclaimed.**

- `GET /pharmacy/dispense/work-queue?claim=…` takes one of:
  - `MINE`: only rows with an active claim by the caller;
  - `UNCLAIMED`: only rows with no active claim, prepared rows included;
  - `ALL`, or no parameter: today's list.
- The paging metadata (`totalElements`, `totalPages`) is correct for each
  filter. Each filter has a count query with the same predicate.
- An unknown value answers **400**, through the existing
  `MethodArgumentTypeMismatchException` handler
  (`exception/GlobalExceptionHandler.java:306-315`).
- With the flag off, the parameter is ignored and the list is ALL.

### Cross-cutting

**AC-14 — Tenancy and roles.**

- On claim, take-over and release, these three answer **404**:
  - a prescription at another hospital;
  - a random id;
  - a null-scope caller.
- Their bodies have equal `status`, `error` and `message`. The comparison
  excludes `timestamp` and `path`, as G15 AC-14 does.
- Roles are asserted by reflection in `DispenseControllerTest`: each new
  controller method's `@PreAuthorize` value equals `getWorkQueue()`'s. No new
  slice test is needed (see the webmvctest-slice-scanning lesson).

**AC-15 — Audit without PHI.**

- Four new `AuditEventType` values:
  - `PRESCRIPTION_QUEUE_CLAIMED`;
  - `PRESCRIPTION_QUEUE_CLAIM_RELEASED`;
  - `PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER`;
  - `PRESCRIPTION_QUEUE_CLAIM_EXPIRED`.
- Each row has `entityType` `"PRESCRIPTION"` and `resourceId` = the
  prescription id.
- Descriptions carry ids, timestamps and reason or action codes only. They
  never carry a medication, patient or staff name.
- `DisclosureCategoryTest` still passes.
- The four `PORTAL.ENUM.AUDIT_EVENT_TYPE.*` keys exist in EN, FR and ES, in the
  same commit as the enum values. `i18n:enums` reads the Java enum (G15 A5).

**AC-16 — Flag and settings.**

- Two properties:
  - `pharmacy.work-queue.claim.enabled`, default **true**;
  - `pharmacy.work-queue.claim.ttl`, default `PT30M`.
- `GET /pharmacy/dispense/settings` adds `queueClaimEnabled` and
  `queueClaimTtlMinutes`.
- With the flag off:
  - the three claim endpoints answer **404** `workqueue.claim.disabled`;
  - the queue carries no `claim`, and ignores `claim=`;
  - the portal hides every claim control.
- The exit-path releases still run with the flag off. So turning the flag off
  and on again never brings back a claim whose work has ended.

**AC-17 — Portal.**

- **The row shows the claim**: "Being prepared by {name} · since {time}", or
  "You are preparing this · since {time}".
- **Buttons**:
  - **Claim**, on an unclaimed row;
  - **Release**, on one's own claim;
  - **Take over**, on a colleague's claim, with a confirm that names the
    holder.
- **Dispense claims the row first**, then opens the form.
  - On 409, the take-over confirm appears.
  - On any other claim failure, the form still opens (the claim is advisory),
    with a warning toast.
- **Route** asks the same confirm before navigating when a colleague holds
  the row.
- **Closing the form** without submitting releases a claim that the form
  itself made. It does not release a claim made with **Claim**.
- **A filter** (All / Mine / Unclaimed) sits above the queue.
- **The queue reloads**:
  - every 60 s while the tab is visible;
  - when `visibilitychange` makes the tab visible;
  - after any 409.

**AC-18 — i18n.**

- Every new backend message and UI string exists in EN, FR and ES.
- These gates pass:
  - `npm run i18n:parity`, `i18n:referenced`, `i18n:enums` and
    `i18n:translated`;
  - the backend bundle parity.

**AC-19 — Migration and schema.**

- V179 applies on Postgres (`LiquibaseSchemaIT`).
- V179 is registered (`MigrationRegistrationTest`).
- `EntitySchemaValidationIT` passes, with `created_at` and `updated_at`
  present (the V153/V154 lesson).

**AC-20 — Patients and apps unaffected.**

- None of the following changes:
  - `hospital-portal/src/app/patient-portal/`;
  - the patient medication DTOs
    (`payload/dto/medication/PatientMedicationResponseDTO.java`,
    `payload/dto/PrescriptionResponseDTO.java`);
  - the patient SMS bundles;
  - the Android and iOS apps.
- Review checks this from the diff's file list.

## 5. Business rules & edge cases

### 1. Advisory with explicit take-over — the decision

**Recommendation: advisory + take-over**, the brief's default. The code
argues for it, not against it:

1. **The data is already safe.** A second fill or preparation is refused by
   the G15 lock, `uq_disp_one_pending_per_rx`, `Prescription.version` and the
   quantity check (section 2). An exclusive claim adds no safety; it only
   moves the refusal earlier.
2. **An exclusive claim can refuse a fill that already happened.** The
   offline queue replays `POST /pharmacy/dispense` once the network returns
   (`offline-dispense.interceptor.ts:41,62`). If a colleague claimed the row
   in between, an exclusive claim would refuse the record of medication the
   patient already holds.
3. **An abandoned claim would block care.** The pharmacist goes home, or the
   browser crashes. For up to the TTL, nobody could fill the order without a
   lead — and no lead role exists (section 3).
4. **It would touch every write.** Exclusivity needs a refusal and an
   override in seven write paths, owned by four services. It would also reach
   the prescriber's edit and withdrawal, which must never wait for a pharmacy.

So the two halves behave differently:

- The **claim endpoint** is exclusive. A plain claim of a held row answers
  409, so nobody steals a row silently.
- **Every other action** is advisory. It is allowed, ends the claim, and is
  audited as a take-over when the actor is not the holder.

### 2. Storage — a claim table, not prescription columns

The claim lives in `clinical.prescription_queue_claims`, one row per
prescription. It is **not** a pair of `claimed_by`/`claimed_at` columns on
`clinical.prescriptions`, because writing the prescription row on every claim
would:

- **Bump `@Version`** (`Prescription.java:383-384`) several times per order.
  Every writer that loads without a lock and saves later would then get a
  spurious `concurrent.modification` 409 on clinical work. Examples are the
  partner replies (`partner/PartnerExchangeService.java:315`) and
  `StockOutRoutingServiceImpl.partnerRespond` (`:359-412`).
- **Run `@PreUpdate validate()`** (`Prescription.java:410-415`). Its
  `validateContextIntegrity` (`:439-458`) throws on any legacy row whose
  staff, assignment or encounter do not line up, so a claim would answer 500
  on such rows.
- **Change the prescription's `updated_at`**, which the prescriber's screens
  read as "the order changed".
- **Put pharmacy coordination state into the clinical record** that FHIR and
  the patient reads map.

A separate table has none of these effects.

- It keeps history out of the row; the audit log is the history.
- It is deleted with the prescription. `deletePrescription` hard-deletes
  (`PrescriptionServiceImpl.java:910-922`), so the FK cascades.

**No `hospital_id` column.** Every read reaches the claim through a
prescription already filtered by hospital: the queue page, or the scoped
lock. A denormalised copy could only drift.

### 3. One lock rule (G15 rule 1, extended)

**Every path that creates, renews, moves or deletes a claim holds the
prescription row lock before it reads the claim row.**

| Path | Lock |
|---|---|
| Claim, take-over, release endpoints | `findByIdAndHospitalIdForUpdate(prescriptionId, hospitalId)`; a null hospital → 404 before any query |
| One-step dispense, mark ready | Already locked in `loadAndValidatePrescription` (`DispenseServiceImpl.java:747-749`) |
| Route to partner, print, back order | Already locked in `findPrescriptionForWrite` (`StockOutRoutingServiceImpl.java:599-601`) |
| Request clarification | Already locked in `findInScopeForUpdate` (`PrescriptionClarificationService.java:101, 178-185`) |
| SMS dispatch | Already locked (`PrescriptionSmsDispatchServiceImpl.java:138`) |
| Withdrawal and edit | Already locked (`PrescriptionServiceImpl.java:840-842`) |

**Lock order is prescription, then dispense, then claim.**

- Every path above touches the claim row last, after any dispense write.
- The claim endpoints touch no dispense row. They only read one, through
  `existsByPrescription_IdAndStatus`.

So no new deadlock pair appears.

**What this closes:**

- claim vs claim;
- claim vs mark-ready (AC-10);
- claim vs withdrawal (AC-11);
- claim vs any exit write (AC-8, AC-9);
- take-over vs release.

### 4. Which writes end a claim (`releaseOnExit`)

A claim ends when its holder's work on the order ends. The release is the
**last step** of the write, in its transaction, so a rollback keeps the claim.

| Write | Reason (holder) | Notes |
|---|---|---|
| One-step dispense (`createDispenseTransactionally`, `DispenseServiceImpl.java:348-410`) | `DISPENSED` | Any outcome, PARTIALLY_FILLED included: the pharmacist's work is done for now |
| Mark ready (`markReadyForCollectionTransactionally`, `:412-449`) | `PREPARED` | After this, a claim is refused while the fill is prepared (AC-10) |
| Route to partner (`:205`), print (`:276`) | `ROUTED` | The order leaves the queue |
| Back order (`:314`) | `BACK_ORDERED` | Stays on the queue as PENDING_STOCK; restock may take days |
| Request clarification (`PrescriptionClarificationService.java:97`) | `CLARIFICATION_REQUESTED` | The order leaves the queue |
| Successful SMS dispatch (`applyDispatchToPrescription`, `:487`) | `DISPATCHED` | A failed dispatch keeps the claim (AC-12) |
| Withdrawal / edit (`PrescriptionServiceImpl.java:900`) | `WITHDRAWN` / `CHANGED` | Next to `voidPreparedFillOnEdit` |

The audit row depends on what the write finds:

| Claim found | Audit |
|---|---|
| Active, and the actor is the holder | `PRESCRIPTION_QUEUE_CLAIM_RELEASED`, with the reason |
| Active, and the actor is not the holder | `PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER`, with the action code |
| Expired | `PRESCRIPTION_QUEUE_CLAIM_EXPIRED` |
| None | Nothing |

**Not wired, on purpose:**

- **Hand-over and cancel-ready.** No claim can exist on a prepared row.
- **Partner accept, reject, no-show and confirm, and the inbound partner
  SMS.** A routed order's claim was already released when it was routed.
- **Refill approval.** A DISPENSED order's claim was already released by its
  dispense.

A claim left behind by a path nobody foresaw is harmless.

- It is invisible once the order leaves the queue, and lapses within the TTL.
- Only the prescription delete removes such a row, by cascade.
- No purge job is added.

### 5. Writes update in place, releases delete

- **Claiming over an expired row, taking over, and renewing all UPDATE the
  existing row**: `claimed_by`, `claimed_at` and `updated_at`. They never
  delete and then insert.
  - Hibernate flushes inserts before deletes. A delete plus an insert for the
    same `prescription_id` in one transaction would violate the unique
    constraint.
- **Release** (from the endpoint or an exit path) deletes the row.
- **These are ordinary managed-entity writes, not bulk JPQL.**
  - The lock already serialises them, so G15's conditional-UPDATE machinery
    (rule 7) is not needed.
  - `BaseEntity`'s `@PreUpdate` sets `updated_at`.
- `uq_rx_queue_claim_prescription` is the last line of defence if a future
  path forgets the lock.

### 6. Expiry, computed

- `activeAfter = LocalDateTime.now(clock).minus(ttl)`. A claim is active iff
  `claimedAt.isAfter(activeAfter)`.
- One Java clock serves writes and reads.
  - The queries receive `activeAfter` as a parameter instead of using the
    database's `now()`.
  - So tests can drive time with a fixed `Clock`.
- `expiresAt` in the DTO is `claimedAt + ttl`, for display only.
- Timestamps are server-local `LocalDateTime`, like `Dispense.createdAt` and
  every other pharmacy timestamp (G15 rule 14).
- There is no sweep.
  - Expiry is a predicate.
  - The EXPIRED audit is written when a later write finds the stale row
    (AC-7).
  - The cost: an expired claim nobody touches again has no EXPIRED audit row.
    This is accepted (section 10, Q8).

### 7. What may be claimed

Under the lock, the checks run in this order:

1. The status must be in `WORK_QUEUE_STATUSES`, otherwise 409
   `workqueue.claim.notInQueue`. PARTNER_ACCEPTED is included: its action is
   a no-show statement.
2. There must be no open preparation, otherwise 409
   `dispense.ready.openPreparation`.
3. The existing claim is checked last.

### 8. 409 messages contain no colon

- `GlobalExceptionHandler.handleConflictException` (`:48-57`) splits a message
  on its first `:` (G15 A3).
- So every new 409 is `new ConflictException(MessageUtil.resolve(key))`, with
  colon-free text in all locales.
- **No holder name** is interpolated into the message:
  - a name could contain a colon;
  - the queue reload shows the holder anyway.

### 9. Response shapes

- Claim and take-over: 200 with a `WorkQueueClaimDTO`.
- Release: 200 with `data: null`.
- Each status code means one thing per endpoint (section 6, "API contract").

### 10. Concurrency with the portal's view

- The queue is a snapshot.
- A colleague's claim appears within 60 s (the AC-17 polling), or at once
  after any 409.
- A pharmacist acting on a stale view is never blocked (AC-8). The take-over
  is recorded.

## 6. Architecture fit

### Data model — V179

The file is `V179__prescription_queue_claims.sql`. Register it in
`changelog.xml` after V178 with `runOnChange="false"`,
`splitStatements="true"` and `stripComments="false"`; it has no DO blocks.

```sql
CREATE TABLE IF NOT EXISTS clinical.prescription_queue_claims (
    id              UUID PRIMARY KEY,
    prescription_id UUID NOT NULL,
    claimed_by      UUID NOT NULL,
    claimed_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_rx_queue_claim_prescription UNIQUE (prescription_id),
    CONSTRAINT fk_rx_queue_claim_prescription FOREIGN KEY (prescription_id)
        REFERENCES clinical.prescriptions (id) ON DELETE CASCADE,
    CONSTRAINT fk_rx_queue_claim_user FOREIGN KEY (claimed_by)
        REFERENCES "security".users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_rx_queue_claim_user
    ON clinical.prescription_queue_claims (claimed_by, claimed_at);
```

- **`created_at` and `updated_at` are NOT NULL**, because the entity extends
  `BaseEntity` (`model/BaseEntity.java:26-30`; the V153 → V154 lesson).
- **The unique constraint is a full constraint**, so it is safe to declare in
  JPA as well: `@Table(uniqueConstraints = …)`, with the same name. H2 builds
  the same thing. (Contrast G15 B3, whose index was partial.)
- **No CHECK constraints.**
  - `clinical.prescriptions` has none (coordinator, 2026-10-07), and this
    migration adds none to it.
  - Prod has 0 dispenses (coordinator, 2026-10-07).
  - The new table starts empty. There is no backfill.
- **Grants.**
  - Prod privileges come from the hand-run `R__prod_role_grants.sql`, which
    runs `ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA clinical …`
    (`:98-109`).
  - When the migration runs as `postgres`, as it did for the V166 and V173
    tables, `hms_app` gets access automatically.
  - Coordinator: after the deploy, check that
    `\dp clinical.prescription_queue_claims` shows `hms_app`. If it does not,
    grant by hand.

### Backend components

| Component | Change |
|---|---|
| New `model/pharmacy/PrescriptionQueueClaim.java` | Extends `BaseEntity`. `@Table(schema="clinical", name="prescription_queue_claims", uniqueConstraints=@UniqueConstraint(name="uq_rx_queue_claim_prescription", columnNames="prescription_id"))`. Fields: `@OneToOne(fetch=LAZY) prescription`, `@ManyToOne(fetch=LAZY) claimedBy` (`User`), `claimedAt` |
| New `repository/pharmacy/PrescriptionQueueClaimRepository.java` | `findByPrescription_Id`; `@EntityGraph(attributePaths="claimedBy") findByPrescription_IdIn(ids)` for the page |
| `repository/PrescriptionRepository.java` | Two `@Query` page methods, each with an explicit `countQuery` and the same hospital and status predicates as `findByHospital_IdAndStatusIn` (`:111`). **Mine**: rows with an active claim by `:userId` — `exists (select c from PrescriptionQueueClaim c where c.prescription = p and c.claimedBy.id = :userId and c.claimedAt > :activeAfter)`. **Unclaimed**: rows with no active claim — `not exists (… c.claimedAt > :activeAfter)` |
| New `enums/QueueClaimFilter.java` | ALL, MINE, UNCLAIMED |
| New `enums/QueueClaimReleaseReason.java` | RELEASED, DISPENSED, PREPARED, ROUTED, BACK_ORDERED, CLARIFICATION_REQUESTED, DISPATCHED, WITHDRAWN, CHANGED |
| New `service/pharmacy/PrescriptionQueueClaimService.java` | See the list below the table |
| New `payload/dto/pharmacy/WorkQueueClaimDTO.java` | `prescriptionId`, `claimedByUserId`, `claimedByName` (through `DispenseMapper.displayNameOf`, `mapper/pharmacy/DispenseMapper.java:78`), `claimedAt`, `expiresAt`, `mine` |
| `payload/dto/pharmacy/WorkQueuePrescriptionDTO.java` | `private WorkQueueClaimDTO claim;`, next to `readyForCollection` (`:91`) |
| `payload/dto/pharmacy/DispenseSettingsDTO.java` | Adds `queueClaimEnabled` and `queueClaimTtlMinutes`. `DispenseController.java:62-63` calls its `@AllArgsConstructor` positionally; switch that call to the builder |
| `service/pharmacy/DispenseService(Impl).java` | `getWorkQueue(Pageable, QueueClaimFilter)` picks the repository method by filter and adds the claim decoration in one batched query. `releaseOnExit` at the end of `createDispenseTransactionally` and `markReadyForCollectionTransactionally` |
| `service/pharmacy/StockOutRoutingServiceImpl.java` | `releaseOnExit` at the end of `routeToPartner`, `printForPatient` and `backOrder` |
| `service/pharmacy/PrescriptionClarificationService.java` | `releaseOnExit` at the end of `requestClarification` |
| `service/impl/PrescriptionSmsDispatchServiceImpl.java` | `releaseOnExit` on the success path only |
| `service/PrescriptionServiceImpl.java` | `releaseOnExit` next to `voidPreparedFillOnEdit` (`:900`); WITHDRAWN or CHANGED by the same predicate (`:697-704`) |
| `controller/pharmacy/DispenseController.java` | `GET /work-queue` gains `@RequestParam(defaultValue="ALL") QueueClaimFilter claim`; three new POSTs; the settings fields |
| `enums/AuditEventType.java` | The four new values, next to the DISPENSE_* block (`:103-107`) |
| `src/main/resources/application.properties` | Next to the G15 block (`:472-476`): `pharmacy.work-queue.claim.enabled=${PHARMACY_WORK_QUEUE_CLAIM_ENABLED:true}` and `pharmacy.work-queue.claim.ttl=${PHARMACY_WORK_QUEUE_CLAIM_TTL:PT30M}` |
| `messages.properties`, `messages_en/fr/es.properties` | See "i18n — backend" |

`PrescriptionQueueClaimService` provides:

- `claim`, `takeOver` and `release`. Each one checks, in order:
  1. the flag;
  2. scope (404);
  3. takes the lock;
  4. the status;
  5. the open preparation;
  6. the claim rules;
  7. writes the audit.
- `releaseOnExit(Prescription, QueueClaimReleaseReason, String actionCode)`.
  It makes **no scope call**, and the caller holds the lock. It is modelled
  on `PreparedFillVoider`.
- `activeClaimsFor(List<Prescription>)`, for the queue.
- `isEnabled()` and `ttl()`.

**Wiring.**

- `releaseOnExit` lives in the claim service, which is injected into the four
  services.
- The claim service is a plain `@Service` whose only dependencies are its
  repository and `PharmacyServiceSupport`. It depends on none of its callers,
  so it adds no cycle.
- The claim endpoints are writes. They keep the conventional
  `WriteAuditInterceptor` DATA_* row, as G15's endpoints do (no
  `@WriteAudited(skip)`).

### API contract

All endpoints are under `/pharmacy/dispense`, with the same roles as the work
queue.

**`POST /work-queue/{prescriptionId}/claim`** — no body.

- 200 → `WorkQueueClaimDTO`: a new claim, or a renewal.
- 404 → either:
  - `prescription.notfound`, for any scope failure, with an identical body;
  - `workqueue.claim.disabled`, when the flag is off.
- 409 → one of:
  - `workqueue.claim.heldByOther`;
  - `workqueue.claim.notInQueue`;
  - `dispense.ready.openPreparation`;
  - `concurrent.modification`.

**`POST /work-queue/{prescriptionId}/claim/take-over`** — no body.

- 200 → `WorkQueueClaimDTO`.
- 404 → as for claim.
- 409 → `workqueue.claim.notInQueue`, `dispense.ready.openPreparation` or
  `concurrent.modification`.

**`POST /work-queue/{prescriptionId}/claim/release`** — no body.

- 200 → `data: null`, whether a claim was released or there was nothing to
  release.
- 404 → as for claim.
- 409 → `workqueue.claim.notHolder` or `concurrent.modification`.
- Release does **not** check the status: ending a claim is always allowed.

**`GET /work-queue?claim=ALL|MINE|UNCLAIMED`**

- The `Page` shape is unchanged; each row may carry `claim`.
- 400 on an unknown value.

**`GET /settings`** adds `queueClaimEnabled: boolean` and
`queueClaimTtlMinutes: number`.

**The offline queue.** The interceptor matches only the bare
`POST /pharmacy/dispense` (`offline-dispense.interceptor.ts:62`).

- The claim endpoints are therefore never queued offline.
- A claim made while offline simply fails, which is acceptable because the
  claim is advisory.

### Portal — staff (`hospital-portal/src/app/`)

- **`services/pharmacy.service.ts`**
  - A `WorkQueueClaim` interface, and `WorkQueuePrescription.claim?`
    (interface at `:334`, next to `readyForCollection?` at `:363`).
  - `getDispenseWorkQueue(page, size, claim: 'ALL'|'MINE'|'UNCLAIMED' = 'ALL')`
    (`:786-792`).
  - New calls `claimQueueRow`, `takeOverQueueRow` and `releaseQueueRow`.
  - `DispenseSettings` (`:319`) gains `queueClaimEnabled` and
    `queueClaimTtlMinutes`.
- **`pharmacy/dispensing.{ts,html,scss,spec.ts}`**
  - **New signals:**
    - `queueClaimEnabled`;
    - `claimFilter`, default `'ALL'`, remembered in `localStorage` inside a
      try/catch, as a per-viewer convenience;
    - `claimAction`, the pending take-over confirm;
    - `formClaimedRowId`.
  - **Status cell:** the claim badge goes under the G15 badge
    (`dispensing.html:390-401`).
  - **Actions cell** (`:452-509`): Claim, Release and Take over buttons,
    shown only when `queueClaimEnabled()` is true and the row is not prepared.
  - **`selectPrescription`** (`dispensing.ts:367`) claims first.
    - A 409 `heldByOther` opens the take-over confirm.
    - Any other failure opens the form anyway, with a warning toast.
    - When the form made the claim, it remembers `formClaimedRowId`.
  - **`closeForm`** (`:496`) releases `formClaimedRowId` if it is set and the
    form was not submitted.
  - **`routeFromQueue`** (`:362`) asks for confirmation before navigating
    when a colleague holds the row.
  - **Polling.**
    - `interval(60_000)` while `document.visibilityState === 'visible'`, plus
      a `visibilitychange` listener.
    - Both are torn down in `ngOnDestroy` (`:172`).
    - The pattern already exists in `nurse-station/nurse-station.ts` and
      `patient-tracker/patient-tracker.component.ts`.
    - No reload while the form or a confirm is open.
  - **Filter control:** a labelled segmented group of radio inputs above the
    table (`:336-340`), keyboard-operable, per the angular-portal-component
    skill.
- **i18n** in `assets/i18n/{en,fr,es}.json`. The `PHARMACY` block is at
  `:7452`; the audit enum keys go next to `DISPENSE_READY` (`:5324`).
- **Accessibility.** The axe Playwright smoke does **not** cover
  `/pharmacy/dispensing`: it was attempted and reverted over colour-contrast
  debt (`e2e/a11y.spec.ts:36-39`). Keyboard use and labels are checked in
  `dispensing.spec.ts` and by hand.

### Patients and apps

No change. The claim exists only on the staff work-queue projection. None of
these gains a field: a patient read, a patient SMS, a FHIR resource, the
Android or iOS models (AC-20).

### i18n — backend (`MessageUtil.resolve`, `utility/MessageUtil.java:21`)

| key | EN | FR | ES |
|---|---|---|---|
| `workqueue.claim.heldByOther` | Another pharmacist is preparing this prescription. Reload the queue to see who, or take it over. | Un autre pharmacien prépare cette ordonnance. Rechargez la file pour voir qui, ou reprenez-la. | Otro farmacéutico está preparando esta receta. Vuelva a cargar la cola para ver quién, o tome el relevo. |
| `workqueue.claim.notHolder` | Only the pharmacist preparing this prescription can release it. Take it over first. | Seul le pharmacien qui prépare cette ordonnance peut la libérer. Reprenez-la d''abord. | Solo el farmacéutico que prepara esta receta puede liberarla. Tome el relevo primero. |
| `workqueue.claim.notInQueue` | This prescription is no longer on the work queue. | Cette ordonnance n''est plus dans la file de travail. | Esta receta ya no está en la cola de trabajo. |
| `workqueue.claim.disabled` | Work-queue claims are turned off. | La prise en charge des ordonnances est désactivée. | La asignación de recetas está desactivada. |

`''` escapes an apostrophe for MessageFormat, as G15's bundles do.

### i18n — UI

| key | EN | FR | ES |
|---|---|---|---|
| `PHARMACY.CLAIM.BEING_PREPARED_BY` | Being prepared by {{name}} | En préparation par {{name}} | En preparación por {{name}} |
| `PHARMACY.CLAIM.MINE` | You are preparing this | Vous préparez cette ordonnance | Usted está preparando esta receta |
| `PHARMACY.CLAIM.SINCE` | since {{time}} | depuis {{time}} | desde las {{time}} |
| `PHARMACY.CLAIM.CLAIM` | Claim | Prendre en charge | Hacerse cargo |
| `PHARMACY.CLAIM.RELEASE` | Release | Libérer | Liberar |
| `PHARMACY.CLAIM.TAKE_OVER` | Take over | Reprendre | Tomar el relevo |
| `PHARMACY.CLAIM.TAKE_OVER_CONFIRM` | {{name}} has been preparing this prescription since {{time}}. Take it over? | {{name}} prépare cette ordonnance depuis {{time}}. La reprendre ? | {{name}} está preparando esta receta desde las {{time}}. ¿Tomar el relevo? |
| `PHARMACY.CLAIM.CLAIMED` | You are now preparing this prescription. | Vous préparez maintenant cette ordonnance. | Ahora está preparando esta receta. |
| `PHARMACY.CLAIM.RELEASED` | Prescription released. | Ordonnance libérée. | Receta liberada. |
| `PHARMACY.CLAIM.TAKEN_OVER` | You have taken this prescription over. | Vous avez repris cette ordonnance. | Ha tomado el relevo de esta receta. |
| `PHARMACY.CLAIM.FAILED` | Could not update who is preparing this prescription. | Impossible de mettre à jour qui prépare cette ordonnance. | No se pudo actualizar quién prepara esta receta. |
| `PHARMACY.CLAIM.FILTER_LABEL` | Show | Afficher | Mostrar |
| `PHARMACY.CLAIM.FILTER.ALL` | All | Toutes | Todas |
| `PHARMACY.CLAIM.FILTER.MINE` | Mine | Les miennes | Las mías |
| `PHARMACY.CLAIM.FILTER.UNCLAIMED` | Unclaimed | Non prises en charge | Sin asignar |
| `PORTAL.ENUM.AUDIT_EVENT_TYPE.PRESCRIPTION_QUEUE_CLAIMED` | Work-queue prescription claimed | Ordonnance prise en charge | Receta asignada |
| `PORTAL.ENUM.AUDIT_EVENT_TYPE.PRESCRIPTION_QUEUE_CLAIM_RELEASED` | Work-queue claim released | Prise en charge libérée | Asignación liberada |
| `PORTAL.ENUM.AUDIT_EVENT_TYPE.PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER` | Work-queue claim taken over | Prise en charge reprise | Asignación relevada |
| `PORTAL.ENUM.AUDIT_EVENT_TYPE.PRESCRIPTION_QUEUE_CLAIM_EXPIRED` | Work-queue claim expired | Prise en charge expirée | Asignación caducada |

The aria-labels of the new buttons use the same keys.

### Audit and logging

- Every event goes through
  `PharmacyServiceSupport.logAudit(type, description, resourceId, "PRESCRIPTION")`
  (`service/pharmacy/PharmacyServiceSupport.java:74-87`). It is best effort:
  a failure is logged and swallowed, as for every pharmacy event.
- Descriptions carry ids and timestamps only. For example:
  - `Work-queue claim taken, prescription <uuid>`;
  - `Work-queue claim taken over from user <uuid> (claimed <ts>) by DISPENSE, prescription <uuid>`;
  - `Work-queue claim of user <uuid> (claimed <ts>) expired at <ts>, prescription <uuid>`.
- No new log line carries a name.

## 7. Security & privacy

| Threat | Control |
|---|---|
| Claiming or probing another hospital's prescription | The hospital is in the locking query; unknown, foreign and null-scope ids give the same 404 body (AC-14) |
| Locking another tenant's row (a lock-based DoS) | `findByIdAndHospitalIdForUpdate` never locks a foreign row (#825 finding 3) |
| A pharmacist silently stealing a colleague's row | A plain claim of a held row is 409; a take-over is explicit and audited; an implicit take-over by acting is audited (AC-3, AC-6, AC-8) |
| A claim blocking care: an abandoned claim, an offline replay, a prescriber's withdrawal | Advisory: no clinical write is refused because of a claim; the TTL lapses it (rule 1, AC-7, AC-8) |
| A claim on a withdrawn or prepared order misleading staff | Status and open-preparation checks under the lock; withdrawal, edit and ready end the claim in the same transaction (AC-10, AC-11) |
| A lost update, or a duplicate claim row | The lock, update-in-place, and the unique constraint (rules 3 and 5) |
| A deadlock surfacing as a 500 | Fixed lock order; the existing 409 `concurrent.modification` handlers (`GlobalExceptionHandler.java:80`) |
| A colon in a 409 message splitting it into a false field | Colon-free messages, with no interpolated names (rule 8) |
| PHI in audit or logs | Ids, timestamps and reason codes only (AC-15) |
| Staff names exposed beyond the pharmacy team | The holder's name is only on the work-queue projection, which only the four pharmacy-queue roles can read |
| Spoofing the claimer | The holder is always `roleValidator.getCurrentUserId()`; no body field names a user |
| Patients seeing staff coordination | Not on any patient surface (AC-20) |

## 8. Test plan

"Falsify" means revert exactly the change named; the test must then fail
(falsify-exactly-the-change).

**The Postgres IT** is a new class, `QueueClaimConcurrencyPostgresIT`, in
`src/test/java/com/example/hms/service/pharmacy/`. It is modelled on
`PreparedFillConcurrencyPostgresIT`:

- `@Testcontainers @DataJpaTest`, `postgres:16-alpine`, Liquibase on, and
  `Propagation.NOT_SUPPORTED`;
- the `race(first, second)` harness (`:490-515`), which proves the second
  writer is WAITING on the row lock before it releases the first.

It `@Import`s the real `PrescriptionQueueClaimService`, `DispenseServiceImpl`,
`PreparedFillVoider` and `StockOutRoutingServiceImpl`, and mocks only the
caller identity and the side channels.

| AC | Test | Falsify by |
|---|---|---|
| AC-1 | `PrescriptionQueueClaimServiceTest`: the row is saved with the caller and the clock time; CLAIMED is audited with ids only; the locked read is used (strict stub on `findByIdAndHospitalIdForUpdate`) | Use `findById`; drop the audit call |
| AC-2 | `DispenseServiceImplTest` work queue: `claim` only on claimed rows, `mine` per caller, the display name; the repository is called once per page | Decorate per row (the call count fails); compute `mine` as always true |
| AC-3 | Unit: B's plain claim over A's active claim → 409, no save, no audit. **IT** `claimVersusClaim`: the second claim waits, then gets 409; one row | Remove the held-by-other check (unit); replace the locked read with `findById` (IT: both commit, giving a unique violation or two holders) |
| AC-4 | Unit: the holder's re-claim updates `claimedAt` and writes no audit | Audit on renew; refuse renew |
| AC-5 | Unit: the holder's release deletes the row and writes RELEASED; nothing to release → 200, no audit; a non-holder of an active claim → 409 | Allow a non-holder release |
| AC-6 | Unit: take-over updates the same entity (no delete) and writes TAKEN_OVER with the previous holder's id. **IT** `takeOverPersistsInPlace`: after commit, one row with holder B, re-read in a new transaction | Implement it as delete + insert (the IT gets a unique violation at flush) |
| AC-7 | Unit with a fixed `Clock`: a claim made at T−31 min is not on the queue, counts as UNCLAIMED, and a plain claim succeeds with EXPIRED + CLAIMED; at T−29 min it is still active; a TTL change applies at once | Invert the age comparison; store the expiry per row |
| AC-8 | One unit test per path (dispense, ready, route, print, back order, clarification, dispatch): B acting over A's claim succeeds and calls `releaseOnExit` with the action code; the claim service writes TAKEN_OVER. **IT** `claimThenOneStepByAnother`: the fill commits and the claim row is gone | Make `releaseOnExit` throw on another holder (the advisory tests fail) |
| AC-9 | The same unit tests with the holder acting → RELEASED with the reason; a refused dispense (400) leaves the claim (no `releaseOnExit` call) | Call `releaseOnExit` before validation |
| AC-10 | Unit: a claim with an open preparation → 409 `dispense.ready.openPreparation`. **IT** `claimThenReady` and `readyThenClaim`: never a claim beside an open preparation | Remove the open-preparation check; drop the PREPARED release |
| AC-11 | `PrescriptionServiceImplTest`: withdrawal → WITHDRAWN, edit → CHANGED, a global-view super-admin included (no scope call). **IT** `claimThenWithdrawal` and `withdrawalThenClaim`: the end state is never a claim on a withdrawn order | Remove the call at `:900`; drop the status check in claim |
| AC-12 | `PrescriptionSmsDispatchServiceImplTest`: success → DISPATCHED release; failure (TRANSMISSION_FAILED) → no release; a TRANSMISSION_FAILED row can be claimed | Release on the failure path too |
| AC-13 | `PrescriptionRepository` `@DataJpaTest` (H2): MINE and UNCLAIMED with active, expired and other-user claims; `totalElements` matches. Controller binding: `claim=BOGUS` → 400 | Drop the `claimedAt > :activeAfter` term; omit `countQuery` |
| AC-14 | Unit: foreign, random and null-scope ids → equal `status`, `error` and `message` on all three endpoints. Reflection in `DispenseControllerTest`: each new method's `@PreAuthorize` equals `getWorkQueue()`'s | Throw a different key for foreign ids; widen a gate |
| AC-15 | Unit, seeded with distinctive names: the audit descriptions contain no medication, patient or staff name. `DisclosureCategoryTest`; `npm run i18n:enums` | Put the holder's name in the description; delete one FR enum key |
| AC-16 | Unit: flag off → the three endpoints answer 404, the queue has no `claim`, `claim=MINE` is ignored, and the exit releases still run; `/settings` reports both values | Gate `releaseOnExit` on the flag |
| AC-17 | `dispensing.spec.ts`: the badge text; the buttons per state; Dispense claims first; a 409 opens the confirm; a network error still opens the form; closing releases only a form-made claim; the Route confirm; the filter passes `claim=`; polling pauses when hidden and is torn down on destroy; nothing shows with the flag off | Remove the release in `closeForm`; poll while hidden |
| AC-18 | `i18n:parity`, `i18n:referenced`, `i18n:enums`, `i18n:translated`; backend bundle parity | Delete one ES key |
| AC-19 | `MigrationRegistrationTest`, `LiquibaseSchemaIT`, `EntitySchemaValidationIT` (Docker). **IT**: a direct JDBC insert of a second row for one prescription → unique violation | Unregister V179; drop the constraint |
| AC-20 | A review check of the diff's file list | — |
| Rule 3 | **IT** `takeOverVersusRelease`: the two serialise, and the end state is consistent — either B holds the claim or there is none, never A after a successful take-over | Use `findById` in release |

**Gates before every push:**

- the full backend suite, including the Docker ITs;
- Karma;
- lint and format;
- the i18n gates.

After any merge, check the changelog (V179 appears once, after V178) and the
i18n files by hand (never-click-update-branch).

## 9. Rollout

1. **V179 ships with the code.**
   - It is additive: a new, empty table and one index.
   - No backfill.
   - The coordinator checks `hms_app`'s privileges on the new table after the
     deploy (section 6).
2. **Flags.**
   - `PHARMACY_WORK_QUEUE_CLAIM_ENABLED` defaults to **true**. Nothing is
     refused because of a claim, so the feature cannot block work, and the
     flag works as a kill switch, like G15's.
   - `PHARMACY_WORK_QUEUE_CLAIM_TTL` defaults to `PT30M`.
   - Railway needs no new variables unless the user wants different values.
3. **Compatibility.**
   - `claim` on the queue rows and the new settings fields are additive. An
     old portal bundle ignores them.
   - `claim=` is optional.
   - No mirrored enum (the portal status enums, the apps) gains a value.
   - The new audit enum values ship with their portal labels in the same PR.
4. **Rollback.**
   - Set the flag off. Claims vanish from the UI at once, and the exit
     releases keep the table clean.
   - A code revert leaves an unused table that old code never reads.
   - V179 is forward-only.
5. **Prod.** The develop → main sync happens only on the user's "sync". That
   sync is a prod deploy.

## 10. Out of scope / decisions / open questions

### Out of scope (record as tasklist debt in T9)

- Showing the claim on `/pharmacy/stock-routing/:id` and inside the shared
  clarification dialog. The server still records the implicit take-over.
- Telling the previous holder that their row was taken over, in-app or by
  push.
- Claims on lab, imaging or any other queue.
- A purge of claim rows left behind by a path that does not release them
  (section 5, rule 4). They are invisible and harmless.
- Server-push (SSE or WebSocket) claim updates. v1 polls.

### Open questions — each with a recommended default (user to confirm)

1. **Advisory or exclusive?** Default: **advisory + explicit take-over**
   (rule 1). The claim endpoint refuses a silent steal; no clinical write is
   refused.
2. **Who may take over?** Default: **any queue role** — PHARMACIST,
   PHARMACY_VERIFIER, HOSPITAL_ADMIN, or SUPER_ADMIN with a pinned hospital —
   always audited.
   - No "pharmacy lead" role exists.
   - Restricting it to PHARMACY_VERIFIER or HOSPITAL_ADMIN would strand a
     pharmacist working the night shift alone.
3. **TTL?** Default: **30 minutes**, set by `PHARMACY_WORK_QUEUE_CLAIM_TTL`.
   - Renewal is the holder claiming again; reopening the form does it.
   - There is no background heartbeat.
4. **Auto-claim when the dispense form opens?** Default: **yes**. Closing the
   form unsubmitted releases a claim the form made. Without auto-claim,
   claims depend on discipline, and the feature does not solve G13.
5. **Does a prescriber edit end the claim?** Default: **yes** (`CHANGED`).
   This mirrors the G15 decision that any edit voids a preparation: the
   pharmacist must re-read the order.
6. **Does a back order end the claim?** Default: **yes**. Restock takes days,
   far beyond the TTL.
7. **Does a failed SMS dispatch end the claim?** Default: **no**. The order is
   the hospital's again, and the in-house preparer keeps it.
8. **Expiry audit?** Default: **lazy**, with no scheduler.
   - EXPIRED is written when a later write finds the stale row.
   - An expired claim nobody touches leaves no EXPIRED row. The CLAIMED row
     and the TTL prove that it lapsed.
9. **Queue freshness?** Default: **poll every 60 s while the tab is
   visible**, plus on focus and after any 409.
10. **Can a prepared (G15) row be claimed?** Default: **no** (409). A claim
    means "preparing". A prepared row is waiting for the patient, and whoever
    is at the counter hands it over.
11. **Does the UNCLAIMED filter include prepared rows?** Default: **yes**. It
    means strictly "no active claim".

## 11. Task list

- [ ] **T1 — V179, entity, repository.** AC-19.
  - `db/migration/V179__prescription_queue_claims.sql`, and its `changelog.xml`
    changeSet after V178 (liquibase-migration skill).
  - `model/pharmacy/PrescriptionQueueClaim.java` and
    `repository/pharmacy/PrescriptionQueueClaimRepository.java`.
  - `MigrationRegistrationTest`, `LiquibaseSchemaIT` and
    `EntitySchemaValidationIT` run green.
- [ ] **T2 — Claim service, audit types, messages.** AC-1, AC-3 to AC-7,
  AC-10, AC-11 (claim side), AC-15, AC-16 (service side).
  - `enums/QueueClaimReleaseReason.java` and `enums/QueueClaimFilter.java`.
  - `service/pharmacy/PrescriptionQueueClaimService.java`: claim, takeOver,
    release, releaseOnExit, activeClaimsFor, and expiry from the `Clock`.
  - The four `enums/AuditEventType.java` values, **plus** the four
    `PORTAL.ENUM.AUDIT_EVENT_TYPE.*` keys in `assets/i18n/{en,fr,es}.json`,
    in this same commit (G15 A5).
  - The four `workqueue.claim.*` keys in the four `messages*.properties`.
  - The properties in `application.properties`.
  - `PrescriptionQueueClaimServiceTest`.
- [ ] **T3 — Endpoints and settings.** AC-14, AC-16.
  - `DispenseController`: the three POSTs; settings built with the builder.
  - The `DispenseSettingsDTO` fields; `WorkQueueClaimDTO`.
  - The `DispenseControllerTest` reflection role test; the identical-404 unit
    test.
- [ ] **T4 — Work-queue decoration and filters.** AC-2, AC-13.
  - The MINE and UNCLAIMED page queries in `PrescriptionRepository`, with
    `countQuery`.
  - `WorkQueuePrescriptionDTO.claim`;
    `DispenseServiceImpl.getWorkQueue(pageable, filter)`; the `DispenseService`
    signature; the controller's `@RequestParam`.
  - An H2 `@DataJpaTest` for the queries; the decoration in
    `DispenseServiceImplTest`.
- [ ] **T5 — Exit-path releases.** AC-8, AC-9, AC-10 (ready side), AC-11,
  AC-12.
  - `DispenseServiceImpl` (one-step, ready).
  - `StockOutRoutingServiceImpl` (partner, print, back order).
  - `PrescriptionClarificationService`.
  - `PrescriptionSmsDispatchServiceImpl` (success only).
  - `PrescriptionServiceImpl` (`:900`).
  - **Update, in the same commit, the strict-stub tests this breaks**:
    `DispenseServiceImplTest`, `StockOutRoutingServiceImplTest`,
    `PrescriptionClarificationServiceTest`,
    `PrescriptionSmsDispatchServiceImplTest`, `PrescriptionServiceImplTest`,
    and the `@Import` list of `PreparedFillConcurrencyPostgresIT`, which
    constructs `DispenseServiceImpl` and `StockOutRoutingServiceImpl`.
- [ ] **T6 — Postgres concurrency IT.** AC-3, AC-6, AC-8, AC-10, AC-11,
  AC-19, rule 3.
  - New `QueueClaimConcurrencyPostgresIT`, covering:
    - claim vs claim;
    - claim vs ready, both orders;
    - claim vs withdrawal, both orders;
    - take-over vs release;
    - a claim, then a one-step dispense by another user;
    - take-over persisted in place;
    - a direct duplicate insert.
- [ ] **T7 — Portal service and dispensing UI.** AC-17, AC-18.
  - `services/pharmacy.service.ts` and `pharmacy/dispensing.{ts,html,scss,spec.ts}`.
  - The `PHARMACY.CLAIM.*` keys in `assets/i18n/{en,fr,es}.json`.
  - `npm run i18n:parity`, `i18n:referenced` and `i18n:translated`; lint;
    Karma.
- [ ] **T8 — Runbook.** AC-16.
  - `docs/pharmacy-runbook.md`: the two env vars, what the TTL means, and how
    to read the four audit events.
- [ ] **T9 — Bookkeeping**, handed to the coordinator in the PR body.
  - `tasklist.md:4612`: G13 is closed by this PR.
  - The section 10 "Out of scope" items, recorded as debt.
