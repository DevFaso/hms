# MediHub Patient — Native iOS App

Pure SwiftUI native iOS app for the MediHub Hospital Management System patient portal.

## Tech Stack

- **Language**: Swift 5.9+
- **UI**: SwiftUI
- **Networking**: URLSession + async/await
- **Auth Storage**: iOS Keychain
- **Min iOS**: 15.0
- **Xcode**: 16.4+

## Project Structure

```
MediHubPatient/
├── App/
│   ├── MediHubPatientApp.swift    ← @main entry point
│   └── ContentView.swift          ← auth gate (Login vs MainTabView)
├── Core/
│   ├── Network/
│   │   ├── APIClient.swift        ← URLSession wrapper + auto token refresh
│   │   └── APIEndpoints.swift     ← all /me/patient/* endpoint constants
│   ├── Auth/
│   │   ├── AuthManager.swift      ← login/logout/refresh, @ObservableObject
│   │   └── KeychainHelper.swift   ← secure JWT token storage
│   └── Models/
│       ├── PatientModels.swift    ← UserDTO, PatientProfileDTO, HealthSummaryDTO
│       ├── AppointmentModels.swift
│       ├── LabModels.swift
│       ├── MedicationModels.swift
│       ├── BillingModels.swift
│       ├── VitalsModels.swift
│       └── ClinicalModels.swift   ← Encounters, CareTeam, Documents, Notifications, Chat...
├── Features/
│   ├── Login/                     ← LoginView + LoginViewModel (Face ID / Touch ID)
│   ├── Navigation/                ← MainTabView (Dashboard, Appointments, Messages, Profile)
│   ├── Dashboard/                 ← DashboardView + DashboardViewModel
│   ├── Appointments/              ← AppointmentsView
│   ├── LabResults/                ← LabResultsView
│   ├── Medications/               ← MedicationsView (Medications + Prescriptions tabs)
│   ├── Billing/                   ← BillingView (invoices + balance due)
│   ├── Messages/                  ← MessagesView, MessageThreadView, ComposeMessageView
│   ├── Vitals/                    ← VitalsView + RecordVitalSheet
│   ├── CareTeam/                  ← CareTeamView
│   ├── Visits/                    ← VisitHistoryView + AfterVisitSummaryView
│   ├── Profile/                   ← ProfileView (full patient profile + logout)
│   └── Misc/                      ← NotificationsView, DocumentsView, HealthRecordsView,
│                                       SharingPrivacyView
└── Resources/
    ├── Info.plist
    └── Assets.xcassets
```

## Setup

### 1. Create Xcode project

Open Xcode → **File → New → Project** → **iOS App**

| Field | Value |
|---|---|
| Product Name | MediHubPatient |
| Bundle Identifier | com.bitnesttechs.hms.patient.native |
| Interface | SwiftUI |
| Language | Swift |
| Min Deployment | iOS 15.0 |

Save into this folder: `patient-ios-app/`

### 2. Add all Swift files

Drag all files from this repository into the Xcode project navigator, making sure **"Copy items if needed"** is **unchecked** (files are already here).

### 3. Set API URL for local dev

In Xcode scheme → **Edit Scheme → Run → Arguments → Environment Variables**:

```
MEDIHUB_API_BASE_URL = http://localhost:8081/api
```

For production builds, update `AppEnvironment.baseURL` in `APIClient.swift`.

### 4. Build & Run

`Cmd + R` — runs on simulator or device.

## Screens

| Screen | Status |
|---|---|
| Login (username + Face ID / Touch ID) | ✅ Built |
| Dashboard | ✅ Built |
| Appointments | ✅ Built |
| Lab Results | ✅ Built |
| Medications + Prescriptions | ✅ Built |
| Billing / Invoices | ✅ Built |
| Messages + Thread + Compose | ✅ Built |
| Vitals + Record Vital | ✅ Built |
| Care Team | ✅ Built |
| Visit History + After Visit Summary | ✅ Built |
| Profile | ✅ Built |
| Notifications | ✅ Built |
| Documents | ✅ Built |
| Health Records | ✅ Built |
| Sharing & Privacy + Consents | ✅ Built |

## API

All endpoints connect to the Spring Boot backend.  
Base path: `/me/patient/*`  
Auth: `Authorization: Bearer <JWT>`  
Token refresh: automatic on 401 via `AuthManager.refreshTokens()`
