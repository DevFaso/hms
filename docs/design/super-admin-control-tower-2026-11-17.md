# Super-admin control tower: research and implementation proposal

Date: 17 November 2026. Audience: platform super-administrators managing multiple organizations and hospitals.

## Recommended dashboard

Prioritize four questions: where can I administer the network, what needs attention, how trustworthy is the data, and where can I investigate? Keep clinical activity as secondary oversight, with authorized drill-downs.

| Priority | Content | Current delivery |
| --- | --- | --- |
| 1 | Visible administration navigation | Network, access, platform and security groups; all 13 existing destinations remain accessible without expanding a footer. |
| 2 | Operational attention | Existing integration-health inventory supplies failing, degraded, no-history and healthy counts. A link opens integration management. Configuration counters retain their separate meaning. |
| 3 | Network overview | Existing organization, hospital, user, patient, department and role totals remain available. The current global scope is retained. |
| 4 | Investigation | Audit events retain failure filtering, while successful TOKEN_REFRESH events are opt-in. All source records remain available through audit logs. The displayed sample size is explicit. |
| 5 | Clinical oversight | Existing metrics and feeds remain; activity tabs wrap rather than clip. A failed aggregate feed reports unavailable instead of no recent records. Unavailable summary totals stay hidden behind an availability notice; activity counts display a dash rather than zero. |

This hierarchy is a design recommendation for HMS, not a claim that one layout is universally best. A dashboard-pattern study reviewed 144 dashboards and emphasizes explicit trade-offs between space, interaction and information: [Bach et al., Dashboard Design Patterns](https://arxiv.org/abs/2205.00757).

## Why this fits the role

Microsoft describes a multitenant control plane as the management layer for tenant access, provisioning and maintenance. HMS should foreground these tasks instead of presenting the super-admin as a treating clinician: [Multitenant control planes](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/control-planes). Multi-tenant oversight should also preserve tenant boundaries when entering a tenant workspace: [Microsoft 365 multitenant management](https://learn.microsoft.com/en-us/microsoft-365/admin/multi-tenant/manage?view=o365-worldwide).

NN/g recommends making important information salient while managing complexity through relevant disclosure. Here, frequent management destinations become visible, while lower-value successful token-refresh events have an explicit toggle: [Complex application design](https://www.nngroup.com/articles/complex-application-design/).

The wrapped activity controls support narrow viewports and enlarged text. W3C explains reflow as avoiding loss of information or function and unnecessary two-dimensional scrolling: [Understanding WCAG 2.2 Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html). This change is not a claim of whole-application WCAG conformance.

## Data contract findings and next phase

The existing summary, platform configuration, integration inventory and recent-activity APIs do not expose a common organization/time-window filter contract. A selector that merely changed the label would be misleading. Keep the current scope explicit until the server can apply one consistent filter across these sources. Clinical counts already have acting-hospital behavior in the backend; an organization selector must not silently mix globally counted records and hospital-scoped metrics.

Next, add a server aggregate with validated organization/hospital scope, period, generated-at metadata, source availability and completeness for each metric. Enforce authorization on the server. Return unknown/unavailable distinctly from zero. Define account-enabled versus recently signed-in users, unique patient records versus hospital registrations, and event counts versus current operational workload before adding new KPIs.

Only then add a shared scope selector and meaningful 7/30-day comparisons. Trends require historical observations or timestamp-based aggregate queries; current totals cannot produce honest trend lines. Integration status is recorded connector history, not proof of end-to-end uptime. No-history must remain unknown. Latency, queue age, backup age and service availability require instrumented sources and agreed thresholds before they become platform-health indicators.

Consider a future server-side security view that groups routine refreshes while retaining failures and supports time-windowed counts. The current dashboard loads only a recent sample; its failure filter must not imply a global failure total. A later operational view can prioritize failed integrations, access failures and overdue administrative work with links to filtered destination screens once those routes support the filters.

## Validation and acceptance

- All existing administration destinations remain navigable; emergency controls remain links into their existing guarded workflow.
- Desktop and narrow layouts show every activity category without clipped controls; existing keyboard tab navigation remains usable.
- Failure responses remain unavailable, not zero or empty. A later refresh cannot be overwritten by an earlier health response.
- Successful token refreshes are hidden initially, can be restored, and failures are never hidden by the routine-event toggle.
- Server-supplied health totals are used independently of the number of returned inventory rows.
- No new probes, resyncs, clinical mutations, tenant impersonation, permissions or deployment actions are introduced.
- Validate full backend and portal suites, build, lint, translation guards and a rendered browser with synthetic API fixtures. Browser fixtures verify presentation; they do not establish production connector health or live acceptance.
