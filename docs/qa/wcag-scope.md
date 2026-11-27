# Accessibility scope for go-live (WCAG 2.1 AA)

Go-live scorecard line **P5**: « Main journeys pass keyboard, accessibility
(agreed WCAG AA scope), mobile layout and form-validation checks. »

This document is that agreed scope for the staff and patient **web portal**
(`hospital-portal`). It says which pages are in, which success criteria are
checked and how, what is put off and why. The keyboard contract itself (focus
order, shortcuts, landmarks, colour tokens) stays in
[docs/ui/accessibility.md](../ui/accessibility.md).

Status: proposed by engineering on 26 November 2026, to be confirmed by the
product owner. The two native patient apps are **not** covered here (§7).

## 1. Target

- **Standard:** WCAG 2.1, levels A and AA.
- **Language of test:** French (the default locale), on the production bundle.
- **Browser of record:** Chromium. Firefox and WebKit are spot-checked by QA
  rounds, not by the automated gate.
- **Assistive technology of record:** NVDA with Firefox on Windows (the free
  combination our hospitals can afford). VoiceOver and TalkBack are for the
  patient apps and are out of this scope.

## 2. Supported widths

| Width | Stands for | What must hold |
|---|---|---|
| 390 px | a phone held upright | no sideways page scroll, the menu is a drawer, tables read as cards, touch targets are at least 24 px |
| 768 px | a tablet held upright, a narrow laptop window | no sideways page scroll, the menu is still a drawer |
| 1440 px | a desk screen | the menu is a landmark beside the page |

Reflow at 320 px (SC 1.4.10 to the letter) is put off: see §6.

## 3. Pages in scope

The pages of the ten main journeys, each tested **as the role that works on
it**. This table and `hospital-portal/e2e/helpers/wcag-journey-pages.ts` are
kept identical by `hospital-portal/scripts/wcag-journey-scope.test.mjs`: add a
page to both or to neither.

| Journey | Role | Pages |
|---|---|---|
| J1 Sign-in, second factor, account setup | signed out, then any staff role | `/login`, `/mfa-challenge`, `/mfa-enroll`, `/reset-password`, `/verify`, `/account-setup` |
| J2 Reception: registration, booking, check-in | receptionist | `/reception`, `/patients`, `/patients/new`, `/appointments`, `/appointments/new`, `/appointments/calendar`, `/registrations` |
| J3 Triage and vitals | nurse | `/nurse-station`, `/patient-tracker` |
| J4 Consultation note and prescription | doctor | `/consultations`, `/encounters`, `/prescriptions` |
| J5 Lab order to result release | doctor, lab technician, lab scientist | `/lab`, `/lab-results`, `/lab-approval-queue`, `/lab-release-worklist` |
| J6 Pharmacy dispense and hand-over | pharmacist | `/pharmacy/dispensing`, `/pharmacy/checkout`, `/pharmacy/caisse` |
| J7 Billing: invoice, payment, till close | accountant | `/billing`, `/billing/caisses` |
| J8 Nurse medication round | nurse | `/emar` |
| J9 Maternity: labour and delivery | midwife | `/maternity` |
| J10 Patient portal records | patient | `/my-records`, `/my-visits`, `/my-medications`, `/my-lab-results`, `/my-imaging`, `/my-vitals`, `/my-documents`, `/my-appointments`, `/my-billing` |

38 pages. `/mfa-challenge`, `/mfa-enroll` and `/account-setup` cannot be opened
by their address: the test reaches them by signing in on `/login` against a
mocked answer, as a user would.

Every other routed page (about 150) keeps the wider but shallower gate that
already existed: `e2e/a11y-routed-pages.ts` scans each one at one desktop width
and fails on serious and critical WCAG A/AA violations.

## 4. Success criteria in scope

"Gate" is what fails a pull request. All gates run in the `a11y-smoke` job of
`.github/workflows/frontend-ci.yml`.

