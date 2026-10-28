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
| `i18n/ResponseProseGuardTest` | An English sentence written straight into a response body: `new MessageResponse("…")`, `ResponseEntity….body("…")`, `Map.of("message", "…")` — as a literal or through a `static final String` constant declared in the file. The global handler never touches these, so they are English in every language. Per **site** (a file cannot swap one sentence for another), the complement of the per-file exception ratchet. | D940, D977, D934, D1000 class (25 reports) | 33 sentences at 43 sites in 7 controllers (each entry carries how many places write the sentence; a second copy of an allowed sentence fails; `Map.of` bodies are read whole, `ResponseEntity.ok("…")` too) |
| `i18n/StoredTextProseGuardTest` | English literals composed into text the backend **stores** or returns as data: `.notes("Pain scale: " + …)`, `.append("[Medication Reconciliation] ")`, `--- Nursing Intake ---`, `.reason("Medication out of stock…")`, a `: "Walk-in"` fallback, the `"Lab result"` title fallback. Persisted, so read in English for good. Audit descriptions and log lines are exempt (house decision: the audit log is English). | D952, D989, D944, D1000, D958 (and the D935/D953 CDS-card and D941 seeded-description classes) | 48 sentences at 48 sites in 18 files — the scan reads every package that can write a row (services, mappers, controllers, HL7/FHIR, CDS rules, seeders), not only `service/`; FHIR `display` text is pinned as "English by convention", not as a defect |

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
`npm run guards` is `scripts/check-guards.mjs`: it reads the templates (every
`.html` and every inline `template:`) and the `.ts` sources once, evaluates all
six gates on that, and reports every failing gate, not just the first. Each
gate also runs alone (`node scripts/check-a11y-dialogs.mjs`). The Java guards
share `testsupport/SourceScan` (balanced braces/parens through string, char and
text-block literals and comments, annotation skipping, statement starts, line
numbers) and `testsupport/GuardAllowlist` (the allowance shape — site, count,
reason — and the two assertions every guard makes: `overAllowance`, `stale`).

| Gate (`npm run …`) | Class it stops | Rules | QA defects | Pins today |
|---|---|---|---|---|
| `guard:a11y-dialogs` | A modal that is not a dialog to assistive technology. | `role` (class names it a modal, no `role="dialog"` on it, an ancestor or a descendant, including a child component that is itself a dialog), `modal` (no `aria-modal`, or `aria-modal="false"`), `label` (no non-empty `aria-labelledby`/`aria-label`), `escape` (no `(keydown.escape)` on the dialog, an ancestor or a descendant, no `document:keydown.escape` HostListener, no CDK/Material dialog — an Escape bound on a **sibling** backdrop never fires while focus is inside the dialog, and a focus trap or an `'Escape'` string elsewhere in the file is not a handler). A native `<dialog>` has the role, the modality (`showModal()`) and Escape built in: only its name is checked. | D976, D988, D1004, D968 (D173 earlier) | 190 (57 role, 104 label, 12 modal, 17 escape) |
| `guard:a11y-names` | A control with no accessible name. | `icon-button` (a `<button>`, an `<a>` with `(click)`/`routerLink`/`href`, or `role="button"`/`"link"` whose content minus icons is empty and that has no `aria-label(ledby)`; `title` alone does not count), `field-label` (`<input>`/`<textarea>`/`<select>` with no `aria-label`, no `<label for>`, no enclosing `<label>`) | D988, D999, D1006, D993 (D173/D200 earlier) | 383 sites at 369 pins (328 icon buttons/links, 55 fields) |
| `guard:patient-picker` | A hand-written patient picker (search input + own suggestion list with a `selectPatient(...)` handler) instead of `<app-patient-picker>`. | `hand-written-picker` | D947 (D943/D942 as seen from each copy) | 11 |
| `guard:money-format` | Money printed without `formatCfa` / `\| cfa`. | `pipe` (`\| number` / `\| currency` on an amount-shaped field — the last identifier is a money word or ends in a capitalised one), `raw` (`{{ r.copayAmount }}` with no pipe at all, a `?? 0`/`|| 0` fallback allowed; the identifier must carry a money stem — `balanceDue`, `amountPaid` — so `medsDue`, `boardTotal()` and `daysUntilDue` stay counts), `helper` (a component `formatCurrency/Money/Amount/Price` not delegating to `formatCfa`, or Intl currency formatting outside one), `fixed` (`.toFixed(` on an amount, through `?.`/`!.`/`(a * b)`) | D948, D1008, D621 | 7 (one of them, `eligibility-check-dialog:113`, found by the `raw` rule) |
| `guard:ts-literals` | An English sentence handed to a toast or a status signal from component code — the layer the locale gates cannot see. | `toast`, `signal` (the literal anywhere in the argument or the assigned expression, so a `?? '…'` fallback counts; comments and the dead fallback of `translate.instant(…) \|\| '…'` do not) | D940 class | 5 (all in `reset-password.ts`, pre-existing, not yet a QA defect) |
| `guard:uuid-fragments` | A UUID fragment on screen (`id \| slice: 0 : 8`, `.substring(0, 8)`, through `?.`/`!.`; the last camelCase word decides whether a name is an id — `hospitalId` is, `isValid` is not). | `slice-pipe`, `slice-call` | D992 (D1009 for file names) | 7 (one of them, `scheduling.html:418`, found by the guard itself) |

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
- **English handed to a helper that stores it** (the call-graph gap of a
  textual scan): `StoredTextProseGuardTest` sees a prose literal written
  directly into a text sink (`.notes("…")`, `.append("…")`), not one passed to
  a helper parameter the helper then stores — on develop today
  `InventoryServiceImpl.recordStockTransaction(…, "Stock receipt: lot " + lot, …)`,
  `ImagingReportServiceImpl.appendStatusHistory(…, "Report signed", …)`,
  `TransfusionServiceImpl.stopInternal(administration, "Transfusion reaction: " + …)`,
  `StockOutRoutingServiceImpl.endOrderOfEndedStay(prescription, "Partner no-show")`.
  The same gap applies to a lazy chain inside a helper method called from a
  post-commit lambda (`PostCommitLazyAssociationGuardTest`). A fix PR that
  touches one of these helpers keys the text at the helper and adds the
  helper's name to the guard's sink list.
