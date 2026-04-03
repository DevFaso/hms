# App Store Connect — MediHub Patient Metadata

> Copy-paste these values into App Store Connect.
> Last updated: April 3, 2026

---

## App Information

| Field | Value |
|-------|-------|
| **App Name** | MediHub Patient |
| **Subtitle** (30 chars) | Your Health, One Tap Away |
| **Bundle ID** | `com.bitnesttechs.hms.patient.native` |
| **SKU** | `medihub-patient-ios-001` |
| **Primary Language** | English (U.S.) |
| **Category** | Medical |
| **Secondary Category** | Health & Fitness |
| **Content Rights** | Does not contain third-party content |
| **Age Rating** | 17+ (Medical/Health info) |

---

## Version 1.0.0

### Promotional Text (170 chars)

```
Manage appointments, view lab results, message your care team, and track vitals — all from your phone. MediHub Patient puts your entire health journey at your fingertips.
```

*(170 / 170 characters)*

---

### Description (4,000 chars)

```
MediHub Patient is the official mobile companion for patients of MediHub-connected hospitals and clinics. Access your complete health portfolio securely from your iPhone.

APPOINTMENTS
• View upcoming and past appointments at a glance
• Book new visits with your preferred provider and department
• Receive reminders so you never miss an appointment
• Cancel or reschedule directly from the app

LAB RESULTS
• Get notified the moment new results are available
• View detailed lab panels with reference ranges
• Track trends over time with easy-to-read summaries
• Download and share results with other providers

MEDICATIONS & PRESCRIPTIONS
• See all active and past medications in one place
• Request prescription refills with a single tap
• View dosage instructions and pharmacy details
• Get reminders for upcoming refills

VITALS & HEALTH TRACKING
• Record blood pressure, heart rate, temperature, and SpO2
• View historical trends with clear visual charts
• Share vitals data with your care team automatically
• Stay informed about your health between visits

SECURE MESSAGING
• Send and receive messages with your doctors and care team
• Attach photos and documents to conversations
• Get push notifications for new replies
• All messages are encrypted end-to-end

BILLING & PAYMENTS
• View outstanding balances and invoice history
• See itemized charges for each visit
• Track insurance claims and payment status
• Download invoices for your records

CARE TEAM
• See all providers involved in your care
• View specialties, departments, and contact information
• Quickly message or book with any team member

VISIT HISTORY & RECORDS
• Access after-visit summaries and clinical notes
• View encounter history across all departments
• Download and share health records securely

FAMILY ACCESS
• Grant family members access to your health information
• Manage permissions — full access or view-only
• Monitor who has access and revoke at any time

PROFILE & SECURITY
• Update personal and contact information
• Manage emergency contacts and insurance details
• Sign in with Face ID or Touch ID for fast, secure access
• All data protected with enterprise-grade encryption

PRIVACY & SHARING
• Control exactly what information is shared
• Generate secure sharing links for external providers
• Full HIPAA-compliant data handling

Built with SwiftUI for a smooth, native iOS experience. Requires an active patient account at a MediHub-connected healthcare facility.
```

*(1,914 / 4,000 characters)*

---

### Keywords (100 chars)

```
health,patient,hospital,appointments,lab,results,medications,vitals,billing,medical,records,portal
```

*(99 / 100 characters)*

---

### Support URL

```
https://bitnesttechs.com/support
```

---

### Marketing URL

```
https://bitnesttechs.com/medihub
```

---

### Version

```
1.0.0
```

---

### Copyright (200 chars)

```
© 2026 BitnestTechs. All rights reserved.
```

*(43 / 200 characters)*

---

### Routing App Coverage File

> Not applicable — leave blank. (Only needed for routing/navigation apps.)

---

### App Clip

> Not applicable — no App Clip included in this release.

---

### iMessage App

> Not applicable — app does not use the Messages framework. No iMessage screenshots needed.

---

### Screenshots

| Display Size | Dimensions | Folder |
| ------------ | ---------- | ------ |
| iPhone 6.5" | 1242 × 2688px | `screenshots/6.5-inch/` |
| iPhone 6.7" | 1284 × 2778px | `screenshots/6.7-inch/` |

