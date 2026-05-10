# Accessibility (a11y) — HMS

> v1.0 roadmap row 11. Pair this doc with the axe-core smoke at
> [hospital-portal/e2e/a11y.spec.ts](../../hospital-portal/e2e/a11y.spec.ts) and the
> keyboard-nav Playwright suite at
> [hospital-portal/e2e/keyboard-nav.spec.ts](../../hospital-portal/e2e/keyboard-nav.spec.ts).
> Together they form HMS's WCAG 2.1 AA gate.

## 1. Why this matters for HMS

HMS is deployed in mixed-context hospitals — public sector (Burkina Faso, Côte
d'Ivoire, Senegal, Ghana), private clinics, and partner referral facilities.
Realistic constraints we design for:

- **Keyboard-first staff.** Receptionists and pharmacy/lab data clerks often
  trained on legacy text-mode systems; many will never touch the mouse during a
  shift. Every clinical-flow screen must be 100% reachable and actionable with
  the keyboard alone.
- **Shared workstations.** Multiple users share one PC across shifts. Focus
  management on route change matters so screen-reader users — and any user who
  lost their place after the previous user signed out — know where they are.
- **Power cuts.** Power outages cause cold reloads mid-task. The first focusable
  element on every screen must be predictable so users can resume without
  scrolling visually.
- **AZERTY keyboards.** Francophone West Africa uses AZERTY. We avoid
  single-letter shortcuts because the physical key positions differ from QWERTY
  in ways that break muscle memory (A↔Q, Z↔W, M position). All shortcuts must
  carry a modifier (Alt, Ctrl, or Meta).
- **Low-bandwidth assistive tech.** NVDA + Firefox is the cheapest screen-reader
  combo and the one we test against. JAWS is licensed; we don't assume it.

## 2. Conformance target

**WCAG 2.1 Level AA.** Tested automatically by:

| Layer                | Tooling                                                              | Where               |
| -------------------- | -------------------------------------------------------------------- | ------------------- |
| Axe rule scan        | `@axe-core/playwright` against critical clinical-flow surfaces       | `e2e/a11y.spec.ts`  |
| Keyboard reachability| Playwright keyboard-only navigation, asserts on `document.activeElement` | `e2e/keyboard-nav.spec.ts` |
| Lint                 | `@angular-eslint/template/*` a11y rules + ESLint a11y plugin         | `eslint.config.mjs` |

Both Playwright suites run in the `chromium` (dev-server) project on every PR
via [.github/workflows/frontend-ci.yml](../../.github/workflows/frontend-ci.yml).

## 3. The keyboard contract

Every interactive element MUST satisfy all of the following:

1. **Reachable** by `Tab` / `Shift+Tab` in DOM order, with no positive
   `tabindex` values. Use only `tabindex="0"` (make focusable) or `tabindex="-1"`
   (focusable by script only — e.g. a programmatic landing target).
2. **Activatable** by `Enter` on `<a>` and by `Enter` _or_ `Space` on
   `<button>`. Custom widgets must replicate this — `role="button"` plus
   keyboard handler is a smell; prefer a real `<button>`.
3. **Has a visible focus indicator** that meets WCAG 2.4.7 — a 2px outline at
   minimum 3:1 contrast against the surrounding background. The global token is
   `--focus-ring` (see `src/styles.scss` and `_page-common.scss`). Do not
   suppress focus rings with `outline: none` unless a substitute is provided.
4. **Has an accessible name.** `<label for>` for inputs, `aria-label` /
   `aria-labelledby` for icon-only buttons, alt text for meaningful images and
   `aria-hidden="true"` for purely decorative ones.
5. **Dismissable.** Any modal, overlay, dropdown, or warn-card must close on
   `Escape`. CDS Hooks warn-cards specifically — clinicians get a lot of them
   during prescribing and must be able to acknowledge them without breaking flow.

## 4. Focus order rules

- **One `<main id="main-content">` per page.** The shell renders this; child
  routes do not. The skip-link target is `#main-content`.
- **On route change, focus moves to `<main>`.** Implemented in
  [shell.ts](../../hospital-portal/src/app/shell/shell.ts) via `Router.events`
  → `NavigationEnd` → `mainRef.nativeElement.focus()` with `tabindex="-1"`.
  This is non-negotiable; without it screen readers stay on the trigger of the
  previous click.
- **First focus on each screen** is the screen's primary action or input:
  - **Login** → username input
  - **Reception cockpit** → patient-search input
  - **Patient form** → first name input
  - **Nurse station / triage** → first vitals field (systolic BP)
  - **Prescriptions** → medication search
  - **Pharmacy dispensing** → next-in-queue prescription row
  - **Patient tracker** → first filter chip (or first row if no filters)
- **Forms move focus to the first invalid field on submit.** Use
  `[appFocusOnError]` from
  [src/app/shared/a11y/focus-on-error.directive.ts](../../hospital-portal/src/app/shared/a11y/focus-on-error.directive.ts).
- **Dialogs trap focus.** Tab cycles within the dialog; `Escape` closes it and
  returns focus to the element that opened it.

## 5. Shortcuts

We deliberately keep the shortcut surface small. Currently defined:

| Shortcut         | Action                          | Implementation                              |
| ---------------- | ------------------------------- | ------------------------------------------- |
| `Tab` / `Shift+Tab` | Move forward / back through focusable elements | Native                              |
| `Enter`          | Activate link / submit form     | Native                                      |
| `Space`          | Activate button / toggle checkbox | Native                                    |
| `Escape`         | Close modal / dismiss CDS warn-card / close profile + notification panels | Per-component `(keydown.escape)` |
| `Arrow Down / Up` | Move between rows in patient-tracker board, nurse-station vitals grid, in-basket list | Roving `tabindex` (planned) |

**Avoid single-letter shortcuts** (`g`, `n`, etc.). On AZERTY the physical
positions of A/Q, Z/W and M shift, breaking muscle memory for francophone WA
staff. If a single-key shortcut is unavoidable, register it with a modifier
(`Alt+P`, `Ctrl+/`) and surface it in the in-app shortcut help (TBD).

## 6. Screen-by-screen contract (clinical flow)

Mirrors the realistic West African patient journey covered by
`e2e/keyboard-nav.spec.ts`:

### 6.1 Reception / front desk (`/reception`, `/patients/new`)
- Patient-search input is autofocused on mount.
- `Enter` on a search result row opens the patient detail without a mouse.
- "New patient" CTA is reachable via 1 `Tab` from the search input.
- New-patient form: tab order follows visible label order (top-to-bottom,
  left-to-right). Required-field violations move focus to the first invalid
  field on submit and announce via `aria-live="polite"` on the error banner.

### 6.2 Triage / vitals (`/nurse-station`)
- Vitals grid: arrow keys move between BP-systolic → BP-diastolic → temp →
  pulse → weight cells (roving `tabindex`). `Tab` exits the grid to the
  "Save vitals" button.
- Triage-priority radio group navigable via arrow keys per WAI-ARIA radio
  pattern.

### 6.3 Doctor consultation (`/consultations`, `/encounters`)
- Patient picker is autofocused.
- Encounter notes `<textarea>` is reachable via `Tab` — never trapped.

### 6.4 Orders (`/prescriptions`, `/lab`, `/imaging`)
- Medication / test search is autofocused on entering the order form.
- CDS Hooks warn-cards (drug-drug interaction, allergy) appear with
  `role="alertdialog"` and are dismissable via `Escape`. Focus moves into
  the warn-card on render and returns to the search field on dismiss.
- "Sign order" button is the last item in tab order to prevent accidental
  signing.

### 6.5 Pharmacy dispensing + checkout (`/pharmacy/dispensing`, `/pharmacy/checkout`)
- Next-in-queue row is autofocused. `Enter` opens it.
- Dispense form: lot number → quantity → expiry → "Dispense" in tab order.
- Offline-queue banner (added in T-68) is announced via `aria-live="polite"`
  when the connection drops.
- Checkout dialog: payment-method radio group (cash / mobile money / insurance)
  navigable via arrow keys.

### 6.6 Patient tracker board (`/patient-tracker`)
- Filter chips: first chip autofocused, arrow keys move between chips per
  WAI-ARIA tabs pattern, `Enter` toggles.
- Patient rows: `Tab` to first row, then arrow keys row-to-row (roving
  `tabindex`), `Enter` opens the patient drawer. `Escape` closes the drawer
  and returns focus to the row.

### 6.7 Discharge / AVS (`/my-summaries`, `/my-medications`)
- Heading hierarchy: one `<h1>` per page, `<h2>` per summary card. Screen
  readers can navigate by heading.
- Print button reachable via `Tab` from the page heading.

### 6.8 Login (`/login`)
- Username input autofocused on mount when the standard login form is shown.
- `Enter` in either username or password submits.
- Forgot-password and forgot-username dialogs: focus moves into the dialog
  on open, traps within it, returns to the trigger link on close.
- Login error banner uses `role="alert"` so screen readers announce it
  without the user having to navigate to find it.

### 6.9 Global shell (`<app-shell>`)
- Skip-link is the first focusable element. `Tab` from anywhere off-page
  reveals it; `Enter` jumps to `<main id="main-content">`.
- Sidebar nav: `Tab` enters the nav, arrow keys move between items
  (planned), `Enter` activates. The drag-handle is keyboard-inert; we will
  add an "Alt+Arrow Up/Down" keyboard alternative for reordering before
  v1.0.0 GA.
- Profile menu and notification panel: opening either moves focus to the
  first item; `Escape` closes and returns focus to the trigger button.

## 7. ARIA landmarks

Every page is contained by the shell, which provides:

```
<header> (topbar) — role="banner"
<aside>  (sidebar) — role="navigation" aria-label="Primary"
<main id="main-content" tabindex="-1"> — role="main"
```

Child screens MUST NOT add a second `<main>`. Use `<section>` with an
`aria-labelledby` heading instead.

## 8. Color contrast

The HMS palette is built on tokens defined in `src/styles.scss`. The
following non-token greys are forbidden in user-facing text because they
fail 4.5:1 against `#fff` background:

- `#9ca3af` on white → 2.84:1 ❌
- `#94a3b8` on white → 2.83:1 ❌
- `#cbd5e1` on white → 1.85:1 ❌

Replacements (use these for body / label / placeholder text):

- Body text → `#1e293b` on white → 14.7:1 ✓
- Secondary text → `#475569` on white → 7.55:1 ✓
- Tertiary / helper text → `#64748b` on white → 5.49:1 ✓
- Placeholder / disabled → `#6b7280` on white → 5.0:1 ✓

Status-badge backgrounds (success-green `#ecfdf5`, error-red `#fef2f2`,
warning-amber `#fffbeb`, info-blue `#eff6ff`) must use the paired darker
foreground tokens (`#065f46`, `#991b1b`, `#92400e`, `#1e40af`). All four
pairs are verified ≥ 7:1 (AAA).

## 9. Maintainer checklist

Before requesting review on any frontend PR that adds or modifies a screen:

- [ ] Page is keyboard-navigable from `Tab` press 1 to the final action,
      without resorting to the mouse.
- [ ] Visible focus indicator on every focusable element (verify in
      Chrome devtools: `:focus-visible` rule applies).
- [ ] First focusable element on the screen is the primary user task
      (search input, first form field, etc.) — not the logo or sidebar.
- [ ] No `tabindex` > 0.
- [ ] All inputs have associated `<label for>` or `aria-label`.
- [ ] All icon-only `<button>` elements have `aria-label`.
- [ ] All purely decorative SVG/icons are `aria-hidden="true"`.
- [ ] Any modal / dropdown closes on `Escape` and traps focus while open.
- [ ] Form errors announce via `aria-live` or `role="alert"`.
- [ ] Form submit moves focus to first invalid field (use
      `[appFocusOnError]`).
- [ ] `npm run lint` is clean (template a11y rules included).
- [ ] `npx playwright test e2e/a11y.spec.ts e2e/keyboard-nav.spec.ts`
      passes locally.

## 10. Known gaps tracked for v1.0.0 GA

- Sidebar drag-to-reorder has no keyboard alternative. Planned: `Alt+ArrowUp`
  / `Alt+ArrowDown` while a nav item is focused.
- The dashboard's drag-and-drop widget grid (if added by row 13 polishing)
  will need the same treatment.
- Patient-tracker filter chips currently use plain buttons; the WAI-ARIA
  tabs pattern (arrow-key navigation, `aria-selected`) is on the v1.1 list.

## 11. Test contract

The CI gate fails if either of:

1. `e2e/a11y.spec.ts` reports any `serious` or `critical` axe violation on
   the routes listed there. (The `color-contrast` rule was previously
   suppressed; row 11 removed that suppression — re-suppressing it must
   be accompanied by a ticket and a written exception.)
2. `e2e/keyboard-nav.spec.ts` cannot complete the keyboard-only journey,
   or finishes with `document.activeElement` on an unexpected element.

Local commands:

```bash
cd hospital-portal
npx playwright test e2e/a11y.spec.ts
npx playwright test e2e/keyboard-nav.spec.ts
```

## 12. References

- [WCAG 2.1 AA quick reference](https://www.w3.org/WAI/WCAG21/quickref/?versions=2.1&levels=aa)
- [WAI-ARIA Authoring Practices](https://www.w3.org/WAI/ARIA/apg/)
- [Deque axe-core rules](https://dequeuniversity.com/rules/axe/)
- [NVDA basic shortcuts](https://www.nvaccess.org/files/nvda/documentation/keyCommands.html)