| Criterion | What we check | Gate |
|---|---|---|
| 1.1.1 Non-text content | images and icon-only buttons have a name; decorative icons are hidden | axe (`e2e/wcag-scope.spec.ts`, `e2e/a11y-routed-pages.ts`), `guard:a11y-names` |
| 1.3.1 Info and relationships | labels tied to fields; one `main`, one `banner`, a named navigation; no content outside a landmark; no two landmarks with one name | axe WCAG rules plus `landmark-one-main`, `landmark-no-duplicate-main`, `landmark-no-duplicate-banner`, `landmark-unique`, `region` on the scope pages; `scripts/wcag-journey-scope.test.mjs` (every page outside the shell has a `<main>`) |
| 1.3.5 Identify input purpose | `autocomplete` on sign-in and identity fields | axe `autocomplete-valid` |
| 1.4.1 Use of colour | status is never colour alone (badge text, icons) | axe `link-in-text-block`; QA visual check |
| 1.4.3 Contrast (minimum) | 4.5:1 for text, on every routed page | axe `color-contrast`; `guard:design-tokens` |
| 1.4.4 Resize text | 200 % zoom on the scope pages | manual, each QA round (§5) |
| 1.4.10 Reflow | no sideways page scroll at 390, 768 and 1440 px | `e2e/wcag-scope.spec.ts`; `e2e/layout-overflow.spec.ts` (every routed page at 1366 / 1024 / 768 / 390) |
| 1.4.11 Non-text contrast | focus ring and field borders at 3:1 | design tokens (`--focus-ring`); manual |
| 1.4.12 Text spacing | no clipped text with user spacing | `e2e/layout-overflow.spec.ts` (spilling text); manual |
| 2.1.1 Keyboard, 2.1.2 No keyboard trap | every journey can be completed without a mouse; a dialog keeps Tab inside and lets Escape out | `e2e/wcag-scope.spec.ts` (entry dialogs), `e2e/a11y-main-refusals.ts`, `e2e/a11y-keyboard-care.ts`, `e2e/keyboard-nav.spec.ts`, `guard:a11y-dialogs` |
| 2.4.1 Bypass blocks | the skip link is the first Tab stop of every signed-in page | `e2e/wcag-scope.spec.ts` |
| 2.4.2 Page titled, 2.4.6 Headings and labels | one visible `h1` in `main` | axe `document-title`, `page-has-heading-one`; `e2e/a11y-routed-pages.ts` (one title style) |
| 2.4.3 Focus order | no positive `tabindex`; focus never lands on something off screen; a closed dialog gives focus back to its opener | `e2e/wcag-scope.spec.ts` |
| 2.4.7 Focus visible | each of the first twelve Tab stops of a page shows an outline or a halo | `e2e/wcag-scope.spec.ts` |
| 2.5.3 Label in name | the visible label is part of the accessible name | axe `label-content-name-mismatch` is experimental: manual |
| 3.1.1 Language of page | `lang` follows the chosen locale | axe `html-has-lang`, `html-lang-valid` |
| 3.2.1 / 3.2.2 On focus, on input | no navigation or submit on focus or on a select change | manual; `guard:form-behaviour` |
| 3.3.1 Error identification, 3.3.3 Error suggestion | a refused field is marked `aria-invalid`, takes focus, and is described by a `role="alert"` message through `aria-describedby` | `e2e/a11y-main-refusals.ts`, `e2e/a11y-keyboard-care.ts`, `guard:required-refusal` |
| 3.3.2 Labels or instructions | every field has a label; required fields are marked | axe `label`, `select-name`; `scripts/accessible-input-labels.test.mjs` |
| 4.1.2 Name, role, value | dialogs are `role="dialog"`, `aria-modal`, named; controls expose their state | axe; `e2e/wcag-scope.spec.ts` (entry dialogs); `guard:a11y-dialogs` |
| 4.1.3 Status messages | toasts and banners use `role="status"` or `role="alert"` | `e2e/a11y-main-refusals.ts`; manual with NVDA |

**Agreed extra, beyond 2.1 AA.** Touch targets of at least 24 × 24 px at 390 px
(WCAG 2.2 SC 2.5.8, axe `target-size`). WCAG 2.1 only has the 44 px AAA
criterion; our nurses and patients use phones, so we adopt the 2.2 AA floor now.

**Blocking level.** On the scope pages: every serious or critical WCAG A/AA
violation, **plus** every violation (any impact) of the landmark, heading and
target rules listed above. Moderate and minor findings of other rules are
attached to the test report (`journey-audit`), not blocking.

## 5. Smoke expectations for a QA round

Automation proves structure. A person proves that the journey can actually be
done. Each full QA round runs these two smokes on J1 to J10, in French, and
records a pass or a defect per journey.

