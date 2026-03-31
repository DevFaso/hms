# Google Play Console – Complete Fill-Out Guide for My Chart BF

Use this document to fill out every required section in the Google Play Console.

---

## 1. STORE LISTING (en-US)

### App Name
```
My Chart BF
```

### Short Description (80 chars max)
```
Access your medical records, appointments, lab results & prescriptions securely.
```

### Full Description (4000 chars max)
```
My Chart BF is a secure patient healthcare portal that puts your complete medical journey at your fingertips. Designed for patients in Burkina Faso and beyond, My Chart BF connects you directly to your healthcare providers and medical records.

KEY FEATURES:

📋 Medical Records
View your complete health history including diagnoses, immunization records, treatment plans, and encounter summaries — all in one place.

📅 Appointments
Check upcoming and past appointments. Stay on top of your healthcare schedule with appointment details and provider information.

🔬 Lab Results
Access your laboratory test results with clear indicators for normal, abnormal, and critical values. Track your health trends over time.

💊 Medications & Prescriptions
View your active medications, dosages, and prescription history. Submit refill requests directly from the app.

📊 Vitals Tracking
Monitor your vital signs including blood pressure, heart rate, temperature, and weight. Record new vitals to share with your care team.

💬 Secure Messaging
Communicate directly with your doctors and healthcare providers through encrypted in-app messaging.

💰 Billing & Invoices
View outstanding balances, invoice history, and payment details in a clear, organized format.

🔒 Privacy & Security
• Biometric login (fingerprint/face recognition)
• Encrypted data storage
• Consent management for inter-hospital record sharing
• Full audit trail of who accesses your records

📄 Document Management
Upload and manage medical documents, insurance cards, and other health-related files.

🔔 Notifications
Receive timely reminders for appointments and important health updates.

My Chart BF is built with your privacy as a top priority. Your biometric data never leaves your device, all communications are encrypted, and you maintain full control over who can access your health records.

Requires an active patient account at a participating healthcare facility.
```

### App Icon
Already created: `store-assets/app-icon-512x512.png`

### Feature Graphic
Already created: `store-assets/feature-graphic-1024x500.png`

### Screenshots
Already created in:
- `store-assets/phone/` (8 screenshots)
- `store-assets/tablet-7inch/`
- `store-assets/tablet-10inch/`

Upload at minimum the 8 phone screenshots.

---

## 2. APP CONTENT

### 2.1 Content Rating Questionnaire

Answer the questionnaire as follows:

| Question | Answer |
|----------|--------|
| **Category** | Utility / Productivity / Other |
| **Does the app contain violence?** | No |
| **Does the app contain sexual content?** | No |
| **Does the app contain profanity?** | No |
| **Does the app contain drug references?** | No (medication management is clinical, not recreational) |
| **Does the app allow user-generated content?** | Yes (chat messages, uploaded documents) |
| **Does the app allow users to interact?** | Yes (patient-provider messaging) |
| **Does the app share the user's location?** | No |
| **Is the app a news app?** | No |
| **Does the app contain gambling?** | No |
| **Is the app a social/dating app?** | No |
| **Does the app contain simulated gambling?** | No |
| **Does the app contain horror/fear content?** | No |
| **Does the app promote marijuana?** | No |

**Expected rating: Rated for Everyone / PEGI 3 / USK 0**

### 2.2 Target Audience and Content

| Field | Value |
|-------|-------|
| **Target age group** | 18 and older |
| **Is the app designed for children?** | No |
| **Does the app appeal to children?** | No |

### 2.3 Privacy Policy

```
https://hms.bitnesttechs.com/privacy-policy
```

(I've created the privacy policy page in the hospital-portal. It will be accessible at this URL once deployed.)

### 2.4 Ads Declaration

| Field | Value |
|-------|-------|
| **Does your app contain ads?** | No |

### 2.5 Data Safety Questionnaire

#### Overview

| Question | Answer |
|----------|--------|
| **Does your app collect or share user data?** | Yes |
| **Is all user data encrypted in transit?** | Yes (HTTPS/TLS) |
| **Do you provide a way for users to request data deletion?** | Yes |

#### Data Types Collected & Shared

| Data Type | Collected | Shared | Purpose | Optional |
|-----------|-----------|--------|---------|----------|
| **Name** | ✅ Yes | ❌ No | App functionality | No (required) |
| **Email address** | ✅ Yes | ❌ No | App functionality, Account management | No |
| **Phone number** | ✅ Yes | ❌ No | App functionality | No |
| **Address** | ✅ Yes | ❌ No | App functionality | No |
| **Date of birth** | ✅ Yes | ❌ No | App functionality | No |
| **Health information** | ✅ Yes | ✅ Yes (with consent, to other hospitals) | App functionality | No |
| **Medications** | ✅ Yes | ✅ Yes (with consent) | App functionality | No |
| **Other personal info (emergency contacts)** | ✅ Yes | ❌ No | App functionality | No |
| **Financial info (insurance, billing)** | ✅ Yes | ❌ No | App functionality | No |
| **Messages (chat)** | ✅ Yes | ❌ No | App functionality | Yes |
| **Photos/files (uploaded documents)** | ✅ Yes | ❌ No | App functionality | Yes |
| **App interactions** | ✅ Yes | ❌ No | App functionality, Analytics | No |
| **Device or other IDs** | ❌ No | ❌ No | — | — |
| **Location** | ❌ No | ❌ No | — | — |
| **Browsing history** | ❌ No | ❌ No | — | — |
| **Contacts** | ❌ No | ❌ No | — | — |
| **Audio/video/photos (from device)** | ✅ Yes (camera for uploads) | ❌ No | App functionality | Yes |

#### Security Practices

| Question | Answer |
|----------|--------|
| **Is data encrypted in transit?** | Yes |
| **Can users request data deletion?** | Yes |
| **Is data processed ephemerally?** | No (data is stored) |
| **Does the app follow Google's Families Policy?** | N/A (not a children's app) |

---

## 3. STORE SETTINGS

### App Category
```
Category: Medical
```

### Contact Details (Developer page)

| Field | Value |
|-------|-------|
| **Developer name** | Bitnest Technologies |
| **Email** | contact@bitnesttechs.com |
| **Website** | https://hms.bitnesttechs.com |
| **Phone** | (add your phone number) |

### Tags (select up to 5)
1. Medical
2. Health & Fitness
3. Healthcare
4. Patient Portal
5. Medical Records

---

## 4. CLOSED TESTING – ALPHA

### Countries/Regions
Already configured:
- ✅ Burkina Faso
- ✅ United States

### Testers
Add testers via:
- **Email list**: Create an email list with tester Gmail addresses
- Or **Google Group**: Create a Google Group and add testers to it

---

## 5. CHECKLIST BEFORE "SEND FOR REVIEW"

- [ ] Upload signed AAB (release build)
- [ ] Fill in Store Listing (name, descriptions, screenshots, icon, feature graphic)
- [ ] Submit Content Rating questionnaire
- [ ] Set Target Audience to 18+
- [ ] Set Privacy Policy URL: `https://hms.bitnesttechs.com/privacy-policy`
- [ ] Declare "No ads"
- [ ] Complete Data Safety questionnaire
- [ ] Set App Category to "Medical"
- [ ] Set up Alpha testers list
- [ ] Deploy hospital-portal with privacy-policy route live
