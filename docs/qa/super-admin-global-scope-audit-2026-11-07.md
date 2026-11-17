# Super-admin hospital scope audit — 7 November 2026

This change fixes the misleading shared selector on facility-scoped pages and adds an explicitly supported all-hospital capacity view to the bed board. It does not remove hospital requirements from every clinical module.

## Confirmed cause

`HospitalScopeGateService.blocked` prevents the router outlet from being created when a route declares `requiresHospitalScope` and the caller has no hospital scope. The first screenshot shows this gate, rather than an empty response. `BedBoardServiceImpl.getBoard()` calls `requireHospital()`; removing the frontend flag alone would still produce a refused request. `BedManagementServiceImpl.getWards(false)` already supports the super-admin's global scope and uses batched bed-status counts. The network view uses that existing endpoint and the hospital directory. No per-hospital board fan-out, backend policy change or global mutation is introduced.

## Behaviour

- Bed board: global mode shows configured active-ward inventory, available beds, occupied beds and a weighted occupancy rate. Hospital cards drill down via `hospitalId` into the existing scoped patient/precaution/transfer board. A hospital with no active wards has zero configured inventory; these are not inpatient census figures.
- Facility-only pages: the shared selector says to choose a hospital, hides the all-hospital sentinel and hides the clear-to-global button. Selecting another hospital remains available. The scope gate is restyled and remains protective.
- Routes that already support global data keep their existing selectors and APIs.
- Hospital name follows URL/external scope changes, cancels obsolete lookups, and cannot display a prior hospital's response after a new pick.
- Empty reserved, maintenance and orphaned occupied beds say no linked patient; only AVAILABLE beds say free. Their underlying status and write rules are unchanged.

## Route-flag inventory

This inventory identifies shared-gate callers; it is not evidence that every module backend lacks a global read. Enabling additional network views requires validating that module's read contract and separating its facility-specific actions first. Existing route roles and backend permissions are unchanged. Line references are to the proposed `hospital-portal/src/app/app.routes.ts`.

| Flagged route segment | Line | Result |
| --- | --- | --- |
| `calendar` | 349 | Facility selection retained; selector does not offer an unsupported global mode |
| `registries` | 371 | Facility selection retained; selector does not offer an unsupported global mode |
| `panels` | 384 | Facility selection retained; selector does not offer an unsupported global mode |
| `roi` | 403 | Facility selection retained; selector does not offer an unsupported global mode |
| `webhooks` | 423 | Facility selection retained; selector does not offer an unsupported global mode |
| `on-call` | 489 | Facility selection retained; selector does not offer an unsupported global mode |
| `transfusions` | 602 | Facility selection retained; selector does not offer an unsupported global mode |
| `microbiology` | 625 | Facility selection retained; selector does not offer an unsupported global mode |
| `lab-release-worklist` | 672 | Facility selection retained; selector does not offer an unsupported global mode |
| `lab-ops-dashboard` | 707 | Facility selection retained; selector does not offer an unsupported global mode |
| `lab-staff` | 725 | Facility selection retained; selector does not offer an unsupported global mode |
| `lab-instruments` | 756 | Facility selection retained; selector does not offer an unsupported global mode |
| `lab-inventory` | 791 | Facility selection retained; selector does not offer an unsupported global mode |
| `bed-board` | 956 | Network bed-capacity read; hospital selection for patient-level board |
| `bed-management` | 978 | Facility selection retained; selector does not offer an unsupported global mode |
| `slot-admin` | 993 | Facility selection retained; selector does not offer an unsupported global mode |
| `reports` | 1005 | Facility selection retained; selector does not offer an unsupported global mode |
| `discharge` | 1029 | Facility selection retained; selector does not offer an unsupported global mode |
| `maternity` | 1042 | Facility selection retained; selector does not offer an unsupported global mode |
| `nurse-station` | 1089 | Facility selection retained; selector does not offer an unsupported global mode |
| `emar` | 1104 | Facility selection retained; selector does not offer an unsupported global mode |
| `patient-tracker` | 1116 | Facility selection retained; selector does not offer an unsupported global mode |
| `reception` | 1247 | Facility selection retained; selector does not offer an unsupported global mode |
| `pharmacy/mtm` | 1423 | Facility selection retained; selector does not offer an unsupported global mode |
| `registrations` | 1737 | Facility selection retained; selector does not offer an unsupported global mode |
| `procedure-orders` | 1756 | Facility selection retained; selector does not offer an unsupported global mode |
| `admin/order-sets` | 1779 | Facility selection retained; selector does not offer an unsupported global mode |
| `admin/integrations/dhis2` | 1815 | Facility selection retained; selector does not offer an unsupported global mode |

## Verification

Regression checks cover explicit global opt-in, continued facility-only blocking, an unassigned non-super-admin, outlet rebuilding on scope changes, global inventory without patient board/transfer requests, weighted totals, hospital search, stale/error handling, scoped search, selector semantics and external hospital-name updates. Browser verification uses fictional local fixtures and intercepts all backend requests. No live hospital data or downtime settings are touched. Detailed local logs/screenshots are under `C:/dev/hms-qa/designs/global-bed-board/`.
