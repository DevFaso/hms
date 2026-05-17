---
name: angular-portal-component
description: Use when adding or modifying Angular 20 components, services, NgRx stores, or routes in hospital-portal/. Covers i18n (EN/FR/ES) FR-completeness gate, accessibility (axe-core/Playwright smoke, WCAG 2.1 AA keyboard contract), and the per-feature module layout under hospital-portal/src/app/.
---

# hospital-portal — Angular 20

The clinician-facing web UI. 61 feature modules under
`hospital-portal/src/app/`, 95+ specs, internationalised in EN / FR /
ES with a hard CI gate.

## Feature module layout

Each feature lives in its own folder under `src/app/<feature>/`:

- `account-setup/`, `admin/`, `admissions/`, `analytics/`,
  `announcements/`, `appointments/`, … (top-level grep that for the
  full list).
- Each folder contains: components, services, store (NgRx if needed),
  routes, specs.
- Shared primitives live in `src/app/shared/` —
  `SkipLinkComponent`, `FocusOnErrorDirective`, common
  pipes/directives. Reuse them; don't reinvent.

## Component conventions

- **Standalone components** by default (no NgModule for new features).
  Imports declared on `@Component({ imports: [...] })`.
- **Strong typing** — every API call returns a typed interface from
  `src/app/<feature>/<feature>.types.ts` or
  `src/app/shared/api-types.ts`.
- **Separation of concerns**:
  - Component class — view-model + event handlers only.
  - Service — HTTP + business logic + side effects.
  - Store (NgRx) — only for state shared across routes.
- **Loading / empty / error states explicit** — never assume the happy
  path. Use the shared `<hms-empty-state>` / `<hms-error-state>` /
  `<hms-loading-state>` primitives.
- **OnPush change detection** for any component bound to a large
  observable stream (e.g. patient list, tracker board).
- **Use `T[]` shorthand, not `Array<T>`.** The project's
  `@typescript-eslint/array-type` rule blocks the long form and CI
  fails on `Array<T>` references. Applies everywhere: signal inputs
  (`input.required<T[]>()`), computed return annotations, local
  variables, helper parameter and return types. Caught in PR #357
  Copilot/SonarQube run — seven violations on one PR is too many to
  let through twice. When adding a sub-component or helper, default
  to `T[]` from the first keystroke.

## i18n — the FR completeness gate is non-negotiable

CI fails any PR where a locale falls below **99 % key coverage of EN**.
Enforced by `scripts/i18n/check-completeness.js` wired into
`.github/workflows/frontend-ci.yml`.

When you add a translatable string:

1. Add the key + EN value to `src/assets/i18n/en.json`.
2. Add the FR + ES translations in the matching files. If you don't
   speak FR/ES, ask the user to provide — **do not invent**.
