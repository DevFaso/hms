# Diagnostics workspace

The imaging, laboratory orders, laboratory results, definition approval, test configuration, quality-control dashboard and validation queue use a consistent teal workspace. The implementation changes templates and SCSS; services, endpoints, roles, result severity, reference ranges, approval transitions and validation rules are unchanged.

The workspace has clear page headers, grouped filters, readable summary cards, wider tables and keyboard-focusable scrolling areas. The results table keeps its request code and patient column visible at the left. The validation queue preserves urgency, critical indicators, units, provenance and both result/received timestamps. QC sections have distinct table names and retain the existing Levey–Jennings drill-down and calculations.

Approval and configuration counts explicitly describe definitions on the current page. Existing order/result/QC counters keep their existing scope and computation. Scope controls and the shared super-admin hospital workspace remain in place; this change does not introduce clinical aggregation or widen write permissions.

Shared presentation lives in `hospital-portal/src/app/shared/styles/_diagnostics-workspace.scss`. Inputs inside clinical forms retain their existing styling; the new input styles target search controls. Mobile minimum table widths are limited to dashboard scroll containers, so dialog forms keep their original width. FR/EN/ES labels are present for the shared workspace copy. Existing filter buttons expose their current selection with `aria-pressed`.

## Review evidence

Local preview links and detailed machine-local browser artifacts are recorded in the PR description. They supplement the repository tests and are not a portable CI runner. Screenshots use fictional `cdx-` records, never clinical reference data. Every API request is intercepted and non-GET requests and external services are blocked.

Validation covers all seven screens in FR/EN/ES, desktop/tablet/mobile, empty datasets, the global laboratory-results view, keyboard QC expansion, laboratory filtering, imaging reports, configuration dialogs and critical-row emphasis. Accessibility scans include WCAG 2 A/AA, WCAG 2.1 AA and unique landmark names. No live validation or approval actions, backend changes or deployment are part of this work.
