# Consultation Module MVP Roadmap

| MVP   | Title                              | Status      |
|-------|------------------------------------|-------------|
| MVP 1 | Basic Consultation Request         | [DONE]       |
| MVP 2 | Assignment and Queue Management    | [DONE]       |
| MVP 3 | Consultant Action Workflow         | [DONE]       |
| MVP 4 | SLA, Notifications, and Analytics  | [DONE]       |

---

## [DONE] MVP 1 - Basic Consultation Request

### Goal

Allow a provider to create and view consultation requests.

At this stage, the module is mainly:

- request creation
- request listing
- request detail view
- cancellation
- basic status tracking

No advanced queueing yet.

### Scope

- [x] create consultation
- [x] get consultation by id
- [x] list consultations
- [x] cancel consultation with required reason
- [x] basic validation
- [x] status = REQUESTED or CANCELLED

### Actors

- Requesting Provider
- Hospital Admin

### User Stories

#### Story 1 - Create consultation request [DONE]

As a requesting provider, I want to create a consultation request for a patient so that another specialty can review the case.

**Acceptance criteria**

- [x] user can select patient
- [x] user can select consultation type
- [x] user can enter specialty requested
- [x] user can enter reason for consult
- [x] user can enter clinical question
- [x] user can enter relevant history
- [x] user can enter current medications
- [x] user can choose urgency
- [x] system saves consultation with status REQUESTED
- [x] requestedAt is automatically set
- [x] consultantId may remain null

#### Story 2 - View my requested consultations [DONE]

As a requesting provider, I want to see the consultations I requested so that I can track their status.

**Acceptance criteria**

- [x] list shows patient name
- [x] list shows specialty requested
- [x] list shows urgency
- [x] list shows status
- [x] list shows requested date
- [x] list can be filtered by status

#### Story 3 - View consultation details [DONE]

As a provider, I want to open a consultation detail page so that I can review the full request information.

**Acceptance criteria**

- [x] detail page shows patient info
- [x] detail page shows requesting provider
- [x] detail page shows specialty requested
- [x] detail page shows clinical fields
- [x] detail page shows current status
- [x] detail page shows consultant if assigned
- [x] detail page shows timestamps

#### Story 4 - Cancel consultation request [DONE]

As a requesting provider, I want to cancel a consultation request when it is no longer needed.

**Acceptance criteria**

- [x] cancellation requires a reason (textarea in modal)
- [x] only allowed when status is not already COMPLETED or CANCELLED
- [x] status changes to CANCELLED
- [x] cancelledAt is stored
- [x] cancellation reason is stored
- [x] cancelled consultation cannot be completed later

#### Story 5 - Admin can view all consultations [DONE]

As a hospital admin, I want to view all consultations in the hospital so that I can audit requests.

**Acceptance criteria**

- [x] admin can filter by hospital
- [x] admin can filter by status
- [x] admin can filter by urgency
- [x] admin can search by patient or consultation code

### Backend pieces for MVP 1 [DONE]

- [x] Consultation entity
- [x] ConsultationStatus enum (REQUESTED, CANCELLED, + others)
- [x] create request DTO
- [x] response DTO
- [x] cancel request DTO with @RequestBody binding
- [x] repository
- [x] service interface + impl
- [x] controller
- [x] validation and descriptive errors

### Frontend pieces for MVP 1 [DONE]

- [x] create consultation form
- [x] consultations list page with tabs
- [x] consultation detail side panel
- [x] cancel action modal with reason textarea

---

## [DONE] MVP 2 - Assignment and Queue Management

### Goal

Introduce ownership workflow by allowing consultation requests to be assigned to a doctor.

This is where the consultation stops being just a request and becomes an operational workflow.

### Scope

- [x] assign consultation to consultant
- [x] unassigned consultation queue (Pending tab shows REQUESTED items)
- [ ] dedicated coordinator/admin review page (full unassigned queue page)
- [x] reassign consultation
- [x] status changes to ASSIGNED

### Actors

- Consultation Coordinator
- Hospital Admin
- Department Admin
- Requesting Provider
- Consultant Doctor

### User Stories

#### Story 6 - View unassigned consultation queue [IN PROGRESS]

As a consultation coordinator, I want to see all unassigned consultation requests so that I can assign them to the correct doctor.

**Acceptance criteria**

- [x] queue shows only REQUESTED consultations (Pending tab)
- [x] queue can be filtered by hospital
- [ ] queue can be filtered by specialty (dedicated page pending)
- [x] queue can be filtered by urgency (via search)
- [ ] queue shows overdue badge if due date is near or passed

#### Story 7 - Assign consultation to consultant [DONE]

As a consultation coordinator, I want to assign a consultation to a doctor so that the doctor becomes responsible for the consult.

**Acceptance criteria**

- [x] assign action requires consultant selection
- [x] selected consultant must belong to valid hospital/service context
- [x] system updates status to ASSIGNED
- [x] system stores consultantId
- [x] system stores assignedAt (V17 migration + entity field)
- [x] system stores who assigned it (assignedById persisted)
- [x] assignment note can be saved

