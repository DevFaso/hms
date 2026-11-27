# Urgent clinical messages: acknowledgement and escalation

Scorecard K4, second half. This page says what happens to every urgent clinical message the platform
sends to staff: who gets the first notice, what counts as an acknowledgement, and what happens while
nobody acknowledges. It describes intended behaviour; the rule is « provisoire » until each hospital's
médecin-chef signs it off (rule sets `CRITICAL_RESULT_ESCALATION` and `URGENT_ALERT_ESCALATION` in
`ClinicalRuleRegistry`).

`UrgentClinicalMessageGuardTest` keeps this page honest: every source listed here is wired to the
ladder, and a new urgent notification type fails the build until it is on the ladder or listed, with
its reason, as a notice that needs no acknowledgement.

## One ladder

Every urgent message follows the ladder of the critical laboratory result
(`CriticalResultEscalationLadder`), with the hospital's own minutes (defaults 15, 30, 60):

| Step | When | Who is added | What is recorded |
|---|---|---|---|
| First notice | at once | the alert's own circle (below) | the alert row; the first notice is owed until delivered |
| Cover | +15 min unacknowledged | the covering clinician: on call, else on duty, else every doctor | the step |
| Médecin-chef | +30 min | the médecin-chef (named by the hospital, else heads of department, else every doctor); the laboratory director for a transfusion alert, the responsible biologist for a laboratory value | the step |
| Unresolved | +60 min | nobody new | an incident row; the administrators get a flag |
| Reminders | then, slowing down to once a day | the same chain | each reminder |

Rules that hold at every step:

- **No cap.** Only an acknowledgement ends an alert, or a clinical record that no longer supports it
  (a corrected value, a culture corrected to no growth, a countersignature that is now signed).
- **Clinicians only.** A hospital administrator is never in the chain. When the incident opens, the
  administrators receive one flag: an incident is open and for how many minutes. It names no patient,
  no test, no study, no questionnaire and no value.
- **The first notice is owed.** The alert row commits with the clinical record. Its notices are
  delivered after the commit; if that delivery fails, the row stays owed and the sweep delivers it.
- **Staff SMS.** The clinicians a step adds receive an SMS that names the patient by initials and
  says nothing clinical (`message-disclosure-inventory.md`).
- **Nothing here decides what is urgent.** Thresholds belong to each alert's own rule.

## Sources

| Urgent message | First circle | Acknowledgement | Path |
|---|---|---|---|
| Critical laboratory value | prescriber and nurse in charge | read-back by a clinician | `CriticalValueNotificationService` |
| Critical imaging finding | ordering provider | « acknowledge » on the report, by a clinician | `ImagingCriticalNotificationService` + `UrgentEscalationSupport` |
| Critical questionnaire answer | recorder, panel owners, else the fallback role | « acknowledge » on the response | `ProScreeningEscalationService` + `UrgentEscalationSupport` |
| Partograph, delivery, postpartum, labour transfer alert | on-call doctors and midwives, else all of them | CRITICAL in-basket item, by somebody other than the documenter | `ObstetricAlertEscalationService` |
| Newborn danger sign (assessment or vital set) | the obstetric circle, always with a physician | as above | `ObstetricAlertEscalationService` |
| Maternal early warning (medium, high, red) | care circle, senior doctor, on call | as above | `ObstetricAlertEscalationService` |
| Adult NEWS2, high band | responsible doctor, ward nurses, a doctor other than the recorder | as above | `ObstetricAlertEscalationService` |
| Adult severe hypertension, urgent level | as NEWS2 | as above | `ObstetricAlertEscalationService` |
| Severe transfusion reaction | the clinician who asked for the blood and the covering physician; a haemovigilance task for the laboratory director | as above | `ObstetricAlertEscalationService` |
| Positive culture | the ordering clinician, else the covering clinician | as above | `ObstetricAlertEscalationService` |
| Countersignature of an emergency O positive release, past its deadline | laboratory directors, else the médecin-chef | the countersignature itself | `OPositiveReleaseNotices` + `ObstetricAlertEscalationService` |
| Countersignature of an incompatible release, past its deadline | laboratory directors, else the médecin-chef | the countersignature itself | `IncompatibleReleaseNotices` + `ObstetricAlertEscalationService` |

Who may authorise or countersign a release is the release rule's to say
(`EMERGENCY_O_POSITIVE_AUTHORITY`, `INCOMPATIBLE_RELEASE_AUTHORITY`); the ladder tells and reminds, and
changes none of it.

## Notices that need no acknowledgement

A bell notice with no acknowledgement is acceptable only for a message that is not urgent on its own.
The guard test lists each of them with its reason; the main ones are an adult NEWS2 in the medium
band (an observation interval, not an emergency), a screen-positive questionnaire without a critical
item (a referral prompt), and the first bell of an alert that is also filed on the ladder.

## Incidents

`lab.critical_result_incidents` (laboratory values) and `clinical.urgent_alert_incidents` (everything
else) hold one open row per unresolved alert: the alert's id, the hospital, when it opened, the
minutes unacknowledged and how many clinicians had been told. They hold no patient and no clinical
text. A row closes when the alert is acknowledged or its record no longer supports it, and says which.

## Alerts raised before this rule

An urgent alert that had already used its three reminders under the earlier rule, and was still
unacknowledged when the cap was removed, is marked once by the migration and is not escalated again:
the first sweeps after a deploy must not open incidents about old alerts.
