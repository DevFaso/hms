# Super-admin clinical workspaces

Base: `develop`. Implementation follows the hospital directory work in PR #1196;
PR #1198 carries the develop-based integration and this follow-up.

## Scope defect and class hunt

SA-SCOPE-1: a clinical request must use the selected request hospital. Falling back
from `activeHospitalId` to the account's primary hospital can send a super-admin's
request to a different hospital, or turn a global view into a hospital request.
All instances found now call `RoleContextService.effectiveHospitalIdForRequest()`.

| Owning component | Result |
| --- | --- |
| Procedure orders | Dynamic effective scope; global view makes no clinical list request; late list responses are discarded |
| Medication history | Effective request hospital |
| Maternity | Effective request hospital |
| Prenatal tab | Effective request hospital |
| Postpartum tab | Effective request hospital; regression asserts selected h2 while primary is h1 |
| Ultrasound tab | Effective request hospital |
| Birth plan tab | Effective request hospital |
| Lab results | Effective request hospital |
| Patient chart | Effective request hospital |
| Medical history tab | Effective request hospital |
| Registrations | Removed multiline primary-hospital fallback after effective scope |
| Imaging | Not an instance: its preceding super-admin branch explicitly returns hospitalFilter or null; retained |
| hospital-core source | No instance of this frontend fallback expression |

`hospital-request-scope.test.mjs` scans portal/core source for the fallback class.
The imaging exception requires its explicit super-admin branch, rather than a
blanket file exclusion. Falsification: temporarily restoring the procedure
fallback made this guard fail and two procedure regression tests fail; restoring
the production fix passes those checks. Local negative logs are
`new-workflows-negative-guard.log` and `new-workflows-negative-unit.log` in the portal.

## Observable changes

- Procedures: refresh, patient/procedure search, urgency filter, complete lifecycle
  tabs, result count, filter empty state, and historical consent wording on cancelled
  orders. Actual recorded consent remains visible. Creation uses the selected hospital.
- Education: explicit network/hospital scope with URL initialization, scoped creation
  and assignments, refresh, actionable empty/filter states, and scope-change resets.
  Responses from the previous hospital cannot replace the current library/progress.
- Registries: all statuses explicitly requests the six statuses (an omitted status
  defaults to ACTIVE on the server), overdue filter, next-visit/name sorting and
  refresh. Default ordering remains the server's clinical ordering. Combined lists
  retain the 200-row display cap, source totals and truncation notice.
- Global directory: procedure/registry-specific guidance and one pagination summary.
  Hospital directory counts remain directory counts; unsupported workload totals
  are not inferred from them.
- Existing backend role policy is unchanged. Selecting a hospital is separate from
  authorizing a clinical action.

## Integration guard repairs

Running all script tests also exposed inherited workflow violations. Fixed report
lookup's silent disabled validation, the redundant disabled Today button, scheduling
badge spacing, scheduling field-specific refusals and raw server messages, ROI colon
localization, and the missing signature-type English fallback group. Removed only
stale accessibility/raw-message pins after the offending sites were fixed. Guard
assertions were not weakened.

## Verification

- Full hospital-core suite: 26,761 tests, no failures/errors/skips.
- Full portal suite: 5,591 tests (final rerun recorded in PR validation).
- Script guard tests: 581 passing.
- Browser verification: all four mocked integration tests passing.
- Production build, lint, workflow guards, translation parity, referenced keys and
  raw-enum checks passing.
- Negative scope test described above proves the old implementation is rejected.
- Browser tests run against the local Angular application, using mocked API data:
  selected hospital differs from primary; URL and X-Hospital-Id agree; global view
  sends no procedure worklist request; cancelled consent is historical; education
  creation requires hospital scope; registry All sends six explicit statuses.
- Browser mocks verify rendering/request construction, not live backend authorization
  or deployment. No patient data was changed.