3. Use the `ngx-translate` `| translate` pipe or the matching
   `TranslateDirective` in the template; never hard-code clinical
   strings. The portal uses **ngx-translate**, not Transloco — keep
   pipe and directive references on the same library (an earlier
   draft of this skill mixed the two and Copilot rightly flagged it
   on PR #340).

**i18n key convention** — the codebase uses `UPPER_CASE_SCREAMING`
nested keys (e.g. `ANALYTICS.KPI.DOOR_TO_DOCTOR_LABEL`), not the
kebab-case variant some earlier docs imply. Mirror the existing
feature-name conventions when adding keys: open the EN file, find the
nearest feature block, extend it. Adding a sibling kebab-case block
alongside an UPPER_CASE block multiplies maintenance burden and
breaks the at-a-glance scan of the JSON.

**i18n the units, not just the labels.** Strings like `"27 min"` or
`"7.5 %"` inside `formatMinutes()` / `formatPercent()` helpers are
just as translatable as the surrounding labels. Don't hard-code `"min"`
in the TS file — pass the value to a translation key that injects the
unit, or use Angular's number/percent pipes with locale support.
Caught in PR #341 Copilot review on `kpi-cards.component.ts`.

**i18n the aria-labels, not just the visible text.** Strings passed
to `[attr.aria-label]` or composed inside a `computed()` that
returns an accessibility name MUST flow through ngx-translate too.
Pattern that bit PR #357: a sparkline component built its name from
`` `${label()} trend, ${count} data points` `` — visually fine in
EN, but screen-readers in FR/ES then announced English words inside
otherwise-translated text. **Inject `TranslateService` in the
component and use `translate.instant('KEY', { label, count })`** so
the translation file owns the word order. Word-by-word
concatenation in TypeScript breaks for languages where the
adjective precedes the noun (FR/ES put "tendance" / "tendencia"
before the label, not after). For pluralisation, accept that
`"1 data points"` is acceptable in this context — ngx-translate's
`count` parameter does the substitution without an ICU plural form,
which is fine for sparse-data sparklines where the count rarely
hits 1.

## Accessibility — the axe smoke gate is non-negotiable

`@axe-core/playwright` smoke runs on every PR across:

- `/login`
- `/reception`
- `/nurse-station`
- `/prescriptions`
- `/pharmacy/dispensing`
- doctor dashboard
- patient tracker
- AVS (after-visit summary)

Any **new** axe violation on these routes fails CI. To extend
coverage: add the route to the `axe.spec.ts` test list. Common
violations:

- `color-contrast` — use the design tokens in
  `src/styles/_tokens.scss`; never hard-code colour hex.
- `aria-required-attr` — labels must associate with inputs via
  `[attr.for]` or wrap.
- `focus-visible` — global tokens live in
  `src/styles/_a11y.scss`; never remove the `:focus-visible` outline.

## Keyboard navigation (row 11 contract)

Per `docs/ui/accessibility.md`:

- `SkipLinkComponent` first focusable on every page; targets
  `#main-content`.
- `<main id="main-content">` on the shell.
- `NavigationEnd → focus(main)` hook fires after route change so the
  next page's content is announced.
- `FocusOnErrorDirective` on form fields jumps to the first invalid
  control on submit.
- Vitals grid + similar dense widgets use **roving tabindex**, not a
  flat tabindex chain.
- CDS warn-card dismissible via `Esc`.
- E2E enforcement: `e2e/keyboard-nav.spec.ts`.

## State management

- **Component-local state** — signals (`signal()`, `computed()`,
  `effect()`) by default.
- **Feature-shared state** — feature-scoped service with a
  `BehaviorSubject` or signal store.
- **Cross-feature shared state** — NgRx store (already in use for
  authentication, appointments, prescriptions).

Don't introduce a new global store for a feature that's local to one
route.

## API calls

- All HTTP through `HttpClient` services under
  `src/app/<feature>/<feature>.service.ts`.
- Base URL injected from `environment.backendBaseUrl` — never
  hard-code.
- Auth token handling is centralized in `src/app/auth/auth-interceptor.ts`
  (adds `Authorization: Bearer ...` + handles refresh-cookie roundtrip).
- 401 + 403 are handled globally; per-call error handling focuses on
  user-actionable surfaces (404 = "not found" toast, 5xx = "service
  unavailable" toast).

**Do NOT swallow errors as empty-data.** A pattern like
`pipe(catchError(() => of({} as T)))` collapses 401 / 403 / 500 into
"no data" cards, which makes outages and authorization problems
indistinguishable from a genuinely empty window — operators can't
tell whether to escalate or dismiss. Either let the error propagate
to the component (and render an explicit error state) or return a
typed `Loaded<T> | Failed<E>` discriminated union. Caught in PR #341
Copilot review on `dashboard.service.ts`.

## Component reachability must match the backend's @PreAuthorize

When embedding a new component, verify that the **route guard** on the
page that hosts it matches the **`@PreAuthorize` roles** on the
backing controller. The row-32 KPI cards were embedded inside the
analytics page (`ROLE_SUPER_ADMIN` route guard), but the backing
endpoint allows `SUPER_ADMIN / HOSPITAL_ADMIN / DOCTOR / NURSE /
STAFF` — so the new UI is unreachable for four of its five intended
users. Either widen the guard or place the component on a route whose
guard matches. Caught in PR #341 Copilot review on
`analytics.html`.

## Build + test commands

```bash
cd hospital-portal
npm install
npm run lint           # ESLint — passes on every PR (pre-commit hook)
npm run format:check   # Prettier — passes on every PR (pre-commit hook)
npm test               # Jest unit + spec
npm run e2e            # Playwright + axe smoke
npm run build          # ng build (AOT)
```

The pre-commit hook in `.claude/settings.json` runs `format:check + lint`
on every git commit. If it fails, **fix the underlying issue**; never
`git commit --no-verify`.

## Sub-component pattern for feature add-ons

When a feature module gains a discrete sub-rollup (e.g. the row-32
`<app-kpi-cards>` added inside `analytics/`), put it under a child
directory named for the sub-component:

```
src/app/<feature>/
  ├── <feature>.ts            ← top-level standalone component
  ├── <feature>.html / .scss
  └── <sub-component>/
        ├── <sub-component>.component.ts
        ├── <sub-component>.component.html
        └── <sub-component>.component.scss
```

The parent imports the child via `imports: [..., ChildComponent]`
inside its `@Component`. The child stays standalone, gets its own
`DashboardService` (or feature-service) injection, and renders
independently — the parent should never reach into the child's
signals.

This is the pattern used by the row-32 KPI dashboard
(`analytics/kpi-cards/`); reuse it for follow-on KPIs (median P50
door-to-doctor, sparkline trend) so each rollup is one file diff
not a re-render of the whole parent template.

## Reference files

- `hospital-portal/src/app/` — feature modules
- `hospital-portal/src/app/analytics/kpi-cards/` — reference sub-component pattern
- `hospital-portal/src/app/shared/a11y/` — accessibility primitives
- `hospital-portal/src/app/auth/` — interceptors + guards
- `hospital-portal/src/assets/i18n/` — translation files
- `hospital-portal/src/styles/_tokens.scss` — design-token source of truth
- `hospital-portal/src/styles/_a11y.scss` — global a11y tokens
- `hospital-portal/e2e/keyboard-nav.spec.ts` — keyboard contract enforcement
- `docs/ui/accessibility.md` — WCAG 2.1 AA keyboard contract
- `scripts/i18n/check-completeness.js` — the FR-completeness gate
- `.github/workflows/frontend-ci.yml` — CI wiring
