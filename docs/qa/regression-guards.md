# Regression guards for the recurring QA bug classes

Nine QA rounds (`docs/qa/dev-qa-run-*.md`) kept re-finding the same classes of
defect on different screens. Fixing the reported screen fixed one instance;
the class came back on the next screen a round later. This page lists the
guards that now read the **whole tree** for each class, what each one catches,
the allowlist it carries, and how a fix branch shrinks that allowlist.

The rule for every guard here: it must pass on `develop` as it stands, so it
carries an explicit list of the sites that violate it today, each with the QA
defect id (or a one-line reason a person wrote). **Anything not on the list
fails the build.** When a fix lands, its line is deleted in the same PR — and
the guard also fails when a listed site has disappeared, so a list can never
quietly outlive what it excused.

Every guard was falsified before it shipped: a real violation was injected
into the tree, the guard failed and named the file and line, the probe was
reverted (PR body of the guard PR records the runs).

---

## Backend (hospital-core, run by the normal JUnit suite)

| Guard | Class it stops | QA defects | Allowlist today |
|---|---|---|---|
| `architecture/SaveThenRefreshGuardTest` | `repository.save(x)` followed by `entityManager.refresh(x)` before the flush — the refresh re-reads the row and **discards the pending update** while the endpoint returns 200. | D923 | 1 file (`AppointmentServiceImpl`, the D923 site) |
| `architecture/PostCommitLazyAssociationGuardTest` | Walking a lazy association (`.getRole().getCode()`, `.getHospital().getName()`) where there is no persistence context: a controller method, a `TransactionCallbacks.afterCommit` lambda, a `@TransactionalEventListener`, an interceptor's `afterCompletion`. | D921, #564 | post-commit code: **0** (must stay 0); controllers: 16 chains in 7 files, the two D921 chains tagged |
| `architecture/UpdateHandlerServiceTestCoverageTest` | A `@PutMapping`/`@PatchMapping` handler whose service method no test names at all — an update nobody has watched round-trip — or that calls no `*Service` at all (`-> <none>`: it writes through a repository, so only a controller IT can cover it). (Weak on purpose: D923 *had* a mock test. The real guard for an endpoint is a round-trip IT; this makes sure none is completely dark.) The scan asserts it found one handler per mapping annotation, so a new annotation shape cannot hide a handler. | D923 class | see the `ALLOWED` map in the test |
| `i18n/ResponseProseGuardTest` | An English sentence written straight into a response body: `new MessageResponse("…")`, `ResponseEntity….body("…")`, `Map.of("message", "…")`. The global handler never touches these, so they are English in every language. Per **site** (a file cannot swap one sentence for another), the complement of the per-file exception ratchet. | D940, D977, D934, D1000 class (25 reports) | 28 literals in 7 controllers |
| `i18n/StoredTextProseGuardTest` | English literals composed into text the backend **stores** or returns as data: `.notes("Pain scale: " + …)`, `.append("[Medication Reconciliation] ")`, `--- Nursing Intake ---`, `.reason("Medication out of stock…")`, a `: "Walk-in"` fallback, the `"Lab result"` title fallback. Persisted, so read in English for good. Audit descriptions and log lines are exempt (house decision: the audit log is English). | D952, D989, D944, D1000, D958 | 27 literals in 12 services |

### Already in place before round 16 (extend these, do not duplicate)

| Guard | Class |
|---|---|
| `i18n/ExceptionProseRatchetTest` | English in `BusinessException`/`IllegalArgumentException`/… — per-file count ratchet (`src/test/resources/i18n/exception-prose-baseline.properties`). D940's and D934's sentences are inside its baseline: **lower the file's count when you key them.** |
| `i18n/MessageBundleParityTest` | en/fr/es backend bundles carry identical key sets and placeholders; every keyed exception names a key that exists. |
| `security/tenant/ActingScopeCoverageTest`, `ServiceWriteTenantLoadCoverageTest`, `service/recordaccess/CrossHospitalReadFilterCoverageTest` | Queries and writes that use something other than the request's effective hospital (the D1011 class). Add new scope helpers to their `TENANT_CHECKS` lists rather than writing a new scan. |
| `architecture/StatusRefusalLabelGuardTest` | A refusal that names a status by its raw code. |
| `db/MigrationRegistrationTest`, `EntitySchemaValidationIT` | Unregistered migration / entity–schema drift. |

### How a fix branch shrinks a backend allowlist

1. Fix the site (key the sentence, drop the refresh, pass ids, …).
2. Run the guard: it fails with `the site was fixed: delete its line`.
3. Delete that `Map.entry(...)` (or lower the `Allowance` count) in the test.
4. Never add a line to make a build green without the defect id or a reason
   a reviewer can check.

---

## Portal (hospital-portal, `npm run guards`, one CI step "Round-16 regression guards")

All six share one shape (`scripts/lib/baseline.mjs`): a scan produces findings
with a stable id `file::rule::anchor` (never a line number), and
`scripts/<gate>-baseline.json` pins how many such sites exist today and why.

