# User Stories — Patient Portal: Health Summary & Medical History

## Feature 1: Health Summary Dashboard

### US-1.1: View Health Summary as Card Dashboard
**As a** patient  
**I want to** see my health summary as a card-based dashboard  
**So that** I can quickly view my key health information at a glance  

**Acceptance Criteria:**
- Dashboard displays cards: Medications, Test Results, Current Health Issues, Immunizations, Allergies, Quick Links, Share My Record
- Each card shows a preview (top 3–4 items) with a "Go to [Section]" link
- Health Goal card shows placeholder with "Add goal" option
- Layout is responsive (2–3 column grid)
- All data loads from existing HealthSummaryDTO

### US-1.2: Navigate from Dashboard Cards
**As a** patient  
**I want to** click "Go to Medications" or "Go to Test Results" links on dashboard cards  
**So that** I can navigate directly to the detailed section  

**Acceptance Criteria:**
- "Go to Medications" links to `/my-medications`
- "Go to Test Results" links to `/my-lab-results`
- "Go to Health Issues" links to `/my-medical-history`
- "Go to Sharing Hub" links to `/my-sharing`
- Quick Links: Visits → `/my-visits`, Upcoming Tests → `/my-appointments`

---

## Feature 2: Medical and Family History

### US-2.1: View Medical History
**As a** patient  
**I want to** see my medical history (diagnoses) with dates  
**So that** I can track my health conditions over time  

**Acceptance Criteria:**
- Table displays Diagnosis and Date columns
- Data fetched from `GET /api/me/patient/medical-history`
- Empty state shows appropriate message
- Patient can only see their own records (resolved from JWT)

### US-2.2: View Surgical History
**As a** patient  
**I want to** see my surgical history  
**So that** I know what procedures I've had  

**Acceptance Criteria:**
- List/table with procedure name and date
- Empty state: "You have no surgical history on file"
- Data fetched from `GET /api/me/patient/surgical-history`

### US-2.3: View Family History
**As a** patient  
**I want to** see my family medical history  
**So that** I can understand hereditary health risks  

**Acceptance Criteria:**
- List showing relationship, condition, age at onset
- Empty state: "You have no family history on file"
- Data fetched from `GET /api/me/patient/family-history`

### US-2.4: View Social History
**As a** patient  
**I want to** see my social history (tobacco, alcohol use)  
**So that** I can review what's on file  

**Acceptance Criteria:**
- Cards: Smoking Tobacco (use status, type, quit date), Smokeless Tobacco (use status), Alcohol (use status, frequency, drinks/week)
- Data fetched from `GET /api/me/patient/social-history`
- Empty state when no record exists

### US-2.5: Personal Notes
**As a** patient  
**I want to** add personal notes for each history section  
**So that** I can keep my own records  

**Acceptance Criteria:**
- Each section (medical, surgical, family, social) has a "Personal notes" area
- Disclaimer: "Notes entered here will not be viewable by your doctor"
- "+ Add" button to add/edit notes
- Notes stored in browser localStorage (device-local only)
