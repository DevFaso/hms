# Native Mobile Apps — Implementation Plan
> **Goal**: Build two pure native patient apps (iOS SwiftUI + Android Kotlin/Compose)  
> connecting to the existing Spring Boot backend (`/me/patient/*` endpoints).  
> The Capacitor hybrid has been decommissioned to `platform/decommissioned/patient-mobile-app/`.

---

## Repository Structure

```
hms/
├── patient-ios-app/                                ← Native SwiftUI (Xcode project)
├── patient-android-app/                            ← Native Kotlin/Compose (Android Studio project)
└── platform/decommissioned/patient-mobile-app/     ← DECOMMISSIONED — Capacitor hybrid
```

---

## Backend API Base

| Environment | Base URL |
|---|---|
| Local dev | `http://localhost:8081/api` |
| Production | Railway deployment URL |

All endpoints require `Authorization: Bearer <accessToken>` header.  
Token refresh: `POST /auth/token/refresh`

---

## Screens Inventory

| # | Screen | Route (hybrid ref) | API Endpoint | iOS | Android |
|---|---|---|---|---|---|
| 1 | **Login** | `/login` | `POST /auth/login` | ✅ Done | ⬜ Not started |
| 2 | **Dashboard** | `/dashboard` | `GET /me/patient/health-summary` | ✅ Done | ⬜ Not started |
| 3 | **Appointments** | `/appointments` | `GET /me/patient/appointments` | ✅ Done | ⬜ Not started |
| 4 | **Schedule Appointment** | `/appointments/schedule` | `POST /me/patient/appointments` | ⬜ Not started | ⬜ Not started |
| 5 | **Lab Results** | `/lab-results` | `GET /me/patient/lab-results` | ✅ Done | ⬜ Not started |
| 6 | **Medications** | `/medications` | `GET /me/patient/medications` | ✅ Done | ⬜ Not started |
| 7 | **Prescriptions** | `/medications` (tab) | `GET /me/patient/prescriptions` | ✅ Done | ⬜ Not started |
| 8 | **Billing / Invoices** | `/billing` | `GET /me/patient/billing/invoices` | ✅ Done | ⬜ Not started |
| 9 | **Payment Options** | `/billing/payment` | billing endpoints | ⬜ Not started | ⬜ Not started |
| 10 | **Messages** | `/messages` | chat service | ✅ Done | ⬜ Not started |
| 11 | **Message Thread** | `/messages/:recipientId` | chat service | ✅ Done | ⬜ Not started |
| 12 | **Compose Message** | `/messages/new` | chat service | ✅ Done | ⬜ Not started |
| 13 | **Profile** | `/profile` | `GET /me/patient/profile` | ✅ Done | ⬜ Not started |
| 14 | **Notifications** | `/notifications` | notification service | ✅ Done | ⬜ Not started |
| 15 | **Care Team** | `/care-team` | `GET /me/patient/care-team` | ✅ Done | ⬜ Not started |
| 16 | **Visit History** | `/visits` | `GET /me/patient/encounters` | ✅ Done | ⬜ Not started |
| 17 | **After Visit Summary** | `/visits/:id/summary` | `GET /me/patient/after-visit-summaries` | ✅ Done | ⬜ Not started |
| 18 | **Documents** | `/documents` | `GET /me/patient/documents` | ✅ Done | ⬜ Not started |
| 19 | **Consent Forms** | `/consents` | `GET /me/patient/consents` | ✅ Done | ⬜ Not started |
| 20 | **Vitals** | `/vitals` | `GET /me/patient/vitals` | ✅ Done | ⬜ Not started |
| 21 | **Health Records** | `/health-records` | `GET /me/patient/health-summary` | ✅ Done | ⬜ Not started |
| 22 | **Sharing & Privacy** | `/sharing-privacy` | `GET /me/patient/access-log` | ✅ Done | ⬜ Not started |

**Status legend**: ⬜ Not started · 🔄 In progress · ✅ Done · ❌ Blocked