| Gate (`npm run …`) | Class it stops | Rules | QA defects | Pins today |
|---|---|---|---|---|
| `guard:a11y-dialogs` | A modal that is not a dialog to assistive technology. | `role` (class names it a modal, no `role="dialog"` on it, an ancestor or a descendant, including a child component that is itself a dialog), `modal` (no `aria-modal`), `label` (no `aria-labelledby`/`aria-label`), `escape` (component has no Escape handler) | D976, D988, D1004, D968 (D173 earlier) | 188 (83 role, 81 label, 12 modal, 12 escape) |
| `guard:a11y-names` | A control with no accessible name. | `icon-button` (a `<button>`, `<a (click)>` or `role="button"` whose content minus icons is empty and that has no `aria-label(ledby)`; `title` alone does not count), `field-label` (`<input>`/`<textarea>`/`<select>` with no `aria-label`, no `<label for>`, no enclosing `<label>`) | D988, D999, D1006, D993 (D173/D200 earlier) | 378 sites / 269 pins (227 icon buttons, 42 fields) |
| `guard:patient-picker` | A hand-written patient picker (search input + own suggestion list with a `selectPatient(...)` handler) instead of `<app-patient-picker>`. | `hand-written-picker` | D947 (D943/D942 as seen from each copy) | 11 |
| `guard:money-format` | Money printed without `formatCfa` / `\| cfa`. | `pipe` (`\| number` / `\| currency` on an amount-shaped field), `helper` (a component `formatCurrency/Money/Amount/Price` not delegating to `formatCfa`, or Intl currency formatting outside one), `fixed` (`.toFixed(` on an amount) | D948, D1008, D621 | 6 |
| `guard:ts-literals` | An English sentence handed to a toast or a status signal from component code — the layer the locale gates cannot see. | `toast`, `signal` | D940 class | 4 (all in `reset-password.ts`, pre-existing, not yet a QA defect) |
| `guard:uuid-fragments` | A UUID fragment on screen (`id \| slice: 0 : 8`, `.substring(0, 8)`). | `slice-pipe`, `slice-call` | D992 (D1009 for file names) | 6 |

Each gate accepts `--report-only` (print, never fail) and `--write-baseline`
(rewrite the pins from the tree — **read the diff**, it is the list of what you
are excusing; `why` of existing pins is kept, new pins get `TODO`).

### Already in place before round 16

| Gate | Class |
|---|---|
| `i18n:raw-enums` (`scripts/i18n-raw-enums-baseline.json`, 203 pins) | An enum-shaped field rendered without `\| enumLabel`. **D950 (`m.route`, `task.route`) and D963 (`lab.html::<option>p`, `o.orderChannel`) are pins in that file: the fix is to pipe them and delete the pins**, not a new guard. |
| `i18n:parity`, `i18n:referenced`, `i18n:translated`, `i18n:enums`, `i18n:placeholders` | Locale file completeness (see memory note "French completeness layers"). |
| `rbac:inline-roles` | Inline `'ROLE_…'` lists. |
| `scripts/lib/shell-a11y.test.mjs` | The shell's own icons/links/search boxes (D173/D200) — now generalised by `guard:a11y-names`. |
| `src/app/shared/i18n/app-locale.spec.ts` | `formatCfa`. **D1008 is a pending spec there (`xit`)**: flip it to `it` in the PR that passes `useGrouping: 'always'`. |

### How a fix branch shrinks a portal baseline

1. Fix the site (add `role="dialog" aria-modal="true" aria-labelledby`, name
   the button, use `<app-patient-picker>`, `\| cfa`, the human code…).
2. Run `npm run guards`: the gate reports `stale pin: <id> (pinned N, found
   N-1)`.
3. Lower `count` or delete the entry in `scripts/<gate>-baseline.json`.
4. A pin's `why` must be a QA defect id or a reason a reviewer can check;
   `--write-baseline` is for reading what changed, not for making CI green.

---

## What is not guarded (and why)

- **Bean Validation default messages** (D925 "providerName: must not be
  blank"): the English comes from `FieldError.getDefaultMessage()` in
  `GlobalExceptionHandler#handleValidationException`; a scan cannot see it,
  the fix is in the handler (resolve the constraint's message in the request
  locale). One site, not a class.
- **A true round-trip test per update endpoint** (D923's real cure): not
  generable. `UpdateHandlerServiceTestCoverageTest` only ensures no update
  endpoint is entirely untested; `SaveThenRefreshGuardTest` catches the one
  mechanism D923 had.
- **Single English words** in stored text ("Unknown", "Walk-in"): only the
  `: "Capitalised"` ternary fallback shape is caught; a bare
  `setReason("Pending")` is indistinguishable from a code without a type
  model.
- **Textual scans read comments too**: a Javadoc that spells
  `new MessageResponse("Logged out successfully.")` fails the response guard.
  This matches the exception ratchet's behaviour and is accepted; reword the
  comment.

---

## Regression pack for every future QA round

A round that re-tests only the defect list re-finds the class on the screens
the list did not name. From round 17 on, **every round re-runs every area
script, not only the defects under re-test**:

```text
C:\dev\hms-qa\scripts\r16\preflight\pre.js
C:\dev\hms-qa\scripts\r16\patient-portal\*.js
C:\dev\hms-qa\scripts\r16\reception-frontdesk\*.js
C:\dev\hms-qa\scripts\r16\nurse-triage-inpatient\*.js
C:\dev\hms-qa\scripts\r16\doctor-outpatient\*.js
C:\dev\hms-qa\scripts\r16\lab-pharmacy\*.js
C:\dev\hms-qa\scripts\r16\maternity-pediatrics\*.js
C:\dev\hms-qa\scripts\r16\billing-cashier\*.js
```

and, before the round starts, the whole static pack on the build under test:

```bash
cd hospital-core && ../gradlew test --tests 'com.example.hms.architecture.*' --tests 'com.example.hms.i18n.*'
cd hospital-portal && npm run guards && npm run i18n:raw-enums && npm run i18n:translated
```

A defect found by a round that one of these guards *should* have caught is a
guard bug: extend the scan (and its `.test.mjs` / "the scan catches the old
shapes" self-test) in the same fix PR, so the class cannot slip past it again.