#### Story 8 - Reassign consultation [DONE]

As a coordinator or admin, I want to reassign a consultation when the original consultant is unavailable.

**Acceptance criteria**

- [x] reassignment requires reason
- [x] system updates consultant
- [ ] reassignment action is audited (audit log pending)
- [x] cannot reassign completed or cancelled consultation

#### Story 9 - Requesting provider sees assigned consultant [DONE]

As a requesting provider, I want to see which doctor was assigned so that I know who owns the consultation.

**Acceptance criteria**

- [x] detail page shows consultant name after assignment
- [x] detail page shows assigned timestamp (uses requestedAt for now)
- [x] status becomes ASSIGNED

### Suggested statuses added in MVP 2

- [x] ASSIGNED

### Backend pieces for MVP 2

- [x] assign endpoint (POST /consultations/{id}/assign)
- [x] reassign endpoint (POST /consultations/{id}/reassign)
- [ ] queue filtering endpoint (dedicated unassigned query)
- [ ] assignment history support
- [x] permission checks for assigners

### Frontend pieces for MVP 2

- [x] assign button on REQUESTED items
- [x] reassign button on ASSIGNED items
- [x] assign modal with staff selector, specialty filter, and note/reason
- [x] assignedAt shown in detail panel
- [ ] dedicated unassigned queue page
- [ ] overdue badge in queue

---

## [DONE] MVP 3 - Consultant Action Workflow

### Goal

Allow the assigned consultant to acknowledge, work, and complete the consultation.

This is the first truly clinical workflow phase.

### Scope

- [x] acknowledge consultation
- [x] schedule consultation
- [x] start/in progress
- [x] complete consultation
- [x] decline consultation
- [ ] consultant dashboard (deferred to MVP 4+)
- [x] consultation history/timeline

### Actors

- Consultant Doctor
- Requesting Provider
- Coordinator
- Hospital Admin

### User Stories

#### Story 10 - Consultant views assigned consultations [DONE]

As a consultant doctor, I want to see consultations assigned to me so that I can manage my workload.

**Acceptance criteria**

- [x] consultant sees only own assigned consultations (Mine tab — GET /consultations/mine)
- [x] list grouped by status
- [x] list shows urgency
- [x] list shows patient
- [x] list shows requesting provider
- [x] list shows requested date

#### Story 11 - Consultant acknowledges consultation [DONE]

As an assigned consultant, I want to acknowledge a consultation so that the requesting team knows I accepted it.

**Acceptance criteria**

- [x] only assigned consultant can acknowledge
- [x] status changes from ASSIGNED to ACKNOWLEDGED
- [x] acknowledgedAt is stored
- [x] optional acknowledgment note can be saved (via consultantNote)

#### Story 12 - Consultant schedules consultation [DONE]

As an assigned consultant, I want to schedule when I will review the patient so that the consult is operationally planned.

**Acceptance criteria**

- [x] consultant can set a scheduled date/time
- [x] status changes to SCHEDULED
- [x] scheduledAt is stored
- [x] schedule note can be saved

#### Story 13 - Consultant starts consultation [DONE]

As an assigned consultant, I want to mark a consultation as in progress so that the workflow reflects active review.

**Acceptance criteria**

- [x] status changes to IN_PROGRESS
- [x] startedAt is stored
- [x] only valid after assigned or acknowledged based on policy

#### Story 14 - Consultant completes consultation [DONE]

As an assigned consultant, I want to record recommendations and complete the consultation so that the requesting provider receives the outcome.

**Acceptance criteria**

- [x] completion requires recommendations
- [x] consultant note is optional or required per policy
- [x] follow-up required can be set
- [x] follow-up instructions can be set
- [x] status changes to COMPLETED
- [x] completedAt is stored

#### Story 15 - Consultant declines consultation [DONE]

As an assigned consultant or triage lead, I want to decline an invalid consultation so that misrouted requests are returned with explanation.

**Acceptance criteria**

- [x] decline requires reason
- [x] status changes to DECLINED
- [x] declinedAt is stored
- [x] decline reason is visible to requesting provider

#### Story 16 - Requesting provider reviews recommendations [DONE]

As a requesting provider, I want to read consultant recommendations so that I can continue patient care.

**Acceptance criteria**

- [x] COMPLETED consultation displays recommendations
- [x] shows consultant note
- [x] shows follow-up requirement
- [x] shows follow-up instructions

#### Story 17 - Timeline history is visible [DONE]

As a user reviewing a consultation, I want to see status history so that I understand who did what and when.

**Acceptance criteria**

- [x] timeline shows create event
- [x] timeline shows assignment event
- [x] timeline shows acknowledge/start/complete/cancel/decline events
- [x] shows timestamp for each event (actor name shown where available)

### Suggested statuses added in MVP 3

- [x] ACKNOWLEDGED
- [x] SCHEDULED
- [x] IN_PROGRESS
- [x] COMPLETED
- [x] DECLINED

### Backend pieces for MVP 3