---

## Shared Features (both platforms)

| Feature | iOS | Android |
|---|---|---|
| JWT auth + auto token refresh | ✅ | ⬜ |
| Secure token storage (Keychain / EncryptedSharedPrefs) | ✅ | ⬜ |
| Biometric login (FaceID/TouchID / Fingerprint) | ✅ | ⬜ |
| Bottom tab navigation | ✅ | ⬜ |
| Push notifications | ⬜ | ⬜ |
| Offline / error states | ⬜ | ⬜ |
| Deep links | ⬜ | ⬜ |

---

## iOS — `patient-ios-app/`

### Tech Stack
- **Language**: Swift 5.9+
- **UI**: SwiftUI
- **Networking**: URLSession + async/await
- **Auth storage**: Keychain (via KeychainAccess or raw Security framework)
- **State management**: `@StateObject` / `@ObservedObject` + `@EnvironmentObject`
- **Min deployment target**: iOS 15.0
- **Xcode**: 16.4

### Project Structure (to be created)
```
patient-ios-app/
├── MediHubPatient.xcodeproj
└── MediHubPatient/
    ├── App/
    │   ├── MediHubPatientApp.swift      ← @main entry point
    │   └── ContentView.swift            ← root router (auth gate)
    ├── Core/
    │   ├── Network/
    │   │   ├── APIClient.swift          ← URLSession wrapper, Bearer token injection
    │   │   ├── APIEndpoints.swift       ← all /me/patient/* endpoint definitions
    │   │   └── TokenRefreshInterceptor.swift
    │   ├── Auth/
    │   │   ├── AuthManager.swift        ← login/logout/refresh, publishes auth state
    │   │   └── KeychainHelper.swift     ← secure token storage
    │   └── Models/                      ← Codable structs matching backend DTOs
    │       ├── AuthModels.swift
    │       ├── PatientModels.swift
    │       ├── AppointmentModels.swift
    │       ├── LabModels.swift
    │       ├── MedicationModels.swift
    │       ├── BillingModels.swift
    │       ├── VitalsModels.swift
    │       └── ...
    ├── Features/
    │   ├── Login/
    │   │   ├── LoginView.swift
    │   │   └── LoginViewModel.swift
    │   ├── Dashboard/
    │   │   ├── DashboardView.swift
    │   │   └── DashboardViewModel.swift
    │   ├── Appointments/
    │   ├── LabResults/
    │   ├── Medications/
    │   ├── Billing/
    │   ├── Messages/
    │   ├── Profile/
    │   ├── Notifications/
    │   ├── CareTeam/
    │   ├── Visits/
    │   ├── Documents/
    │   ├── Vitals/
    │   ├── HealthRecords/
    │   └── SharingPrivacy/
    └── Resources/
        ├── Assets.xcassets
        └── Info.plist
```

### Build Order (iOS)
1. ⬜ Scaffold Xcode project (`patient-ios-app/`)
2. ⬜ `APIClient.swift` — URLSession + Bearer token + refresh logic
3. ⬜ `AuthManager.swift` + `KeychainHelper.swift`
4. ⬜ All `Models/` (Codable structs from backend DTOs)
5. ⬜ `LoginView` + `LoginViewModel`
6. ⬜ `ContentView` — auth gate (Login vs MainTabView)
7. ⬜ `MainTabView` — bottom tab bar (Dashboard, Appointments, Messages, Profile)
8. ⬜ `DashboardView` + `DashboardViewModel`
9. ⬜ `AppointmentsView`
10. ⬜ `LabResultsView`
11. ⬜ `MedicationsView`
12. ⬜ `BillingView`
13. ⬜ `MessagesView` + `MessageThreadView`
14. ⬜ `VitalsView`
15. ⬜ `CareTeamView`
16. ⬜ `VisitHistoryView` + `AfterVisitSummaryView`
17. ⬜ `ProfileView`
18. ⬜ `DocumentsView`
19. ⬜ `NotificationsView`
20. ⬜ `HealthRecordsView`
21. ⬜ `SharingPrivacyView`
22. ⬜ `ConsentFormsView`
23. ⬜ Biometric login (FaceID/TouchID)
24. ⬜ Push notifications (APNs)

