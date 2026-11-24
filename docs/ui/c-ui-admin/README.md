# C-UI: administration and integrations

Display-only form work. The shared `appFormControl` directive adds the uniquely
named `keneya-form-control` style to native controls. It has no value accessor,
validator, event listener or API call. Existing component class bodies and
form/event bindings are unchanged. Native text/select/textarea binding,
validation and disabled behavior are covered by tests.

These screenshots come from the local production SPA in Chromium using synthetic
API responses. They do not prove dev deployment or physical-device acceptance.
ADT and cost use a super-admin fixture; the DHIS2 tabs use a hospital-admin fixture.
The checks measure every control's height and containment, including controls
below the first viewport. ADT also explains the disabled hospital/dependent fields
and replaces wire-event codes and setting names with EN/FR/ES wording.

| View | Route | 390 px | 1440 px |
|---|---|---|---|
| Automatic admissions | `/admin/adt-intake-configs` | [Screenshot](automatic-admissions-390.png) | [Screenshot](automatic-admissions-1440.png) |
| Integration settings | `/admin/integrations/dhis2`, settings tab | [Screenshot](integration-settings-390.png) | [Screenshot](integration-settings-1440.png) |
| Integration mappings | `/admin/integrations/dhis2`, mappings tab | [Screenshot](integration-mappings-390.png) | [Screenshot](integration-mappings-1440.png) |
| Integration exports | `/admin/integrations/dhis2`, exports tab | [Screenshot](integration-exports-390.png) | [Screenshot](integration-exports-1440.png) |
| Platform cost filters | `/super-admin/cost` | [Screenshot](platform-cost-filters-390.png) | [Screenshot](platform-cost-filters-1440.png) |

The administration guard scans external and inline Angular templates, including
conditional, loop-empty and deferred branches. It accepts the established shared
field wrappers, the native styling directive and Material form fields. Existing
custom designs have exact, context-bound exceptions with a CSS-based reason in
`scripts/form-control-exceptions.json`; a new control, moved control, empty reason
or stale exception fails the guard. Hidden/checkbox/radio/file/range/color controls
have explicit type-specific reasons to retain their native affordances.

This module does not declare the clinical/operations sweep complete. That module
has its own review and screenshots; it does not hold up the other go-live lanes.

Claude review follow-up: ADT uses one stylesheet; mobile padding is asserted at
16 px (desktop 24 px). The shortened navigation label is measured without
clipping in the open phone drawer: [390 px navigation](automatic-admissions-navigation-390.png).
The shared production-SPA config applies no network throttling.
`npm run e2e:form-display` runs explicitly in frontend CI, separately from the default suite.

Exception keys include an occurrence counter: 87 existing controls have 85
previously recorded fingerprints (two pairs were identical siblings). Each
control now has its own reasoned entry; copying one again fails the guard.
This corrects the old collision rather than adding an exception for new code.
