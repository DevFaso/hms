# Message disclosure inventory (scorecard K4)

Every message the platform sends out of the building (SMS, e-mail and its attachment, the links
inside them), what each one carries, and whether that is the minimum. Reviewed 2026-11-23.

`MessageDisclosureGuardTest` reads this file: every outgoing template key in every bundle and
locale must be covered by an entry below (a key, or one message family such as `email.invoice.*`;
a wildcard on a whole namespace, the root alone followed by a star, is refused), and every entry must still name a template. Staff
role labels (`email.role.label.*`, a staff member's own role) are the only outgoing text the
sensitive-word scan skips. A new message fails the build until somebody reviews it against
the rule and lists it here.

## The rule

An SMS, an e-mail subject and the first lines of an e-mail (the preview a lock screen shows) may
carry:

- the hospital's or the pharmacy's name;
- a neutral event: « un nouveau résultat est disponible », « rendez-vous le … à … », « votre
  ordonnance est prête »;
- a link to the signed-in portal, a one-time code, an account reference;
- the patient's first name or initials.

They never carry a diagnosis, a test name, a result value, a medicine, words that reveal HIV, an
STI, viral hepatitis, pregnancy, maternity or reproductive care, mental health, substance use,
sexual violence, a genetic or paternity test, sickle cell disease or tuberculosis
(`SensitiveWording`), or the patient's full name in an SMS.

An e-mail attachment that holds clinical data is not sent: the message links to the portal. The
invoice is the one attachment, and its mailed copy names no test and no medicine, and prints no
line, payer reason or cancellation reason whose words reveal one of those categories. Its amounts,
shares and balance are the desk copy's.

Links point at the portal and require sign-in (see « Links »).

The in-app bell and the in-app message thread are read inside the signed-in portal and app, so they
may name the test or the clinician; they are not on this list. The server sends no push
notification today (no FCM/APNs sender in `hospital-core`); the patient apps' local notification
text is the app's own neutral « Nouveau message ».

## SMS to the patient

| Template | Arguments | Sent by | Verdict |
|---|---|---|---|
| `sms.appointment.confirmation` | date, time, hospital | `PatientNotificationRenderer` (ledger) | OK |
| `sms.appointment.reminder` | date, time, hospital | `PatientNotificationRenderer` (ledger) | OK |
| `sms.result.available` | hospital | `PatientNotificationRenderer` (ledger) | OK: never the test or the value (`PatientNotificationRoutingGuardTest`) |
| `sms.waitlist.offer` | date, time, hospital, reply-by date | `ReceptionServiceImpl` → `PatientOutreachNotifier` | OK |
| `sms.recall.due` | hospital | `RecallReminderService` → `PatientOutreachNotifier` | OK: the recall's reason is not sent |
| `sms.pharmacy.dispensed` | first name, pharmacy | `PharmacyServiceSupport` | **Fixed (K4)**: named the medicine |
| `sms.pharmacy.readyForCollection` | first name, pharmacy | `PharmacyServiceSupport` | **Fixed (K4)**: named the medicine |
| `sms.pharmacy.readyReminder` | first name, pharmacy | `PharmacyServiceSupport` | **Fixed (K4)**: named the medicine |
| `sms.pharmacy.readyCancelled` | first name, pharmacy | `PharmacyServiceSupport` | **Fixed (K4)**: named the medicine |
| `sms.pharmacy.outOfStock` | first name, routing sentence | `PharmacyServiceSupport` | **Fixed (K4)**: named the medicine |
| `sms.pharmacy.outOfStock.partner` | partner pharmacy | `PharmacyServiceSupport` | OK |
| `sms.pharmacy.outOfStock.print` | none | `PharmacyServiceSupport` | OK |
| `sms.pharmacy.outOfStock.backorder` | none | `PharmacyServiceSupport` | OK |
| `sms.pharmacy.outOfStock.backorder.dated` | restock date | `PharmacyServiceSupport` | OK |
| `sms.pharmacy.refillReminder` | first name, days left | `PharmacyServiceSupport` | **Fixed (K4)**: named the medicine |
| `sms.partner.patientAccepted` | partner pharmacy | `SmsPartnerNotificationChannel` | OK |
| `sms.partner.patientDispensed` | partner pharmacy | `SmsPartnerNotificationChannel` | OK |
| `sms.partner.pharmacyFallback` | none (placeholder for an unnamed pharmacy) | `PartnerSmsTemplates` | OK |

## SMS to a clinician

| Template | Arguments | Sent by | Verdict |
|---|---|---|---|
| `sms.staff.criticalLab` | patient's initials | `CriticalValueNotificationService` (T0) | **Fixed (K4)**: the SMS was the in-app text: test, value with unit and full name |
| `sms.staff.criticalLab.escalation` | patient's initials | `CriticalValueNotificationService` (escalation chain) | **Fixed (K4)**: same |
| `sms.staff.criticalImaging` | patient's initials | `ImagingCriticalNotificationService` | **Fixed (K4)**: was study, impression and full name |
| `sms.staff.criticalImaging.escalation` | patient's initials | `ImagingCriticalNotificationService` | **Fixed (K4)**: same |
| `sms.staff.questionnaire` | patient's initials | `ProScreeningEscalationService` | **Fixed (K4)**: named the instrument (a depression or suicide-risk screen) and the full name |
| `sms.staff.questionnaire.escalation` | patient's initials | `ProScreeningEscalationService` | **Fixed (K4)**: same |
| `sms.staff.urgentAlert` | patient's initials | `UrgentEscalationSupport` (the clinicians a step of the urgent-alert ladder adds: the covering clinician, the médecin-chef) | OK: names no alert, no study, no questionnaire and no value |

The in-app alert behind the sign-in keeps the test, the value and the name; the SMS says only that
something critical is waiting and for whom (initials).

A hospital administrator is not a clinician: the in-app text an administrator receives about an
unacknowledged alert (`lab.critical.unresolved.admin`, `urgent.alert.unresolved.admin`) says that an
incident is open and for how many minutes, and carries no patient, no study, no questionnaire and no
value. See `urgent-message-escalation.md`.

## SMS to a partner pharmacy

| Template | Arguments | Verdict |
|---|---|---|
| `sms.partner.offer` | reference, medicine, patient's initials | OK by necessity: the pharmacy must know what to dispense; initials only, never the name |
| `sms.partner.offer.partial` | reference, medicine, remaining quantity, initials | OK, as above |
| `sms.partner.allergies` | the patient's known allergies (allergen, severity) | OK by necessity (QA D3865): appended to the offer; the pharmacy must know the allergies of the patient it dispenses for |
| `sms.partner.allergyOverride` | known allergies, the prescriber's reason for prescribing despite one (first 120 characters) | OK by necessity (QA D3865): appended to the offer, so a pharmacy never dispenses an overridden order as a plain one |
| `sms.partner.reminder` | reference | OK |
| `sms.partner.autoRejected` | reference | OK |
| `sms.partner.superseded` | reference | OK |
| `sms.partner.withdrawn` | reference | OK |
| `sms.partner.heldForReview` | reference | OK |
| `sms.partner.medicationFallback` | none (placeholder) | OK |

## SMS for accounts and security

| Template | Arguments | Verdict |
|---|---|---|
| `sms.activation.patient` | hospital, code, activation link | OK |
| `sms.activation.staff` | role, hospital, code, link | OK (staff) |
| `sms.activation.staff.platform` | role, code, link | OK (staff) |
| `sms.activation.registrar` | person, role, hospital, code | **Fixed (K4)**: a patient's full name went to the registrar's phone; now first name and initial |
| `sms.activation.registrar.platform` | person, role, code | **Fixed (K4)**: as above |
| `sms.activation.hospital` | person, role, code | **Fixed (K4)**: as above, to the hospital's number |
| `sms.username.reminder` | username | OK |
| `sms.passwordReset.link` | reset link | Account link (see « Links ») |
| `sms.passwordReset.changed` | none | OK |
| `sms.phoneVerification.code` | code, minutes | OK |
| `recipientVerification.code` | code | OK (SMS or e-mail body) |
| `recipientVerification.subject` | none | OK |
| `recipientVerification.invalid` | none: a refusal shown on screen, not a message | n/a |
| `recipientVerification.unavailable` | none: a refusal shown on screen | n/a |
| `recipientVerification.cooldown` | none: a refusal shown on screen | n/a |
| `recipientVerification.limit` | none: a refusal shown on screen | n/a |

The SMS gateway's own outcome texts are shown to staff on screen, never sent:
`sms.provider.rejected`, `sms.provider.unknownOutcome`, `sms.provider.authFailed`,
`sms.provider.unreachable`, `sms.recipient.notSupported`, `sms.recipient.notAllowlisted`,
`sms.message.required`.

## E-mail to the patient

| Message | Subject | Body carries | Attachment | Verdict |
|---|---|---|---|---|
| `email.patient.resultAvailable.*` | « Un nouveau résultat est disponible – hospital » | hospital, sign-in | none | OK |
| `email.patient.appointmentReminder.*` | « Rappel de rendez-vous – hospital » | date, time, hospital | none | OK |
| `email.appointment.confirmed.*` | neutral | hospital, clinician's name, date, time, links to `/appointments/{reschedule,cancel}/{appointmentId}` | none | OK: the links require sign-in (`AuthGuard`), carry the appointment id in the path (no patient id, no token, no query string) |
| `email.appointment.rescheduled.*` | neutral | as above | none | OK |
| `email.appointment.cancelled.*` | neutral | hospital, clinician, date, time | none | OK |
| `email.appointment.completed.*` | neutral | hospital, clinician, date, time | none | OK |
| `email.appointment.noshow.*` | neutral | hospital, clinician, date, time, contact | none | OK |
| `email.appointment.label.*` | n/a (labels) | n/a | n/a | OK |
| `email.appointment.links.*` | n/a (link labels) | n/a | n/a | OK |
| `email.appointment.contact` | n/a | hospital e-mail and phone | n/a | OK |
| `encounter.visitSummary.email.subject`, `encounter.visitSummary.email.title`, `encounter.visitSummary.email.greeting`, `encounter.visitSummary.email.body`, `encounter.visitSummary.email.provider`, `encounter.visitSummary.email.closing`, `encounter.visitSummary.email.patientFallback`, `encounter.visitSummary.email.careTeamFallback` | « Votre résumé de visite est disponible » | greeting by name, hospital, clinician, « connectez-vous » | none: the summary itself stays in the portal | OK |
| `email.invoice.*` | « Facture N – hospital » | number, dates, total, balance, the clerk's optional note | invoice PDF | **Fixed (K4)**: the attached PDF listed every test and medicine by name (« Sérologie VIH », an antiretroviral). The mailed copy (`PdfInvoiceService.generateMailedInvoicePdf`) prints a test or a medicine by its category only, and any other line or payer reason whose words reveal a protected category by its category or not at all. The desk copy and the portal are unchanged. The clerk's free-text note is the clerk's responsibility |
| `email.patient.welcome.*` | « Bienvenue à hospital » | hospital, code, activation button | none | OK |
| `email.activation.*` | neutral | activation link | none | Account link (see « Links ») |

## E-mail to staff

| Message | Verdict |
|---|---|
| `refill.request.email.subject`, `refill.request.email.body` | OK: the patient's name to the prescriber, never the medicine (QA D3301) |
| `email.role.assignment.*` | OK: role, hospital, code, verification link |
| `email.role.label.*` | n/a: role labels used inside the account e-mails |
| `email.admin.welcome.*` | OK: account e-mail to the new staff member |

Scheduled reports (`ScheduledReportService`) mail aggregate counts only, written in code and not
from a template: « Aggregate counts only — no patient data ».

## E-mail for accounts and security

| Message | Verdict |
|---|---|
| `email.common.*` | n/a: greeting, button, footer shared by every e-mail |
| `email.password.reset.*` | Account link (see « Links ») |
| `email.password.changed.*` | OK |
| `email.password.rotation.reminder.*` | OK |
| `email.password.rotation.force.*` | OK |
| `email.account.restored.*` | OK |
| `email.recovery.contact.*` | OK: code only |
| `email.change.code.*` | OK: code only |
| `email.change.notice.*` | OK: the new address masked |
| `email.change.inuse.*` | OK |
| `email.username.reminder.*` | OK |

## Links

- Appointment links: `/appointments/reschedule/{appointmentId}` and `/appointments/cancel/{appointmentId}`
  route through `AuthGuard` to the patient's own appointments, where the server checks ownership. No
  patient id, no token, no query string.
- « Connectez-vous à votre espace patient »: the portal's sign-in, no parameter.
- Account links: out of scope for this change; tracked separately.

## Residual risks

- A hospital or pharmacy whose own name reveals a category (a specialised HIV clinic) is named in
  its messages; there is no neutral display name for messages yet.
- The clerk's note on an invoice e-mail is free text and is sent as typed.