---

## Android — `patient-android-app/`

### Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Networking**: Retrofit 2 + OkHttp + Gson/Moshi
- **Auth storage**: EncryptedSharedPreferences
- **State management**: ViewModel + StateFlow + Hilt (DI)
- **Navigation**: Compose Navigation
- **Min SDK**: API 26 (Android 8.0)
- **Target SDK**: API 35

### Project Structure (to be created)
```
patient-android-app/
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/com/bitnesttechs/hms/patient/
        ├── MediHubApplication.kt        ← Application class (Hilt)
        ├── MainActivity.kt              ← single activity, Compose host
        ├── core/
        │   ├── network/
        │   │   ├── ApiClient.kt         ← Retrofit instance
        │   │   ├── ApiService.kt        ← all /me/patient/* @GET/@POST definitions
        │   │   └── AuthInterceptor.kt   ← Bearer token injection + refresh
        │   ├── auth/
        │   │   ├── AuthRepository.kt
        │   │   └── TokenStorage.kt      ← EncryptedSharedPreferences
        │   └── models/                  ← data classes matching backend DTOs
        ├── features/
        │   ├── login/
        │   ├── dashboard/
        │   ├── appointments/
        │   ├── labresults/
        │   ├── medications/
        │   ├── billing/
        │   ├── messages/
        │   ├── profile/
        │   ├── notifications/
        │   ├── careteam/
        │   ├── visits/
        │   ├── documents/
        │   ├── vitals/
        │   ├── healthrecords/
        │   └── sharingprivacy/
        └── navigation/
            └── AppNavigation.kt         ← NavHost with all routes
```

### Build Order (Android)
1. ⬜ Scaffold Android Studio project (`patient-android-app/`)
2. ⬜ `ApiClient.kt` + `ApiService.kt` + `AuthInterceptor.kt`
3. ⬜ `AuthRepository.kt` + `TokenStorage.kt`
4. ⬜ All `models/` (data classes from backend DTOs)
5. ⬜ `LoginScreen` + `LoginViewModel`
6. ⬜ `AppNavigation.kt` — auth gate routing
7. ⬜ Bottom nav bar + `MainScreen`
8. ⬜ `DashboardScreen`
9. ⬜ `AppointmentsScreen`
10. ⬜ `LabResultsScreen`
11. ⬜ `MedicationsScreen`
12. ⬜ `BillingScreen`
13. ⬜ `MessagesScreen` + `MessageThreadScreen`
14. ⬜ `VitalsScreen`
15. ⬜ `CareTeamScreen`
16. ⬜ `VisitHistoryScreen`
17. ⬜ `ProfileScreen`
18. ⬜ `DocumentsScreen`
19. ⬜ `NotificationsScreen`
20. ⬜ `HealthRecordsScreen`
21. ⬜ `SharingPrivacyScreen`
22. ⬜ `ConsentFormsScreen`
23. ⬜ Biometric login (BiometricPrompt API)
24. ⬜ Push notifications (FCM)

---

## Current Status

### iOS (`patient-ios-app/`)
> ✅ **Builds and runs** on iOS Simulator (iPhone 16 Pro, iOS 18.6). 28 SwiftUI source files, pure system frameworks.

### Android (`patient-android-app/`)
> ✅ **Scaffolded.** Kotlin/Compose project with 61 files committed.

### Hybrid (`patient-mobile-app/`) — ✅ DECOMMISSIONED
> Moved to `platform/decommissioned/patient-mobile-app/` (June 2025).  
> All 21 screens preserved as reference for UI logic, API calls, and data shapes.

---

## Status
- ✅ iOS app builds & runs on Simulator
- ✅ Android app scaffolded
- ✅ Hybrid app decommissioned