- **Comments are stripped** before every Java scan (`SourceScan.stripComments`),
  so a Javadoc that quotes an old English sentence or a `// was
  assignment.getRole().getCode()` note neither fails a guard nor keeps an
  allowance alive. (The older `ExceptionProseRatchetTest` still reads
  comments.)

---

## How a fix PR uses these guards

The user's standing order for every D-numbered defect is the project skill
`.claude/skills/defect-fix-mandate/SKILL.md` (lands with PR #974; until it
merges, the rule is summarised here and in every guard's failure message) —
fix the **class**, not the instance; prove it with a codebase-wide guard;
prove it on the running app; a PR without the class table is not a fix. These guards are the "guard the
class" step for the classes round 16 named. The contract between a fix PR and
a guard:

1. **Every allowlist entry carries a defect id** (or a one-line reason a
   reviewer can check). The entry says *this site is known, it belongs to
   D-xxx, and it is not fixed yet*.
2. **A fix PR removes its entries.** When the site is fixed the guard fails
   with *"the site was fixed: delete its line"* / *"stale pin"*; the PR
   deletes the `Map.entry(...)` or lowers the pin's `count` in the same
   commit. The allowlist only ever shrinks.
3. **A new violation fails CI** and is never pinned to make the build green.
   Pinning is for sites that exist on `develop` today, each with its id. A
   reviewer rejects a pin without one.
4. **A guard that misses an instance is a guard bug**: extend the scan and its
   self-test (`.test.mjs` / "the scan catches the old shapes") in the fix PR.
5. Every failure message of these guards ends with a pointer to that skill.

The round-16 fix PRs and the allowlists each is expected to shrink (by area;
the fix PR's class table is the authority):

| Fix PR | Area | Allowlists it shrinks |
|---|---|---|
| #971 | encounters (nursing intake, triage, notes, timeline) | `StoredTextProseGuardTest` (D952, D989, D958 entries), `guard:uuid-fragments` (D992 pins), `guard:a11y-dialogs` / `guard:a11y-names` (triage-form, nurse-station, emar pins: D1004, D993) |
| #972 | front office (reception, appointments, registration) | `SaveThenRefreshGuardTest` (the D923 allowance — the guard then has none), `StoredTextProseGuardTest` (D944 "Walk-in"), `guard:patient-picker` (D947 pin), `guard:money-format` (D948 pins), `guard:a11y-dialogs` / `guard:a11y-names` (checkin-dialog, reception-cockpit, walkin-dialog pins: D988) |
| #973 | portal / billing (activation, pre-check-in, billing modals) | `PostCommitLazyAssociationGuardTest` (the two D921 chains), `ResponseProseGuardTest` (AuthController's D921 body, D940-class literals), `guard:a11y-dialogs` / `guard:a11y-names` (billing.html pins: D976, D1006), `app-locale.spec.ts` D1008 pinned expectation |
| orders / meds / lab (pending) | prescriptions, MAR, lab results, pharmacy | `StoredTextProseGuardTest` (D1000 stock-out reasons, D958 "Lab result"), `i18n:raw-enums` (D950 `m.route`/`task.route`, D963 `lab.html::<option>p`, `o.orderChannel` pins), `guard:a11y-dialogs` (lab-results pins: D968) |

Entries tagged "pre-existing (class …)" belong to no fix PR yet; they shrink
as pages are touched, and never grow.

---

## Regression pack for every future QA round

A round that re-tests only the defect list re-finds the class on the screens
the list did not name. From round 17 on, a round is called clean only when the
**whole pack** below has been re-run in full on the build under test — the
listed defect ids are the smallest part of it.

### 1. The static guards, before any tester signs in

```bash
cd hospital-core && ../gradlew test --tests 'com.example.hms.architecture.*' --tests 'com.example.hms.i18n.*' --tests 'com.example.hms.security.tenant.*'
cd hospital-portal && npm run guards && npm run i18n:raw-enums && npm run i18n:translated && npm run i18n:enums && npm run rbac:inline-roles
```

### 2. Every area pack under `C:\dev\hms-qa\scripts\<round>\<area>`

The round-16 packs (one per tester role; `preflight\pre.js` first):

```text
C:\dev\hms-qa\scripts\r16\preflight
C:\dev\hms-qa\scripts\r16\patient-portal
C:\dev\hms-qa\scripts\r16\reception-frontdesk
C:\dev\hms-qa\scripts\r16\nurse-triage-inpatient
C:\dev\hms-qa\scripts\r16\doctor-outpatient
C:\dev\hms-qa\scripts\r16\lab-pharmacy
C:\dev\hms-qa\scripts\r16\maternity-pediatrics
C:\dev\hms-qa\scripts\r16\billing-cashier
```

and the earlier packs, which cover areas round 16 did not re-walk (super-admin,
hospital admin, security, multi-hospital, modules, i18n/a11y crawls):

```text
C:\dev\hms-qa\scripts\setup, setup-r13            (accounts, hospitals, catalogue — run first on a wiped dev)
C:\dev\hms-qa\scripts\patient-portal
C:\dev\hms-qa\scripts\journey-outpatient
C:\dev\hms-qa\scripts\journey-inpatient
C:\dev\hms-qa\scripts\journey-maternity
C:\dev\hms-qa\scripts\journey-multi-hospital
C:\dev\hms-qa\scripts\clinical-roles
C:\dev\hms-qa\scripts\lab-pharmacy
C:\dev\hms-qa\scripts\billing
C:\dev\hms-qa\scripts\hospital-admin
C:\dev\hms-qa\scripts\super-admin
C:\dev\hms-qa\scripts\security
C:\dev\hms-qa\scripts\i18n-a11y                     (crawl.js + a11y.js over every route, FR/ES/EN)
C:\dev\hms-qa\scripts\modules\*                     (blood-pharmacy, clinical-extras, hospital-ops, integrations,
                                                     lab-modules, platform-commercial, platform-safety-governance,
                                                     reports-analytics)
C:\dev\hms-qa\scripts\smoke-956, walk-956
C:\dev\hms-qa\scripts\r9-retest … r15-retest4        (the per-round re-test packs: each holds the scripts that
                                                     proved a fix; a fix that no longer passes its own script is
                                                     a regression)
```

Each pack's `r<N>` sub-folder is that round's variant; the newest variant of a
script supersedes the older one, but a script with no newer variant is still
part of the pack.

### 3. Every "What went well" list

Each report in `docs/qa/dev-qa-run-*.md` ends its verdict with a
**"What went well"** (round 2 onwards; "What worked" in round 1) list: the
flows that passed that day. Those lists are the regression baseline — a flow
on any of them that no longer works is a regression even if no defect id
names it. A re-test walks every item of every round's list, not only the
current round's.

### 4. The sibling paths named in the fix PRs' class tables

The defect-fix mandate requires every fix PR to list the sibling instances it
found (the "Instances found" column). Those paths are part of the next
round's pack by definition: the class was fixed there without a tester ever
having seen it.

A defect found by a round that one of the static guards *should* have caught
is a guard bug: extend the scan and its self-test in the fix PR, so the class
cannot slip past it again.