Upload the **10 screenshots** from `screenshots/6.5-inch/` into the **6.5" Display** slot.
The 6.7" set is auto-derived by App Store Connect, or upload both for full control.

iPad 13" Display — Not required (iPhone-only app).

---

### Build

> Upload via **Xcode → Product → Archive → Distribute App → App Store Connect**.
> Or use `xcodebuild archive` + `altool` / Transporter.
> The app does **not** use encryption beyond standard HTTPS (exempt from export compliance).

---

### Game Center

> Not applicable — no Game Center features.

---

## App Review Information

### Sign-In Information

| Field | Value |
| ----- | ----- |
| **Sign-in required** | ✅ Yes |
| **User name** | `pat_touedraogo3604` |
| **Password** | `Password123!` |

---

### Contact Information

| Field | Value |
| ----- | ----- |
| **First Name** | Tiego |
| **Last Name** | Ouedraogo |
| **Phone Number** | +226 70 12 34 56 |
| **Email** | contact@bitnesttechs.com |

---

### Notes (4,000 chars)

```text
Demo account credentials are provided above. The app connects to our
development backend at https://api.hms.dev.bitnesttechs.com/api.

After signing in with the demo credentials, the reviewer will land on the
Dashboard screen showing the patient's health summary, upcoming
appointments, and quick-access tiles.

Key flows to test:
1. Login → Dashboard loads with health summary
2. Appointments tab → view upcoming appointments
3. Lab Results → view completed lab panels
4. Medications → view active prescriptions
5. Messages → view message threads with providers
6. Vitals → view recorded vitals
7. Billing → view invoices and balance
8. Profile → view patient demographics

The app does NOT use:
• HealthKit
• Location Services
• Background App Refresh
• Bluetooth
• Apple Pay / In-App Purchases
• Push Notifications (planned for v1.1)
• Tracking / IDFA

Camera access is requested ONLY when the user taps the profile photo
to upload a new image. Face ID / Touch ID is used as an optional
biometric convenience for returning login.

The backend is live 24/7 on Railway. If you experience any issues,
please contact contact@bitnesttechs.com.
```

---

### Attachment

> Optional — not needed for this submission.

---

## App Store Version Release

| Option | Recommended |
| ------ | ----------- |
| ◯ Manually release this version | |
| ◉ **Automatically release this version** | ✅ **Select this** |
| ◯ Automatically release after App Review, no earlier than… | |

> **Recommendation:** Select **"Automatically release this version"** so the app goes live immediately after approval.

---

## What's New in This Version

```text
Initial release of MediHub Patient for iOS.

• Secure login with Face ID / Touch ID
• Dashboard with health summary and quick actions
• Appointment booking and management
• Lab results with trend tracking
• Medication list and refill requests
• Secure messaging with care team
• Vitals recording and history
• Billing and invoice management
• Visit history and after-visit summaries
• Care team directory
• Family access management
• Profile and insurance management
• Privacy controls and record sharing
• English and French language support
```

---

## Privacy Details (App Privacy)

| Data Type | Collected | Linked to User | Tracking |
| --------- | --------- | --------------- | -------- |
| Name | Yes | Yes | No |
| Email | Yes | Yes | No |
| Phone Number | Yes | Yes | No |
| Health & Fitness — Health Records | Yes | Yes | No |
| Health & Fitness — Clinical Data | Yes | Yes | No |
| Photos (profile photo only) | Yes | Yes | No |
| Identifiers — User ID | Yes | Yes | No |

**Data NOT collected:** Location, contacts, browsing history, search history, diagnostics, financial info, advertising data.

**Purpose:** App Functionality only — no analytics, advertising, or third-party sharing.

---

## App Encryption

| Question | Answer |
| -------- | ------ |
| Does your app use encryption? | Yes (HTTPS/TLS only) |
| Is it exempt? | **Yes** — standard HTTPS networking is exempt |
| Export compliance documentation required? | **No** |

> Select **"Yes"** → **"Only uses standard encryption (HTTPS, TLS)"** → exempt.