**Keyboard only (no mouse, Chromium, 1440 px and 390 px).**

1. The first Tab shows « Aller au contenu principal »; Enter moves to the page.
2. Every control of the journey is reached with Tab or Shift+Tab, in reading
   order, and the focused control is always visible on screen.
3. Each action works with Enter or Space; each dialog opens with focus inside,
   keeps Tab inside, closes with Escape and gives focus back to its opener.
4. Submitting an empty form moves focus to the first refused field.
5. The journey's outcome is reached: the patient is registered, the dose is
   recorded, the invoice is paid, the till is closed.
6. On a phone width, the menu button opens the drawer, and a closed drawer
   takes no Tab stop.

**Screen reader (NVDA with Firefox, 1440 px).**

1. The page title and the `h1` are announced on arrival, in French.
2. The landmark list (NVDA+F7) shows one banner, one named navigation, one main.
3. The headings list gives the outline of the page; no icon name is spoken
   (« calendar_month », « unfold_more »).
4. Each field is announced with its label, « obligatoire » when required, and
   its error text when refused.
5. A toast or banner after an action is announced without moving focus.
6. A table is announced with its column headers; on a phone width each card
   reads « label, value ».

Zoom check, same round: 200 % browser zoom at 1440 px on one page per journey;
nothing is cut and nothing needs sideways scrolling.

## 6. Put off, and why

| Item | Reason | When |
|---|---|---|
| WCAG 2.1 level AAA (contrast 7:1, 44 px targets, 2.4.8 to 2.4.10) | not a target; several AAA criteria conflict with dense clinical screens | no date |
| WCAG 2.2 criteria other than 2.5.8 (2.4.11 focus not obscured, 3.3.7 redundant entry, 3.3.8 accessible authentication) | the agreed standard is 2.1; 2.2 needs its own audit of sticky bars and of the second-factor step | after go-live |
| 1.2.x captions, audio description | the portal has no audio or video content | when media is added |
| 1.4.5 Images of text, 1.4.13 Content on hover or focus | not automatable; tooltips are short `title` text | manual audit after go-live |
| 1.4.10 Reflow at 320 px | 390 px is the agreed phone width (§2); 320 px has never been tested | if a hospital reports a narrower device |
| 2.5.1, 2.5.2, 2.5.4 pointer gestures, cancellation, motion | no path-based gesture or motion input exists; drag to reorder the menu has a keyboard alternative (Alt+Arrow) | re-check when a gesture is added |
| 2.2.1 Timing adjustable | the idle sign-out is a security control; whether it warns and lets the user extend is reviewed with the session work rather than here | with the session work (#1350) |
| 3.1.2 Language of parts | patient names and drug names inside French text are not marked | no date |
| Populated lists | the automated pages render against empty mocked lists: a row's badges, row actions and pagination are scanned only where a suite seeds them (billing payment, dispensing, imaging report, patient tracker). Seeding one realistic row per scope page is the next step | next accessibility change |
| `/account-setup` main landmark | the page has no `<main>`; its template is being edited by #1345. Listed as `pending` in `wcag-journey-pages.ts`, reported but not blocking | when #1345 merges |
| Firefox and WebKit in the gate | one browser keeps CI under its time budget; QA rounds cover the others (r44) | no date |
| Real screen-reader run | never done on a full journey; §5 defines it | next full QA round |
| Patient apps (Android, iOS), a real phone | separate codebases and tools (TalkBack, VoiceOver) | their own scope, before store release |

## 7. What this scope does not claim

- Passing the automated gate is not a WCAG conformance statement. It shows
  that the listed rules hold on the listed pages in their first state.
- P5 also asks for the patient apps on a real phone. That part stays open.
- No external audit has been done.

## 8. How to run

From `hospital-portal/`:

```bash
npm run build
PLAYWRIGHT_PREBUILT=1 npm run e2e:wcag-scope      # the scope pages, 390 / 768 / 1440
PLAYWRIGHT_PREBUILT=1 npm run e2e:main-journeys   # routed scan, refusals, keyboard care
npm run test:scripts                              # includes wcag-journey-scope.test.mjs
```

Each test attaches a `journey-audit` JSON with every axe finding, blocking or
not, to the Playwright report.
