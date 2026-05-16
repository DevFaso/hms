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

## i18n — the FR completeness gate is non-negotiable

CI fails any PR where a locale falls below **99 % key coverage of EN**.
Enforced by `scripts/i18n/check-completeness.js` wired into
`.github/workflows/frontend-ci.yml`.

When you add a translatable string:

1. Add the key + EN value to `src/assets/i18n/en.json`.
2. Add the FR + ES translations in the matching files. If you don't
   speak FR/ES, ask the user to provide — **do not invent**.
3. Use the `transloco` pipe (`{{ 'key' | transloco }}`) or
   `TranslocoDirective` in the template; never hard-code clinical
   strings.

Keys are kebab-case and namespaced by feature:
`appointments.list.empty-state.title`, etc.

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

## Reference files

- `hospital-portal/src/app/` — feature modules
- `hospital-portal/src/app/shared/a11y/` — accessibility primitives
- `hospital-portal/src/app/auth/` — interceptors + guards
- `hospital-portal/src/assets/i18n/` — translation files
- `hospital-portal/src/styles/_tokens.scss` — design-token source of truth
- `hospital-portal/src/styles/_a11y.scss` — global a11y tokens
- `hospital-portal/e2e/keyboard-nav.spec.ts` — keyboard contract enforcement
- `docs/ui/accessibility.md` — WCAG 2.1 AA keyboard contract
- `scripts/i18n/check-completeness.js` — the FR-completeness gate
- `.github/workflows/frontend-ci.yml` — CI wiring
