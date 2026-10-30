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
| `architecture/SaveThenRefreshGuardTest` | `repository.save(x)` followed by `entityManager.refresh(x)` before the flush — the refresh re-reads the row and **discards the pending update** while the endpoint returns 200. | D923 | **0** (the D923 site was fixed by #972 and its allowance deleted on the combined branch; must stay 0) |
| `architecture/PostCommitLazyAssociationGuardTest` | Walking a lazy association (`.getRole().getCode()`, `.getHospital().getName()`) where there is no persistence context: a controller method, a `TransactionCallbacks.afterCommit` lambda, a `@TransactionalEventListener`, a `TransactionSynchronization`'s `afterCommit()`/`afterCompletion(int)` (named class or anonymous: any getter chain), an interceptor's `afterCompletion(HttpServletRequest, …)` (entity getters only, the servlet API is fine there). | D921, #564 | post-commit code: **0** (must stay 0); controllers: 14 chains in 7 files (the two D921 chains left with #972; allowances lowered on the combined branch) |
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
3. Delete that entry — the `"site", count, "reason"` triple of the guard's
   `GuardAllowlist.allow(...)` list (`PostCommitLazyAssociationGuardTest`'s
   controller list takes `"file", "chain", count, "reason"`) — or lower its count.
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
| `guard:a11y-dialogs` | A modal that is not a dialog to assistive technology. | `role` (class names it a modal, no `role="dialog"` on it, an ancestor or a descendant, including a child component that is itself a dialog), `modal` (no `aria-modal`, or `aria-modal="false"`), `label` (no non-empty `aria-labelledby`/`aria-label`), `escape` (no `(keydown.escape)` on the dialog, an ancestor or a descendant, no `document:keydown.escape` HostListener, no CDK/Material dialog — an Escape bound on a **sibling** backdrop never fires while focus is inside the dialog, and a focus trap or an `'Escape'` string elsewhere in the file is not a handler). A native `<dialog>` has the role, the modality (`showModal()`) and Escape built in: only its name is checked. An element with the shared `appModalDialog` / `appDialogA11y` directive is a dialog (role, modality, focus trap from the directive); its Escape counts when `(escaped)` / `(dialogClose)` is bound. `appDialogFocus` (#972) supplies only the keyboard contract: its host still needs role, `aria-modal` and a name, and its Escape counts when `(dialogDismiss)` is bound. | D976, D988, D1004, D968 (D173 earlier) | 247 on the round-17 combined branch (126 of them the `focus` rule #978 added); 123 after round 16 (round-16 siblings: 59 stale pins deleted, referrals' six sibling-backdrop Escapes fixed; #972 merge: 8 pins of dialogs #972 fixed deleted) |
| `guard:a11y-names` | A control with no accessible name. | `icon-button` (a `<button>`, an `<a>` with `(click)`/`routerLink`/`href`, or `role="button"`/`"link"` whose content minus icons is empty and that has no `aria-label(ledby)`; `title` alone does not count), `field-label` (`<input>`/`<textarea>`/`<select>` with no `aria-label`, no `<label for>`, no enclosing `<label>`; anchored on the binding — `formControlName`, `name`, `[ngModel]`, `[(ngModel)]`, `[formControl]` — so two unlabelled rows are two pins) | D988, D999, D1006, D993 (D173/D200 earlier) | 49 sites at 46 pins, all unlabelled fields (round-16 siblings: every icon-only button and link named) |
| `guard:patient-picker` | A hand-written patient picker (search input + own suggestion list with a `selectPatient(...)` handler) instead of `<app-patient-picker>`. | `hand-written-picker` | D947 (D943/D942 as seen from each copy) | 11 |
| `guard:money-format` | Money printed without `formatCfa` / `\| cfa`. | `pipe` (`\| number` / `\| currency` on an amount-shaped field — the last identifier is a money word or ends in a capitalised one), `raw` (`{{ r.copayAmount }}` with no pipe at all, a `?? 0`/`|| 0` fallback allowed; the identifier must carry a money stem — `balanceDue`, `amountPaid` — so `medsDue`, `boardTotal()` and `daysUntilDue` stay counts), `helper` (a component `formatCurrency/Money/Amount/Price` not delegating to `formatCfa`, or Intl currency formatting outside one), `fixed` (`.toFixed(` on an amount, through `?.`/`!.`/`(a * b)`) | D948, D1008, D621 | 2 (a controlled-drug register's `openingBalance`/`closingBalance` are counts, exempted by name, QA D967) |
| `guard:ts-literals` | An English sentence handed to a toast or a status signal from component code — the layer the locale gates cannot see. | `toast`, `signal` (a field named `error…`/`message…`/… or ending in `Error`/`Message`/… — `saveError`, `formMessage` — and a status key in an object handed to `.set(`/`.update(`/`patchState(`; the literal anywhere in the argument or the assigned expression, so a `?? '…'` fallback counts; comments and the dead fallback of `translate.instant(…) \|\| '…'` do not) | D940 class | 6 (5 in `reset-password.ts`, 1 `?? 'Already in use'` fallback in `user-list.ts` found by the `.update(` rule; pre-existing, not yet QA defects) |
| `guard:uuid-fragments` | A UUID fragment on screen (`id \| slice: 0 : 8`, `.substring(0, 8)`, through `?.`/`!.`; the last camelCase word decides whether a name is an id — `hospitalId` is, `isValid` is not). | `slice-pipe`, `slice-call`, `strip-slice` (round 17: a slice taken after the dashes are stripped, whatever the receiver: `(id ?? '').replace(/-/g, '').substring(0, 10)`) | D992, D1032 (D1009 for file names) | 0 |

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
| `src/app/shared/i18n/app-locale.spec.ts` | `formatCfa`. D1008 was pinned there as a live `it` until #973 fixed it; the expectation is now inverted ("D1008 (fixed)": four Spanish digits are grouped like five). |

### How a fix branch shrinks a portal baseline

1. Fix the site (add `role="dialog" aria-modal="true" aria-labelledby`, name
   the button, use `<app-patient-picker>`, `\| cfa`, the human code…).
2. Run `npm run guards`: the gate reports `stale pin: <id> (pinned N, found
   N-1)`.
3. Lower `count` or delete the entry in `scripts/<gate>-baseline.json`.
4. A pin's `why` must be a QA defect id or a reason a reviewer can check;
   `--write-baseline` is for reading what changed, not for making CI green —
   the gate fails on any pin whose `why` is empty or still the `TODO` it
   writes, or whose `count` is below 1 (the Java `GuardAllowlist.allow()` rule).

---

## Guards added by the round-16 fix branches (extend these too)

| Guard | Class | Scope |
|---|---|---|
| `architecture/ClinicalDocumentAuthorGuardTest` (#971, extended by the siblings branch) | A colleague rewrites, deletes, signs, cancels or voids another clinician's draft or order (D930). | 13 services: discharge summaries, treatment plans, maternal history, referrals (general, OB/GYN), discharge approvals, consultations, prescriptions, lab orders, imaging reports and orders, ultrasound orders, transfusion requests. Each method's own body must call an author check, or be declared team-owned / supervisor (`TEAM_OWNED`) or admin-only (`ADMIN_ONLY`, checked against the controller's `@PreAuthorize`). |
| `architecture/PatientFirstMatchGuardTest` (r16 residual #13, combined branch) | A patient taken as the **first** match of a free-text lookup (phone, email, name): shared family numbers and emails make that a guess, and the audit log filed access rows under the wrong holder, possibly at another hospital. | Every production class: a `findFirstByPhoneNumber…` call, or `.findFirst()` straight off a phone/email/name finder without a `.filter(` to the one record meant, fails. Allowlist: `DevSyntheticDataSeeder` (dev seed, its own unique synthetic email). |
| `scripts/lib/lab-dialog-a11y.test.mjs` (#975, extended) | An overlay that is a keyboard trap (D968). | Every overlay in `lab/`, and every `detail-overlay` / `form-overlay` side panel in the portal. |
| `scripts/lib/lab-locale-format.test.mjs` (#975, extended) | A lab value, date or label colon not formatted for the reader (D1002). | `lab/` and the MTM review; outside `lab/`, every template showing a lab result value (value and colon rules). |

## Guards added by the round-17 front-desk branch (fix/r17-frontdesk-identity)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `shell/shell-startup-endpoints.spec.ts` + `security/ShellStartupEndpointRolesTest` | The shell or an app-wide service calls an endpoint the signed-in role cannot use (D1012: the EMPI probe 403'd for every patient, pharmacist and lab user on every page load). | The spec boots the real shell for every role in `SecurityConstants` and refuses a request not in `SHELL_STARTUP_ENDPOINTS` or not admitted for the role; the Java test holds that table to each controller's `@PreAuthorize`. Allowlist: none. |
| `architecture/AppointmentSlotRulesGuardTest#everyMethodThatBooksAClinicianChecksTheyAreFree` | A method that gives a clinician's time (new appointment, moved appointment) without the shared overlap rule (D1013: slot booking, accepted waitlist offer, referral appointment). | Every production method building an `Appointment` or moving one; helpers that call the rule count (to a fixpoint). Allowlist: mappers, FHIR mapper, dev seeder (not booking paths). |
| `architecture/DischargedPatientStaysFindableGuardTest` | A discharge (or any side effect) ends the registration, or a registration query leaves out a stay status, so a patient vanishes from every list (D1014). | All of `src/main`. Allowlist: none. |
| `architecture/OpenVisitDayWindowGuardTest` | The desk reads the day's walk-ins by calendar day without the visits still open from an earlier day (D1021). | Allowlist: none (one helper, `walkInsShownOn`). |
| `architecture/PatientMatchKeysGuardTest` (extended) | A free-text patient search that never routes a typed number to the phone match; a request DTO capping a written phone below 20 characters (D1019). | |
| `architecture/PatientDisplayNameGuardTest` | A patient name assembled as first + last, dropping the middle name (D1036 / D926). | GuardAllowlist of 8 files owned by other branches or pre-existing, each with its reason; shrinks only (`LabResultServiceImpl` left on the combined branch: #978 uses `getFullName`). |
| `controller/AuthControllerTest#resendVerification_answersTheSameForUnknownActiveAndPendingContacts_D1017` | A public account lookup whose answer differs by account state (D1017). | Resend by e-mail and phone; password reset was already always 204. |
| `service/disclosure/DisclosureAccountingServiceImplTest#thePatientsHeaderAlwaysAddsUpToItsLines` | A count shown beside a list that counts something else (D979). | Seeded random mixes, 20 runs. |
| `npm run guard:person-search` | People filtered with a raw `toLowerCase().includes()` (accents, full names, written phone formats missed; D1019, D1020). | 19 sites fixed, baseline 0. |
| `npm run guard:translate-params` | A translate call that leaves a placeholder of the value unfilled (D1035). | Baseline 0. |
| `scripts/lib/desk-receipt.test.mjs` | A staff screen that takes money without handing a receipt (D1022). | |
| `scripts/lib/drag-keyboard.test.mjs` | An action done only by mouse drag (D1023, WCAG 2.1.1). | Every `cdkDrag` / `(dragstart)` element. |
| `scripts/lib/person-name-display.test.mjs` | A template rendering `first last` without the middle name (D1036). | Staff-account screens only (the `pharmacy/dispensing.html` pin left when the combined branch piped its D1052 lines through `\| personName`). |

## Guards added or widened by round 17 (fix/r17-lab-pharmacy-billing)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/LabResultReleaseAnnouncementGuardTest` (extended) | D1016 (D931 sibling): a class that reads a lab result's value without asking whether it is a correction (`LabResultCorrections.isCorrection`, a DTO's `isCorrected()`); D1053: an `*.inBasket*` message key resolved without being stored as the item's key (`setTitleKey`/`setBodyKey`). | every production class; no allowlist |
| `scripts/lib/lab-correction-shown.test.mjs` (new) | D1016: a template that shows a lab result value without `<app-lab-correction-badge>` (or the lab's own `supersedesResultId` badge). | every template; no allowlist |
| `architecture/RequestFieldColumnLengthGuardTest` (new) | D1050: a request DTO text field written into an entity column of length N with no `@Size(max <= N)`. | request DTOs paired with their entity by name; 218 pre-existing fields pinned (QA D1050 class) |
| `architecture/ControlledFlagGuardTest` (new) | D922: a controlled-substance decision keyed on the prescriber's tick without the catalogue (`PrescriptionControlledResolver`). | every production class; 7 files pinned, each with why the tick itself is meant (the 2FA clause is the documented exception) |
| `scripts/lib/fefo-lots.test.mjs` (new) | D1029 / D964: a screen choosing a lot by "covers the quantity" or comparing expiry dates by hand instead of `pharmacy/fefo-lots.ts`. | every `.ts`; no allowlist |
| `scripts/lib/glued-colon.test.mjs` + `glued-colon-baseline.json` (new) | D1052 / D1002: `{{ 'X' \| translate }}:` anywhere in the app (lab-locale-format only read `lab/`). | every template; 113 sites in 41 templates pinned (#975: reported, not fixed) |
| `guard:a11y-dialogs` (widened) | D1030 / D968: an inner `(keydown[.escape])="$event.stopPropagation()"` swallows the backdrop's close (`escape`); a hand-built dialog with no focus management (`focus`: appModalDialog / appDialogA11y / appDialogFocus / cdkTrapFocus / dialog service); a `<dialog [open]>` (not `showModal()`) is checked like a hand-built one. | 126 focus, 3 escape-swallow and 18 `<dialog [open]>` sites pinned with their reason (61 escape-swallow pins before the combined branch merged #979, which fixed 58 of them); the prescriptions and pharmacy ones fixed. One rule with #979's copy: a binding of only `stopPropagation()`/`preventDefault()` is never a handler, and swallows only when it stops propagation |
| `guard:uuid-fragments` (widened) | D1032: see `strip-slice` above. | 0 |
| `architecture/BillingChargeCreatorGuardTest` (extended) | D972: the checkout listener states its one-visit-invoice rule. | — |

## Guards added by fix/r17-clinical-workflow (round 17; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/AuthorWithdrawRoleParityGuardTest` | A record its author cannot withdraw: a `/{id}/cancel` that admits fewer roles than the collection's `POST` create (D1026). | Every controller. 0 allowed. |
| `architecture/TimelineStatusGuardTest` | A chronology row that puts a status in its metadata but not in its summary, so a cancelled order reads as active (D1027). | `PatientServiceImpl` + `service/timeline`. 1 allowed (open lab orders, with reason). |
| `architecture/EncounterWaitGuardTest` | A wait computed from `getEncounterDate()` (the visit day's midnight) instead of `EncounterWait` (D1039/D990); a patient id served as the MRN (D1040). | All production code. 0 allowed. |
| `architecture/CancelStampGuardTest` | A cancellation of an entity with a `cancelledAt` column that stamps neither (D1047). | All production code. 0 allowed. |
| `architecture/RouteLabelGuardTest` | A medication route composed into text as its raw code (D1025, backend half of D950). | All production code. 2 allowed (partner SMS, FHIR text). |
| `architecture/MarWitnessGuardTest` | A MAR dose outcome recorded outside `ControlledAdministrationWitness` (D922, decision 2026-10-28). | All production code. 0 allowed. |
| `guard:a11y-dialogs` (extended) | An `(keydown.escape)="$event.stopPropagation()"` on a dialog is not an Escape handler and swallows its backdrop's (D1004 class): 58 sites fixed; the 2 `prescriptions.html` pins it left for the lab-pharmacy branch (D1030) were deleted on the combined branch, which fixed those modals, and its rule was folded into #978's `dialog-scan.mjs` (one implementation). | |
| `scripts/lib/medication-route-display.test.mjs` (extended) | A form field seeded with a stored route code (D1025: the eMAR "Voie", the intake reconciliation). | |
| `scripts/lib/iso-date-display.test.mjs` | An ISO date interpolated in any template without `\| date` (D1049); the lab-only date rule of `lab-locale-format.test.mjs`, for the whole portal. | 0 pinned. |
| `scripts/lib/fr-false-friends.test.mjs` | A French false friend in a portal or backend French string ("Reconnaître" for acknowledge, D1054). | |

The error interceptor is the choke point for D995: a write refused with a
server reason (`ClientSafeAccessDeniedException`: a `message`, no `code`) is
reported in a toast on the page, never by the 403 page; its spec covers an
arbitrary URL.

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
`.claude/skills/defect-fix-mandate/SKILL.md` (lands with PR #974; the rule is
summarised here, and every guard's failure message points at this section, not
at the skill file, so the pointer holds whichever PR merges first) —
fix the **class**, not the instance; prove it with a codebase-wide guard;
prove it on the running app; a PR without the class table is not a fix. These guards are the "guard the
class" step for the classes round 16 named. The contract between a fix PR and
a guard:

1. **Every allowlist entry carries a defect id** (or a one-line reason a
   reviewer can check). The entry says *this site is known, it belongs to
   D-xxx, and it is not fixed yet*.
2. **A fix PR removes its entries.** When the site is fixed the guard fails
   with *"the site was fixed: delete its line"* / *"stale pin"*; the PR
   deletes the entry (the `GuardAllowlist.allow(...)` triple, or the
   baseline JSON pin) or lowers its `count` in the same commit. The
   allowlist only ever shrinks.
3. **A new violation fails CI** and is never pinned to make the build green.
   Pinning is for sites that exist on `develop` today, each with its id. A
   reviewer rejects a pin without one.
4. **A guard that misses an instance is a guard bug**: extend the scan and its
   self-test (`.test.mjs` / "the scan catches the old shapes") in the fix PR.
5. Every failure message of these guards ends with a pointer to this section.

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
