# MediHub Patient — Android App

Native Android patient portal built with **Kotlin + Jetpack Compose**.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material3 |
| DI | Hilt 2.52 |
| Networking | Retrofit 2.11 + OkHttp 4.12 + Moshi 1.15 |
| Navigation | Navigation Compose 2.8 |
| Auth Storage | EncryptedSharedPreferences (AES256-GCM) |
| Biometrics | BiometricPrompt (AndroidX Biometric) |
| Image Loading | Coil 2.7 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |

## Project Structure

```
app/src/main/java/com/bitnesttechs/hms/patient/
├── MediHubApplication.kt          # @HiltAndroidApp entry point
├── MainActivity.kt                # Single activity host
├── core/
│   ├── auth/
│   │   ├── AuthInterceptor.kt     # OkHttp Bearer + 401 refresh
│   │   ├── TokenStorage.kt        # EncryptedSharedPreferences wrapper
│   │   └── AuthRepository.kt      # Login / logout / current user
│   ├── models/
│   │   ├── PatientModels.kt
│   │   ├── AppointmentModels.kt
│   │   ├── LabModels.kt
│   │   ├── MedicationModels.kt
│   │   ├── BillingModels.kt
│   │   ├── VitalsModels.kt
│   │   └── ClinicalModels.kt
│   └── network/
│       ├── ApiService.kt          # Retrofit interface (50+ endpoints)
│       ├── ApiResponse.kt         # ApiResponse<T>, PageDto<T>
│       └── NetworkModule.kt       # Hilt @Module
├── navigation/
│   ├── AppNavigation.kt           # Root NavHost, login guard
│   └── MainScreen.kt              # 4-tab NavigationBar + sub-routes
├── ui/theme/
│   ├── Color.kt
│   ├── Type.kt
│   └── Theme.kt                   # MediHubTheme (light + dark)
└── features/
    ├── login/         # LoginScreen + LoginViewModel (BiometricPrompt)
    ├── dashboard/     # DashboardScreen + DashboardViewModel
    ├── appointments/  # AppointmentsScreen + AppointmentsViewModel
    ├── labresults/    # LabResultsScreen + LabResultsViewModel
    ├── medications/   # MedicationsScreen + MedicationsViewModel
    ├── billing/       # BillingScreen + BillingViewModel
    ├── messages/      # MessagesScreen + MessageThreadScreen + ViewModels
    ├── vitals/        # VitalsScreen + VitalsViewModel (record bottom sheet)
    ├── careteam/      # CareTeamScreen + CareTeamViewModel
    ├── visits/        # VisitHistoryScreen + VisitHistoryViewModel
    ├── profile/       # ProfileScreen + ProfileViewModel (logout)
    ├── notifications/ # NotificationsScreen + NotificationsViewModel
    ├── documents/     # DocumentsScreen + DocumentsViewModel
    ├── healthrecords/ # HealthRecordsScreen + HealthRecordsViewModel
    └── sharingprivacy/# SharingPrivacyScreen + SharingPrivacyViewModel
```

## API Configuration

API base URLs are injected at build time via `BuildConfig.API_BASE_URL`:

- **Debug** (emulator): `http://10.0.2.2:8081/api`
- **Release**: `https://hms-production.up.railway.app/api`

## Running Locally

### Prerequisites
- Android Studio Ladybug or later
- JDK 17+
- Android emulator (API 26+) or physical device

### Steps

```bash
cd patient-android-app

# Open in Android Studio and run the 'app' configuration,
# or use the Gradle wrapper:
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

> **Note:** Ensure the backend server is running on port 8081 when testing with the emulator.
> The emulator uses `10.0.2.2` to reach the host machine's `localhost`.

## Authentication Flow

1. `TokenStorage` checks for a saved access token on launch
2. If found → navigate directly to `MainScreen`
3. If not found → `LoginScreen` (username/password or biometric)
4. `AuthInterceptor` attaches `Bearer <token>` to every request
5. On `401` → auto-refresh using stored refresh token
6. On refresh failure → clear tokens and redirect to login

## Features

| Screen | Description |
|--------|-------------|
| Dashboard | Health summary, quick links, upcoming appointments, recent labs |
| Appointments | Upcoming appointment list |
| Lab Results | Results with abnormal/critical color indicators |
| Medications | Active medications + prescriptions (tabbed) |
| Billing | Outstanding balance banner + invoice list |
| Messages | Chat thread list + full thread view |
| Vitals | Grid of latest readings + history + record new vitals sheet |
| Care Team | Primary physician card + members list with dial action |
| Visit History | Encounter list + discharge summary bottom sheet |
| Profile | Personal info, medical info, insurance, emergency contact, logout |
| Notifications | List with mark-read / mark-all-read |
| Documents | Patient document list with external viewer |
| Health Records | Immunizations / Treatment Plans / Referrals (tabbed) |
| Sharing & Privacy | Consent management + access log |
