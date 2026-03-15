This is VERY important because nurse workflow is completely different from physician workflow.

Doctors = decision + diagnosis + orders
Nurses = patient flow + bedside execution + safety monitoring

So the UI must reflect that.

🏥 Nurse Dashboard (Epic-style Nurse Station Cockpit)
🎯 Screen goal

The nurse cockpit is the operational command center for unit workflow.

It must optimize:

patient movement

vitals monitoring

medication administration

task execution

triage and safety

communication with doctors

It is not a clinical decision dashboard.

It is a care delivery + coordination dashboard.

🧭 Core philosophy (Epic model)

Nurse dashboard must answer:

Which patients are under my care?

Who needs immediate attention?

Which tasks are due now?

Who is waiting?

Who is deteriorating?

What meds must be given now?

🧱 Layout structure

Use 4 main zones.

HEADER
CRITICAL STRIP
MAIN WORKBOARD (large)
RIGHT ACTION PANEL
1️⃣ Header zone
Must show

Nurse name

role badge (RN / LPN / TRIAGE / ICU)

assigned hospital

assigned department

shift time

on-duty status

unit selector (ward / ER / ICU)

global patient search

emergency action button

Emergency button

Example:

🔴 Code Blue
→ opens rapid response workflow

2️⃣ Critical alert strip

This is the MOST important area.

Cards must include

Critical vitals

Overdue medications

High acuity patients

Falls risk alerts

New admissions

STAT orders

Pending triage

Each card shows:

count

severity color

quick click navigation

Example:

Critical Vitals   3
Meds Overdue      5
STAT Orders       2
3️⃣ Main workboard (core nurse workflow)

This is the heart of the dashboard.

Title

Unit Workboard

Filter bar

My patients

My unit

High acuity

Waiting triage

Awaiting medication

Awaiting procedure

Discharge prep

Patient row card structure

Each patient card should show:

patient name

MRN

age / sex

room / bed

admission type

triage level (if ER)

acuity score

last vitals time

medication due indicator

fall risk icon

isolation badge

diet status

attending doctor

nurse assigned

Time indicators

waiting since

last nurse interaction

medication countdown

Row quick actions

Record vitals

Administer medication

Document care

Escalate to doctor

Request lab collection

Prepare discharge

Transfer patient

Start triage

4️⃣ Patient flow board (visual)

Epic uses strong flow visualization.

Columns example:

Waiting Triage

Triage Complete

Roomed

Waiting Doctor

In Treatment

Awaiting Results

Discharge Ready

Discharged

Each card draggable.

This lets nurses manage throughput.

5️⃣ Medication administration panel

Dedicated section.

Title

Medication Tasks

Rows show

patient

drug name

dose

route

scheduled time

overdue badge

PRN indicator

Actions

administer

hold

document refusal

escalate adverse reaction

This is a core nursing responsibility.

6️⃣ Vitals monitoring panel
Title

Vitals Due

Shows:

patient

last vitals timestamp

vitals schedule

abnormal trend indicator

Actions

capture vitals

view chart

escalate

7️⃣ Nursing task board

This replaces generic “todo list”.

Examples of tasks:

dressing change

IV check

catheter care

pain reassessment

mobility assistance

intake/output recording

wound documentation

Each task shows:

due time

priority

completion state

8️⃣ Admission & discharge panel
Sections

New admissions arriving

Patients pending discharge teaching

Transport requests

Bed cleaning status

This supports bed turnover efficiency.

9️⃣ Communication inbox

Nurse communication differs from doctor.

Include

physician orders notifications

pharmacy clarifications

lab specimen requests

consult coordination

handoff messages

Actions

acknowledge

respond

escalate

open patient chart

🔟 Right side compact panels

Recent patients

Shift handoff summary

Assigned team members

Unit capacity indicator

🧑‍⚕️ Sidebar navigation for nurse

Should include:

Dashboard

Patients

Scheduling (shift view)

Encounters (limited)

Admissions

Medication Administration

Referrals (view/create)

Laboratory (collection workflow)

Imaging prep

Notifications

Messages

Should NOT include:

Department admin

Staff scheduling admin

Billing admin

Physician prescribing workspace

🔐 RBAC nurse capabilities
Nurse CAN

capture vitals

administer meds

document care notes

manage triage

manage patient flow

view orders

request labs/imaging

prepare discharge

escalate alerts

Nurse CANNOT

prescribe medication

finalize diagnosis

complete physician encounter note

sign medical orders

change treatment plan authority

📡 Suggested backend APIs
GET /api/nurse/dashboard/summary
GET /api/nurse/workboard
GET /api/nurse/medications/due
GET /api/nurse/vitals/due
GET /api/nurse/tasks
GET /api/nurse/patient-flow
GET /api/nurse/admissions/pending
GET /api/nurse/inbox
🧩 Angular component structure
nurse-station-page
 ├── nurse-header
 ├── nurse-critical-strip
 ├── nurse-workboard
 ├── nurse-flow-board
 ├── nurse-medication-panel
 ├── nurse-vitals-panel
 ├── nurse-task-board
 ├── nurse-admission-panel
 ├── nurse-inbox
 ├── nurse-recent-patients
🧠 UX rule (very important)

Doctor dashboard = decision-centric
Nurse dashboard = execution-centric

So nurse UI must feel:

✔ operational
✔ time-sensitive
✔ task-driven
✔ flow-oriented

NOT menu-driven.