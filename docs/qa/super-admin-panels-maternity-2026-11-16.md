# Super-admin panels and maternity follow-up

## Behaviour changes

- Administrators without a local staff profile previously saw an irrelevant
  personal-panel message. They now see the hospital management overview and
  active assignment count. A clinician-administrator retains their own personal
  count, including its truncated-page total, separately from team assignments.
- Provider name and role filters narrow the loaded overview. Refresh reloads
  the current personal/management data and an open provider drilldown. The empty
  management view offers assignment only to roles already permitted to assign.
- The maternity guide starts collapsed. Board, referrals, birth plans and
  ultrasound have refresh actions. Board name filtering, referral name/MRN,
  receiving clinician, urgency/status/overdue filtering and ultrasound name/MRN
  and priority filtering operate on the current loaded page. Clear controls
  restore that page without changing server totals or clinical priority order.
- Referral rows display the receiving clinician and elapsed overdue minutes from
  the existing server SLA. Existing overdue status rules remain unchanged.
- Maternity patient pickers explicitly receive the selected hospital and explain
  dossier search separately from exact national-ID lookup. Four digits may find
  multiple MRN candidates; selecting and confirming the full identity remains
  necessary. National-ID lookup remains exactly twelve digits.
- Global panel and maternity directories explain their own module. They retain
  row-specific hospital navigation and do not fabricate network workload totals.

## Class inventory and guards

| Class | Hunt and affected paths | Correction / regression evidence |
| --- | --- | --- |
| Personal clinical context presented as administrative workload | Panel personal/overview metrics, role gates, empty/profile/error states; backend panel ownership still requires clinical staff | Hide only the expected missing-profile personal section for administrators; separate assignment and personal totals, including truncation. Panel specs exercise pure admin, dual clinical/admin, role filtering and unchanged read-only hospital-admin gates. Mocked browser test exercises the management view. |
| Ambiguous identity search or implicit home-hospital scope | Shared patient picker and maternity board/referral, birth-plan, prenatal and ultrasound consumers; core name-or-MRN repository matcher | Opt-in shared MRN guidance, explicit effective hospital on these consumers. Picker spec and browser test enter `5624`, require the selected hospital in the name search request, retain two candidates, and assert no exact national-ID lookup or automatic selection. Existing source guards scan picker/person-search/effective-scope callers. No national-ID length change. |
| Local filters presented as complete source counts | Panel, maternity board/referrals and ultrasound lists; existing registry/education pages from the earlier commit | Explicit current-page/loaded-row wording and independent source signals; unit tests retain totals and order while filtering. Existing source-scan scope tests remain enabled. |
| Generic network guidance obscures administrative context | Shared network workspace module-help map, panel/maternity route titles | Module-specific translated copy; shared workspace test enumerates procedures, registries, panels and maternity. Existing global browser check verifies directory navigation does not request a clinical worklist. |

No new backend permissions, clinical classifications, risk thresholds or schema
changes are introduced. Existing actions and server authorization remain in
place. The source scans are partial guards for product wording; semantic UI
regressions are covered by component and browser assertions rather than a claim
that static scanning can establish correct clinical meaning.

## Verification

- Production portal build and lint pass locally.
- Six Playwright tests pass against the production build, including pure
  super-admin panel management, selected-hospital maternity MRN candidates,
  procedure cancellation consent, network navigation, education creation scope
  and registry status scope. Screenshots reviewed for panel and maternity.
- These browser checks use mocked APIs and demonstrate UI/request construction;
  they are not live-patient, deployment or backend authorization evidence.
- Full hospital-core suite passes on the unchanged core tree. Full portal
  suite: 5,615 passing tests and all coverage floors met; script suite: 582
  passing tests. The final display correction is rerun before publication.
- Translation parity/referenced keys, source guards and script tests remain
  enabled; no quality-gate floor or baseline is lowered.
