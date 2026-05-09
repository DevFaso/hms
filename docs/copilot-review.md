Implementation Progress — 2026-05-07

Started implementation on local branch feature/patient-portal-parity-2026-05-07. No push performed.

Completed first parity slice: patient pharmacy invoices/payment and claim history now uses the authenticated /me/patient/pharmacy/payments and /me/patient/pharmacy/claims contracts on web, Android, and iOS. Android and iOS now have first-class pharmacy invoice surfaces, models, navigation entries, and focused tests/diagnostics coverage.

Validation so far: Angular lint clean; focused Angular shell + pharmacy invoice specs 8/8 passing; Android focused unit test task passing after adding ignored local SDK path. iOS diagnostics clean, but xcodegen/xcodebuild are unavailable on this Windows machine.

I created the implementation todos and kept them ordered by dependency. I did not overwrite copilot-review.md because it already has uncommitted user edits; this is the clean user-story/tasklist content to add to that doc.

User Stories

As a patient, I want the same patient portal navigation on web, Android, and iOS so I can find appointments, medications, labs, vitals, billing, documents, pharmacy invoices, notifications, sharing, and family access consistently.

As a patient, I want notification lists, unread counts, mark-read, and mark-all-read to work the same on every platform so I can manage alerts reliably.

As a patient, I want appointment cancel and reschedule actions to use one backend contract so the same appointment action succeeds regardless of device.

As a patient, I want pharmacy payment and claim history on Android and iOS, matching the web pharmacy invoices page, so I can review medication-related charges anywhere.

As a family/proxy user, I want delegated access views for appointments, medications, lab results, billing, and records on web and iOS so proxy access is as useful as Android’s current flow.

As a patient, I want all delegated-record access to be scoped to authenticated permissions so my PHI is not exposed through patient IDs or stale links.

As a product owner, I want the team to decide whether web patient messages should stay in shared /chat or become a patient-specific messages page so cross-platform parity is intentional.

As a QA reviewer, I want backend, web, Android, iOS, and E2E tests for the parity flows so future contract drift is caught before release.

Implementation Tasklist

[Discovery] Confirm canonical patient API contracts in PatientPortalController.java: /me/patient/notifications, /appointments/cancel, /appointments/reschedule, /pharmacy/payments, /pharmacy/claims, and /proxy-access/{patientId}/*.

[Discovery] Create a parity acceptance matrix in copilot-review.md covering navigation, notifications, appointment actions, pharmacy, proxy access, messages, documents, and records.

[Migration] Verify no schema change is needed. If indexes are required later, create hospital-core/src/main/resources/db/migration/V93__patient_portal_parity_indexes.sql.

[Entity] Verify existing entities already support the work: Notification, Appointment, pharmacy payment/claim entities, proxy access entities, and patient documents.

[Repository] Verify existing repositories cover patient-owned and proxy-scoped queries, especially NotificationRepository.java and pharmacy repositories.

[Service] Validate PHI ownership and proxy-permission checks in patient portal, appointment, notification, pharmacy, and proxy services. ⚠️ Needs peer review.

[Controller] Lock the canonical backend routes in PatientPortalController.java with ROLE_PATIENT or authenticated proxy access as appropriate. ⚠️ Needs peer review.

[DTO] Confirm request/response shapes for cancel/reschedule, notifications, pharmacy payments/claims, and proxy responses.

[Mapper] Confirm existing mapper classes support all response DTOs without introducing MapStruct.

[Frontend model/service] Update patient-portal.service.ts, ApiService.kt, and APIEndpoints.swift to use the same patient notification and appointment-action contracts.

[UI component] Update web navigation in shell.ts so hidden patient routes become first-class nav entries.

[UI component] Add Android pharmacy invoices/payment history screen, ViewModel, navigation drawer entry, and models under patient-android-app/app/src/main/java/com/bitnesttechs/hms/patient/features/pharmacyinvoices/.

[UI component] Add iOS pharmacy invoices/payment history view and ViewModel under patient-ios-app/MediHubPatient/Features/PharmacyInvoices/.

[UI component] Add Angular proxy data detail flow under my-family-access to match Android’s delegated data categories. ⚠️ Needs peer review.

[UI component] Extend iOS family/proxy detail views in FamilyAccessView.swift and ProxyDetailView.swift. ⚠️ Needs peer review.

[UI component] Align notification screens on web, Android, and iOS for list, unread filter/count, mark one read, and mark all read.

[UI component] Align appointment cancel/reschedule flows on Android and iOS with backend request bodies.

[Product] Decide whether web patient messages remain routed to /chat or get a dedicated patient messages page.

[i18n] Add/update English and French strings for new navigation labels, pharmacy invoices, proxy data categories, and notification actions.

[Backend tests] Add MockMvc tests for patient notifications, appointment cancel/reschedule, pharmacy self-service, and proxy data endpoints.

[Backend tests] Add service tests for ownership, patient scoping, proxy permission enforcement, and notification state transitions. ⚠️ Needs peer review.

[Frontend tests] Extend Angular specs for shell navigation, pharmacy invoices, notifications, family access, and proxy data detail.

[Android tests] Add feature tests for endpoint normalization, notifications, pharmacy invoices, appointment actions, and proxy data navigation.

[iOS tests] Add endpoint/config and ViewModel tests for notifications, pharmacy invoices, appointments, and proxy data.

[E2E tests] Add Playwright patient flows for notification read actions, pharmacy payment history, appointment reschedule/cancel, and proxy record viewing.

[Verification] Run ./gradlew :hospital-core:test, Angular lint/tests, Android unit tests, iOS tests, and Playwright E2E.

Task Summary — Patient Portal Cross-Platform Parity
Total tasks: 32 in the todo list
New files: expected for Android pharmacy invoices, iOS pharmacy invoices, web proxy detail, backend tests, mobile tests, and E2E tests
Modified files: backend controller/service tests, web service/navigation/components, Android API/navigation/features, iOS endpoints/navigation/features/localization
High-risk flags: 4 — PHI/proxy access, auth/session handling, billing/payment, appointment ownership

Tasks are ready in the todo list. Run HMS Implementer to execute them in order.