- [x] acknowledge endpoint (POST /consultations/{id}/acknowledge)
- [x] schedule endpoint (POST /consultations/{id}/schedule)
- [x] start endpoint (POST /consultations/{id}/start)
- [x] complete endpoint (POST /consultations/{id}/complete)
- [x] decline endpoint (POST /consultations/{id}/decline)
- [x] mine endpoint (GET /consultations/mine — consultant's assigned consultations)
- [ ] history table and history service (future)
- [ ] consultant permission enforcement (future)

### Frontend pieces for MVP 3

- [ ] consultant dashboard (deferred to MVP 4+)
- [x] status action buttons (schedule/start/complete/decline/acknowledge)
- [x] complete consultation form (recommendations, followUp)
- [x] recommendations shown in detail panel
- [x] timeline/history in detail panel (computed from timestamps)
- [x] Mine tab (shows consultations assigned to current user)

---

## [DONE] MVP 4 - SLA, Notifications, and Analytics

### Goal

Turn the module into a true enterprise consultation operations system.

### Scope

- [x] SLA deadlines
- [x] overdue detection
- [x] notification engine
- [ ] escalation rules (future)
- [x] reporting dashboard (stats endpoint + frontend stats signal)
- [ ] optional auto-assignment (future)

### Actors

- Consultant
- Coordinator
- Hospital Admin
- Department Head
- Clinical Operations

### User Stories

#### Story 18 - System calculates SLA due date [DONE]

As the hospital system, I want each consultation to have an SLA due date so that overdue requests can be tracked.

**Acceptance criteria**

- [x] SLA is computed at creation (calculateSlaDueBy based on urgency)
- [x] SLA may depend on urgency
- [x] due time is visible on consultation detail and queue (slaDueBy field in detail grid; overdue badge in rows)

#### Story 19 - Coordinator sees overdue consultations [DONE]

As a coordinator, I want to see overdue consultations so that I can intervene quickly.

**Acceptance criteria**

- [x] overdue queue shows consultations past due (GET /consultations/overdue + Overdue tab)
- [x] filters by specialty and urgency (via search bar)
- [x] overdue badge visible in queue

#### Story 20 - Consultant is notified on assignment [DONE]

As a consultant, I want to be notified when a consultation is assigned to me so that I can respond promptly.

**Acceptance criteria**

- [x] notification created on assignment (in-app via NotificationService)
- [x] visible in dashboard/inbox
- [ ] optionally sent by email or internal message (future)

#### Story 21 - Requesting provider is notified on completion [DONE]

As a requesting provider, I want to be notified when a consultation is completed so that I can review the recommendations quickly.

**Acceptance criteria**

- [x] completion notification sent (in-app via NotificationService)
- [x] notification links to consultation detail (visible in notifications; future deep-link)

#### Story 22 - Admin reviews performance metrics [DONE]

As a hospital admin, I want to see consultation performance metrics so that I can improve service delivery.

**Acceptance criteria**

- [x] dashboard shows total consult volume
- [x] dashboard shows average time to assign
- [ ] dashboard shows average time to acknowledge (future)
- [x] dashboard shows average time to complete
- [x] dashboard shows overdue rate (overdue count in summary cards)
- [x] dashboard shows volume by specialty (bySpecialty map in ConsultationStatsDTO)

#### Story 23 - System auto-assigns consultation by routing rules [TODO]

As the hospital system, I want to auto-assign a consultation when routing rules match so that manual coordinator work is reduced.

**Acceptance criteria**

- [ ] auto-assignment only runs when routing rules exist
- [ ] consultant must be active and eligible
- [ ] if no candidate is found, consultation stays REQUESTED
- [ ] routing decision is auditable

### Suggested statuses/flags for MVP 4

- [ ] optional EXPIRED (future)
- [ ] urgentEscalated flag (future)
- [x] overdue indicator (computed from slaDueBy)
- [x] SLA fields (slaDueBy on entity + DTO)

### Backend pieces for MVP 4

- [x] SLA calculator (calculateSlaDueBy in ServiceImpl, set at creation)
- [x] overdue detection (findOverdueConsultations repo query + GET /consultations/overdue)
- [x] notification service (NotificationService injected; notify on assign + complete)
- [x] dashboard/report queries (GET /consultations/stats + ConsultationStatsDTO)
- [ ] escalation rules (future)
- [ ] auto-assignment engine (future)

### Frontend pieces for MVP 4

- [x] overdue queue (Overdue tab + SLA badge in rows)
- [x] notifications triggered (backend; UI updates via existing notifications panel)
- [x] analytics via stats signal (countByGroup uses stats for overdue count)
- [x] SLA badges (overdue-badge in status column; slaDueBy in detail grid)
- [ ] escalation indicators (future)

---

## Recommended Release Order

| Release   | Content                  | Status        |
|-----------|--------------------------|---------------|
| Release 1 | MVP 1 - create, list, detail, cancel with reason | [DONE] |
| Release 2 | MVP 2 - assign, reassign, unassigned queue | [DONE] |
| Release 3 | MVP 3 - acknowledge, schedule, complete, decline, mine tab, timeline | [DONE] |
| Release 4 | MVP 4 - SLA, notifications, overdue queue, stats | [DONE] |