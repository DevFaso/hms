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

### Round 18 (`fix/r18-clinical-reference`)

| Guard | Class it stops | QA defects | Allowlist today |
|---|---|---|---|
| `immunization/ImmunizationCvxOptionalGuardTest` | A payload that makes a vaccine/CVX code mandatory (CVX is a US code system; MenAfriVac and the malaria vaccine have none). Scans every class under `payload`; also pins the mapper (blank stored as null on create and edit) and the FHIR export (no empty coding). | D1003 | **0** |
| portal `patients/medical-history-tab/pev-cvx.spec.ts` | A PEV vaccine offered without a decision: each is either mapped to a CVX code checked against the CDC table (description and status recorded) or listed unmapped with a reason. | D1003 | 2 unmapped (MENA, MALARIA), each with its reason |
| `mapper/LabReferenceRangeFieldCopyGuardTest` | A reference-range copy that drops a field (sex/age band, age unit, critical limits, source): every field is set by reflection and must survive the definition DTO both ways and the result DTO. | r18 lab ranges | **0** |
| `mapper/MedicationCatalogItemDtoCoverageTest` (extended) | A catalogue column no request can write; round 18: every paediatric limit the dose rule reads is on the pharmacy's dosing request. | D935 | 2 server-set columns (reviewer, time) |
| `architecture/ControlledSigningReauthenticationGuardTest` | A path that writes `PrescriptionStatus.SIGNED` without the prescriber's re-authentication for a controlled drug; the signature must re-authenticate before SIGNED. | r18 section 6 (D922 class) | 2 non-signature sites, each with its reason |

### Round 23 (`fix/r23-backend`)

| Guard | Class it stops | QA defects | Allowlist today |
|---|---|---|---|
| `service/DrugAllergyBrandEntryPointsTest` (extended) | An allergy STOP (prescription create/edit/sign, CDS order-sign and medication-prescribe) that reads the drug without the INN identity (`AllergyDrugIdentity`) and its safety net: a brand nobody listed, or an unknown free-text name, passed a class allergy in silence. | D1149, D1183 | **0** |
| `architecture/MarDoseIntervalGuardTest` | A MAR write that stamps an administration time without the minimum-interval rule since the last GIVEN (`MarDoseInterval`). | D1210 | 1 (the entity itself) |
| `validation/PhoneFieldValidationGuardTest` | A phone-named text field of a request payload without `@PhoneNumber`. Read-only payloads are placed by name or listed with a reason. | D1211 | 5 read-only classes, each with its reason |
| `architecture/CancelVoidReasonGuardTest#everyInvoiceCancellationKeepsAReason` | An invoice set to CANCELLED without a kept reason (drafts included; the D1069 draft exemption is withdrawn). | D1217 | **0** |
| `architecture/R23BackendClassGuardsTest` | The "no specimen" refusal that cannot tell a rejected specimen (D1204); a proxy read that looks the grantor up under the proxy's tenancy (D1212); a lab-result pairing that cuts before the filter (D1213); a health summary built without `activeDiagnoses` (D1214); a request date range with no reversed-range check (D1215); a fill that checks form, units or FEFO before the wrong-drug check (D1221). | D1204, D1212, D1213, D1214, D1215, D1221 | date ranges: 5 listed with their check |

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
| `guard:a11y-dialogs` | A modal that is not a dialog to assistive technology. | `role` (class names it a modal, no `role="dialog"` on it, an ancestor or a descendant, including a child component that is itself a dialog), `modal` (no `aria-modal`, or `aria-modal="false"`), `label` (no non-empty `aria-labelledby`/`aria-label`), `escape` (no `(keydown.escape)` on the dialog or an ancestor — since round 19 a descendant control's handler, such as a typeahead's, no longer counts — no `document:keydown.escape` HostListener, no CDK/Material dialog — an Escape bound on a **sibling** backdrop never fires while focus is inside the dialog, and a focus trap or an `'Escape'` string elsewhere in the file is not a handler). A native `<dialog>` has the role, the modality (`showModal()`) and Escape built in: only its name is checked. An element with the shared `appModalDialog` / `appDialogA11y` directive is a dialog (role, modality, focus trap from the directive); its Escape counts when `(escaped)` / `(dialogClose)` is bound. `appDialogFocus` (#972) supplies only the keyboard contract: its host still needs role, `aria-modal` and a name, and its Escape counts when `(dialogDismiss)` is bound. | D976, D988, D1004, D968 (D173 earlier) | **0 after round 19** (fix/r19-orders-encounters-clinical-ui, dialogs slice: 210 → 0 — focus 101, label 62, modal 26, escape 11, role 10 — every hand-built dialog carries `appModalDialog`, or `appDialogFocus` with role/aria-modal; a new dialog without them fails CI and is never pinned); 247 on the round-17 combined branch (126 of them the `focus` rule #978 added); 123 after round 16 (round-16 siblings: 59 stale pins deleted, referrals' six sibling-backdrop Escapes fixed; #972 merge: 8 pins of dialogs #972 fixed deleted) |
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

## Guards added or widened by the round-18 front-desk branch (fix/r18-frontdesk-identity)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/PatientDisplayNameGuardTest` (rewritten, per method) | A patient name built from its first and last name, dropping the middle name, **whatever wraps or joins the parts** (`safe()`, `nullSafe()`, `nullToEmpty()`, `Objects.toString`, a join helper, `first = …; last = …;` locals). D1036 / D926 failed three rounds because the first version matched only `p.getFirstName() + " " + p.getLastName()`. Plus `#noProjectionConcatenatesANameWithoutItsMiddleName` for JPQL `SELECT new` projections. | Every method of `src/main` that reads both parts of a record carrying a middle name (the entity and every DTO with a `middleName` field, found by scanning). Allowlist: 4 name MATCHES (EMPI Jaro-Winkler, reception `nameContains`, `PatientMatchKeys.sameIdentity`) and the partner-pharmacy SMS initials, each with its reason. |
| `scripts/lib/person-name-display.test.mjs` (widened) | As above, portal: any receiver (`selectedPatient()!.firstName`, `formPatient()?.firstName`), and `.ts` template literals, concatenations and `[first, last]` joins. | Staff/user-account screens and the duplicate-search query, each with its reason. |
| `architecture/AppointmentSlotRulesGuardTest#everyMethodThatBooksAClinicianTakesTheSlotRules` | A booking path that takes the visit's end time from the caller, or books over a slot held for a waitlist offer (D1060, D1063). | Every method building or moving an `Appointment`: it must call `AppointmentSlotPolicy.endFor` (or use the slot row) and `requireNoLiveHold` (or the slot's `isOfferable`). Allowlist: mappers, FHIR, dev seeder. |
| `architecture/AppointmentSlotRulesGuardTest#everyMethodThatCreatesAnAppointmentNamesItsCreator` | An appointment created with no creator (D1073: slot and waitlist bookings). | Every `Appointment.builder()` / `new Appointment()` outside mappers, FHIR and the dev seeder. |
| `architecture/PdfCharCountTruncationGuardTest` | Printed text cut at a character count, mid-word (D1091: the invoice adjustment reason, D1036: the wristband name). | Every class drawing with PDFBox; `PdfText.wrap` / `fitSize` / `wrapAtMost` is the way. Allowlist: none. |
| `architecture/PatientAddressCompositeGuardTest` | An address rebuilt from its city, region, postal code and country (pipe-joined), so the parts show twice (D1074). | All of `src/main`; `PatientAddressText` is the one writer and reader. Allowlist: none. |
| `npm run guard:required-refusal` (new, part of `npm run guards`) | A form that refuses a submission with a generic "required fields" toast without marking, focusing and scrolling to the field (D1072, WCAG 3.3.1). | Every component method that shows a REQUIRED / MISSING / VALIDATION_ERROR message must call `refuseFields` / `focusFirstInvalid`. Baseline: the pre-existing sites outside the front desk, pinned with the defect id; shrinks only. |
| `npm run guard:translate-params` (widened) | A translate call with no params for a value that has a placeholder, or a key whose placeholders differ between EN, FR and ES (D1035 class). | Baseline 0; `placeholderMismatches` 0. |
| `i18n:raw-enums` (widened) | A comma-joined permission list or a relationship code rendered raw (D1062). | `permissions` added to the enum words; the `p.relationship` pin on family access removed. |

## Guards added or widened by round 17 (fix/r17-lab-pharmacy-billing)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/LabResultReleaseAnnouncementGuardTest` (extended) | D1016 (D931 sibling): a class that reads a lab result's value without asking whether it is a correction (`LabResultCorrections.isCorrection`, a DTO's `isCorrected()`); D1053: an `*.inBasket*` message key resolved without being stored as the item's key (`setTitleKey`/`setBodyKey`). | every production class; no allowlist |
| `scripts/lib/lab-correction-shown.test.mjs` (new) | D1016: a template that shows a lab result value without `<app-lab-correction-badge>` (or the lab's own `supersedesResultId` badge). | every template; no allowlist |
| `architecture/RequestFieldColumnLengthGuardTest` (new) | D1050: a request DTO text field written into an entity column of length N with no `@Size(max <= N)`. | request DTOs paired with their entity by name; 218 pre-existing fields pinned (QA D1050 class) |
| `architecture/ControlledFlagGuardTest` (new) | D922: a controlled-substance decision keyed on the prescriber's tick without the catalogue (`PrescriptionControlledResolver`). | every production class; 7 files pinned, each with why the tick itself is meant. Round-18 merge: the 2FA exception is gone (`ControlledSubstanceGuard` unpinned) and `#everyTwoFactorSafeguardAsksTheCatalogue` fails on any check of the prescriber's proof not keyed on the resolver (no allowlist) |
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

## Guards added by fix/r18-billing-pharmacy (round 18; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/BillableOrderRegistryGuardTest` (new) | D972 (NOT_FIXED in rounds 17 and 18): a billable order type of a visit with no charge mapping. Every `BillableOrderType` charged on the visit invoice has exactly one `VisitChargeSource`; every entity linked to an encounter is either the source of a registry entry or in `NOT_BILLABLE` with its reason. | every `@Entity` under `model/` with an `encounter`/`encounterId` field; 14 documentation/record entities declared not billable, each with why |
| `architecture/VisitCompletionBillingGuardTest` (new) | D972, second half: a path that completes a visit (sets `EncounterStatus.COMPLETED`) without publishing `EncounterCheckedOutEvent` (discharge, the admission that ends an emergency visit, a recorded death were never billed). | every production method; 1 allowed (`PrescriptionServiceImpl#createEncounterSnapshot`, the anchor of a stand-alone prescription: no visit took place) |
| `architecture/MapperDisplayNameGuardTest` (new) | D1058: a response mapper that sets `fooId` and leaves the DTO's own `fooName` to some of its callers (the checkout picker read "Non renseigné" because only `getDispense` filled the name). | every `…DTO.builder()` chain under `mapper/`; 0 allowed (`ServiceTranslationMapper.treatmentName` fixed with it) |
| `architecture/StatusRefusalLabelGuardTest` (widened) | D1090: a refusal or a 409 that names an invoice, routing or claim status by its raw code. Now also scans the billing and pharmacy services, `ConflictException`, `InvoiceStatus`/`RoutingDecisionStatus` labels in every locale, and `billing.*`/`stock.routing.*` messages that spell a code; a status looked up as a bundle label counts as a label. | 0 allowed (3 pharmacy messages that spelt ACCEPTED/PENDING reworded) |
| `architecture/ServerComposedTimeGuardTest` (new) | D1084: a pharmacy or billing source that formats a date-time without converting it through `HospitalTime` (server UTC printed as the hospital time). | pharmacy/billing packages and the billing/PDF services; 1 allowed: `service/PdfLabels.java` (shared labels; the invoice and receipt print dates only, confirmed at the round-18 merge; its time-of-day formatter serves the clinical PDFs, outside this guard's scope, tasklist debt) |
| `service/pharmacy/FefoLotPolicyTest#noLocaleOfTheFefoMessageClaimsTheEarlierLotCoversTheQuantity_D1083` (new) | D1083: a FEFO message (server bundle or portal locale) that says the earlier lot "covers" the quantity, or does not name what is left on it. | `pharmacy.fefo.reasonRequired` in 4 bundles, `PHARMACY.FEFO_WARNING` in 3 locales |
| `scripts/lib/invoice-tone.test.mjs` (new) | D1071: an invoice icon or colour chosen by a ternary of its own (`isPayable(inv) ? 'pending' : 'check_circle'`) instead of `shared/invoice-tone.ts`. | every template/ts about invoices; 0 allowed |
| `scripts/lib/cfa-amount.test.mjs` (new) | D1089: a screen naming `BILLING.PAYMENT_WHOLE_AMOUNT` itself instead of `shared/cfa-amount.ts`, which asks for an amount before it checks decimals. | every `.ts`/`.html`; 0 allowed |

## Guards added by fix/r18-clinical-record (round 18; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/TimelineEventRegistryGuardTest` | D959 (NOT_FIXED in r17 and r18): a kind of clinical event missing from the chronology. Every JPA entity that belongs to a patient is either a chronology event with its category or listed in `NOT_EVENTS` with a reason; every category has a row source (`VisitTimelineRows.sources()` registry or `PatientServiceImpl`'s shared rows), `timeline.category.*` in the three backend bundles, `PORTAL.ENUM.TIMELINE_CATEGORY` in en/fr/es and a `timelineIcon` case. | All of `model/` (85 patient-owned entities: 24 events, 61 decided not-events) |
| `architecture/LabResultReleaseAnnouncementGuardTest#everyRowBuiltFromAResultValueCarriesTheCorrection` (widened) | D1016/D931 (third round): a row built from a lab result's value (directly, or through a same-class helper like `formatResult`) without `.corrected(…)` in the same builder. The class-level rule passed for the chart review because its Results section asked the question while its Chronologie dropped the answer. | Every class reading `getResultValue()`; no allowlist |
| `scripts/lib/lab-correction-shown.test.mjs` (widened) | D1016: a template iterating a timeline or lab-result list without the correction badge in the loop body (per loop, not per file). | Every template; the patient-chart Chronologie loop is excused by expression (the server writes "(Corrigé)" in the summary) |
| `architecture/CdsCardLocaleFormatGuardTest` | D1049 / D1078 (D1002 class): a CDS or BPA card that formats a date (`toLocalDate().toString()`, `…At().toString()`, ISO formatter) or a decimal (`String.format("%.1f")`, `toPlainString()`) itself instead of `CdsCardText.date(…)` / `CdsCardText.decimal(…)`. | Every class writing card text; no allowlist (`PediatricDoseRule` converted at the round-18 merge) |
| `architecture/ControlledFlagResponseGuardTest` | D1080: a response DTO with a `controlledSubstance` field built without setting it (reads `false` for morphine). | Every `Dto.builder()` of a flagged response type in `src/main`; no allowlist |
| `architecture/CancelledOrderLockGuardTest` | D1066: a status write that can revive a cancelled order. Every non-cancel status write in the ten order services must refuse a cancelled order first (`CancelledOrders.requireNotCancelled`, an explicit CANCELLED check, `isTerminal()`, a transition table, or a live-state precondition). | 4 sites allowlisted with reasons (creates, the lab-order create branch, the ultrasound report path that refuses on entry, the signed-prescription withdrawal) |
| `architecture/EntityTransitionRefusalProseGuardTest` | D1086 (D1046 class): an entity state transition (or a helper it calls) that refuses with an English sentence instead of a message key. | All of `model/`; JPA lifecycle callbacks (integrity invariants) excluded; no allowlist |
| `architecture/EmptyCurrentRecordAnswerGuardTest` | D1082: a patient's `…/current`, `…/active` or `…/latest` GET that answers 404 (or a 200 with an empty body) when there is none. House convention: 204. | Every controller; no allowlist |
| `scripts/lib/author-only-actions.test.mjs` | D1081 (D995 class): a cancel / void / withdraw button on a row of an order list not gated by `authorship.isAuthor(row)`. | Every template; excused sites listed with reasons (general referrals already per row, lab void, pharmacy fill, appointments) |
| `scripts/lib/pregnancy-delivered.test.mjs` | D1067: a running gestational age computed from a history without the delivery check (`ongoingPregnancy`). | One excused site (the history form's preview) |
| `scripts/lib/maternity-notes-shown.test.mjs` | D1068: a free-text field a maternity form saves that no read surface renders. | Maternity templates; the two `recentDeliveryOverrideReason` confirmations excused |
| `guard:a11y-dialogs`, `glued-colon` (shrunk) | D1004, D1079. | a11y-dialogs 247 → 210 pins; glued colons 113 sites in 41 files → 92 in 32 |

## Guards added or widened by fix/r19-scheduling-frontdesk-billing (round 19; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/AppointmentSlotRulesGuardTest#everyPatientBookingPathTakesAPublishedSlot` | D1093: a patient's own booking or reschedule that takes any minute of any day instead of one published, free slot. Starts from every POST/PUT/PATCH handler a ROLE_PATIENT may call, follows the service methods it calls and their same-file helpers; any of them that builds or moves an appointment must call `AppointmentSlotPolicy.requirePublishedFreeSlot` (or the slot inventory's `book`). | All controllers and services; no allowlist (the desk keeps its documented walk-in / overbook freedom: it is not a patient path) |
| `architecture/CancelVoidReasonGuardTest` | D1069 (NOT_FIXED in r18: the dispense cancel the r18 table named still took an empty body): a cancel / void / reverse / reject / undo of money or stock with no reason. Reads every write handler of the pharmacy, billing, invoice, payment and claim controllers; each one whose path names such an action (or that is a DELETE) is listed with the body field it reads the reason from and the test proving a blank reason is refused (the proof must exist), or with why it is not a void. The invoice cancel (a status move on `PUT /billing-invoices/{id}`) is listed with its proof, and the two screens that offer these actions must carry a D1069 UI test. | 6 voids with proofs, 4 DELETEs that are not voids (draft invoice, draft line, catalogue item, registry row), each with its reason |
| `architecture/InvoiceBalanceGuardTest` | D1104: a balance shown to a reader computed by hand (`total.subtract(paid)`) instead of `InvoiceBalance.dueOf` (nothing owed on a cancelled invoice). | Every mapper, FHIR class, `Pdf*`, `*Email*` and `PatientPortal*` class; 1 site excused (the receipt prints the balance as of that payment, QA D228) |
| `architecture/PatientMatchKeysGuardTest#everyPatientNameComparisonIncludesTheMiddleName` | D1103: a patient-name search that ignores the middle name ("Mariam Wendkouni Kiemdé", "Rasmané Wend", "Wendkouni" found nobody). Reads every `@Query` of every repository that compares a patient's first name. | 1 query excused (the EMPI candidate block and audit resource-name lookup, not a typed-name search) |
| `architecture/PatientDisplayNameGuardTest` (shrunk) | D1103: the reception duplicate score now matches the name as displayed. | allowlist 4 → 3 files (`ReceptionServiceImpl` removed) |
| `scripts/lib/silent-disabled-submit.test.mjs` (widened r20, `lib/disabled-control-scan.mjs`) | D1124 / D1089 (NOT_FIXED in r18, r19 and r20: the r19 scan saw only amount-shaped names, so `!form.medicationName`, `!verifyAttested()`, `!canSubmit`, `!voidReason().trim()` … stayed dead on ~20 staff and portal screens) and the D1069 UI: **any** control switched off for anything but a request in flight or a pager end. Reads `disabled`, `[disabled]`, `[attr.disabled]`, `aria-disabled` / `[attr.aria-disabled]`, `[class.disabled]`, a `disabled` class, an `[ngClass]` naming `disabled`, on `<button>`, `<a>`, `<input type=submit|button>`, `role="button"` and `<fieldset>`, in every template (`.html` and inline), following the component members (methods, getters, `computed`) it calls two levels deep. The way out is `[appSubmitGuard]` (`shared/forms/submit-guard.directive.ts`: the click is refused, the blank fields are marked and the reason is shown next to the button) or an `aria-describedby` naming a visible reason. | Every template; 129 dead buttons fixed (the last 9, in admissions, appointment-detail, bed-board and the nursing intake, at the round-20 merge into qa/r16-combined); 0 pins; a stale pin fails |
| `scripts/lib/iso-date-display.test.mjs` (widened) | D1119: a range bound (`requestedDateFrom`, `…DateTo`, `…Until`) or a bare `.date` field shown as an ISO date; now reads inline templates too. | Every template; no pins |
| `patients/patient-form.spec.ts` "QA D1118 guard" | D1118: an existing-patient card that names the wrong match basis. Every basis (phone/e-mail, name + date of birth, both) × place (elsewhere, here, both) has a sentence in en/fr/es that names that basis and no other. | 9 sentences |
| `guard:required-refusal` (shrunk) | D1072 class, touched by the D1069 / D1089 fixes. | 137 → 134 pins |

## Guards widened by round 19 (fix/r19-orders-encounters-clinical-ui, glued colons)
| Guard | Class | Scope / allowlist |
|---|---|---|
| `scripts/lib/glued-colon.test.mjs` + `glued-colon-scan.mjs` (widened) + `glued-colon-baseline.json` (drained) | D1079 (NOT_FIXED r17, r18, r19): a colon glued to its label. The r17 guard read only `{{ 'X' \| translate }}:` and its baseline let 92 sites survive; the r19 misses were outside that shape (`MRN:` literal on the nurse station, `{{ label }}:`). It now reads the **display text** of every template (tags, attribute values, comments, `{{ }}` bodies and `@if (…)`/`@for (…)`/`@let` headers removed): rule `label` (a word glued to a colon that ends the text or precedes a value: `MRN: {{ m }}`, `>Statut:<`), rule `value` (a colon after any interpolation, spaces allowed; `{{ h }}:{{ m }}` excepted), rule `expr` (a `': '` literal composed in an interpolation or a `[binding]`), the plain `title`/`placeholder`/`aria-label`/`alt`/`label` attributes; every non-spec `.ts` for a literal that composes label-colon-value (`` `${label}: ${v}` ``, `'MRN: ' + m`, `a + ': ' + b`; console arguments, keys like `staff:${id}`, `a + ':' + b`, URLs and CSS excepted); every `fr.json` value (no word or `{{x}}` glued to a colon; `{{host}}:{{port}}` excepted); and `COMMON.COLON` = no-break space + `:` in fr, `:` in en/es. Only French requires the space, so en/es values are not read: the markup never types the colon, and each language's `COMMON.COLON` applies. | every template and `.ts` in `src/app`, `fr.json`. Baseline 32 pins / 92 sites → 2 pins / 15 sites, both true false positives with a reason: `privacy-policy.ts` (14, the English `@if (lang === 'en')` half of the bilingual policy) and `pharmacy/stock-adjustment.ts` (1, the stored `CODE: notes` wire format, rendered through COMMON.COLON). |
| `i18n/FrenchColonTypographyGuardTest` (new, backend half) | D1079 server side: a French label glued to its colon in what the server composes. Every `i18n/*_fr.properties` value (no word or `{0}` glued to a colon; `{0}:{1}` excepted); `.append(": ")` joining a label to a value anywhere in `src/main/java`; a string literal holding a French accented letter with a glued label colon. English bundles, audit descriptions, logs and FHIR narrative are English (StoredTextProseGuardTest owns that class); an unaccented French literal concatenated with `+` ("Pharmacie: " + x, fixed here) cannot be told from English audit text by a scan. | all bundles and main sources; 1 allowance: `DiagnosticReportFhirMapper` (English FHIR narrative, 1). The `PatientServiceImpl` lab-summary allowance was deleted when fix/r19-lab-results-chart merged (the line goes through `timeline.labResult.labelValue`). |

## Guards widened by fix/r19-orders-encounters-clinical-ui — dialogs slice (round 19; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `guard:a11y-dialogs` (drained, widened) | D1004 (NOT_FIXED in r17, r18 and r19): a hand-built dialog that keeps focus on its opener, so its own `(keydown.escape)` never fires. Round 19 found the five maternity dialogs still failing because their `focus` pins let them survive. Every pin is now fixed and the baseline is empty. Two holes are closed in `lib/dialog-scan.mjs`: a NESTED dialog's directive no longer counts as focus management for the dialog around it, and a descendant control's `(keydown.escape)` no longer counts as the dialog's Escape. | Every template; **0 pins** (210 → 0) |
| `shared/a11y/modal-dialog.directive.spec.ts` (new) | The `appModalDialog` contract: role and `aria-modal`, focus in (or onto the box when nothing inside is focusable), Tab kept inside, Escape through `(escaped)` respecting the close guard, focus back to the opener, only the innermost of stacked dialogs closing, no page-level Escape firing as well, Tab and Escape brought back when focus fell to the page. | The directive |
| `testing/modal-dialog-keyboard.ts` `expectKeyboardModal` (new) | Drives a dialog the way a keyboard user does: focus and press the opener, then focus must be inside a named modal, Escape (sent to the focused element) must close it, and focus must come back to the opener. | Used by the maternity specs for the five round-19 dialogs (new history, OB/GYN referral, ultrasound order, ultrasound view, birth plan); reuse it for any dialog QA reports |

## Guards added or widened by the round-19 rx slice (fix/r19-orders-encounters-clinical-ui; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/CdsReviewCardGuardTest` (new) | D1096: a decision-support card whose sentence asks for a pharmacist or physician review ("must review") built as a plain warning, so the order signs and reaches the pharmacy as an ordinary one. Such a card must be built by `reviewRequired(…)` (sets `CdsCard.reviewRequired`; the signature then holds the order until the review is recorded). | Every card sentence under `cdshooks/`; 2 review cards today (`cds.pediatric.manualReview.summary`, `cds.pediatric.band.none.summary`); no allowlist |
| `architecture/ControlledSigningReauthenticationGuardTest#theSignatureRequiresTheRecordedDoseReviewBeforeReAuthenticatingOrSigning` (widened) | D1096: the signature checks the recorded paediatric dose review before the re-authentication and before SIGNED. The class test above still lists every SIGNED writer (refill, stock-out return: re-sign an order that already passed the signature). | `PrescriptionServiceImpl#signPrescription` |
| `i18n/CdsRefusalTextGuardTest` (new) | D1102: a CDS refusal shown as developer text - a `CdsCriticalBlockException` built from a literal or a concatenation ("CDS ALERT: … set forceOverride …"), or a CDS refusal/card sentence in any bundle naming an API field (`forceOverride`) or a developer token (`CDS_CRITICAL`). | Every `new CdsCriticalBlockException(` in `src/main`; every `cds.*`, `prescription.(cds\|allergy\|pediatricReview\|safeguard).*`, `pharmacy.(dispense.cds\|cds.)*`, `nurse.mar.(override\|fiveRights)*` key in 4 locales of 5 bundles; clinical abbreviations mmHg, qSOFA, sBP, eMAR allowed; no allowlist |
| `i18n/ExceptionProseRatchetTest` (widened) | D1102: now also counts `CdsCriticalBlockException` with an English literal. | Baseline unchanged (the one literal, `PrescriptionServiceImpl` "CDS ALERT", was keyed before the pattern widened) |
| `architecture/ServerComposedTimeGuardTest` (widened) | D1114 (D1084 class): the CDS and BPA cards (`cdshooks/`) are in scope - a date-time formatter there must convert through `HospitalTime` (the BPA card printed server UTC), and a card must not take the calendar day of a stored timestamp (`getCreatedAt().toLocalDate()`) without converting it. | `cdshooks/` added to the pharmacy/billing scope; still 1 allowed (`service/PdfLabels.java`, unchanged) |
| `service/pharmacy/ControlledSubstanceGateTest#onlyTheActionableStatusesNeedTheProof` (new, parameterised over every `PrescriptionStatus`) | D1097: the step-up proof (and the co-signature) gate only the statuses that authorise somebody to act; DRAFT, PENDING_SIGNATURE, CANCELLED and DISCONTINUED never need it. | Every status |

## Guards added by fix/r19-oe-orders (round 19; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/VitalSignMeasureRuleGuardTest` (new) | D1077 (NOT_FIXED in r18 and r19): a vitals write path that stores a measure the patient cannot have (head circumference for an adult, fundal height for a man). Every method that saves a `PatientVitalSign`, applies a request onto one (`applyRequestToEntity`) or builds one with a head circumference must call a `PatientMeasureRules.require…` rule; every class that reads a fundal height from a request must check it. | All of `src/main` (recordVital, triage, nurse station found); no allowlist |
| `architecture/CancelledOrderLockGuardTest#everyCancelStatesItsReasonAndSparesAResultedOrder` (widened) | D1098: a cancellation with no checked reason, or one that does not refuse an order that already has its result. | The ten order services; 2 non-cancels listed (`GeneralReferralServiceImpl#toResponse` copies the reason onto the DTO; `LabOrderServiceImpl#voidLabOrder` is the entered-in-error path a resulted lab order needs) |
| `scripts/lib/author-only-actions.test.mjs` (widened, D1098 + D1108) | D1098: a cancel button of an order list not gated on `isOrderCancellable` (`shared/order-lifecycle.ts`); D1108: an LWBS button not gated on `isNeverSeenVisit` (`core/encounter-start.ts`). | Every template; 5 non-order records listed (appointments ×2, pharmacy fill, lab void, discharge request) |
| `architecture/ExaminingClinicianGuardTest` (new) | D1099: a service method that takes a visit out of IN_PROGRESS without `EncounterClosureRules.requireExaminingClinician`. | `service/**`; no allowlist (`ReceptionServiceImpl#updateEncounterStatus` judged not this class: own patients only, noted in the test) |
| `architecture/BedReleaseReasonGuardTest` (new) | D1100: a bed freed by hand (`bedAssignmentService.unassignBed`) with no required reason or no `ADMISSION_BED_RELEASED` audit. | All of `src/main`; no allowlist |
| `scripts/lib/server-refusal-shown.test.mjs` (new) | D1111: a write's error handler on the order/encounter/admission/discharge screens that shows only a generic toast (`error: () => toast(…)`). | 10 screens; reads (load / not found / none) allowed by key |
| `architecture/AuthorshipRefusalKeyGuardTest` (new) | D1112: an authorship check that answers `identity.notCaller` ("act in your own name"): `requireCaller` on a record's own author, or `identity.notCaller` written outside the acting-on-behalf sites. Every `requireAuthor…` key is defined in the four clinical bundles. | 3 acting-on-behalf sites listed with why |
| `i18n/ExceptionProseRatchetTest` (baseline shrunk) | D1111: English refusals in the discharge flow. | `DischargeApprovalServiceImpl` 12 → 0, `DischargeSummaryServiceImpl` 4 → 0 |
## Guards added by fix/r19-lab-results-chart (round 19; extend these too)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/LabResultChartRound19GuardTest#d1095_*` | D1095: a lab value written out without its unit (a blank stored unit printed as "0,40"). The stored unit is read only through `LabResultMapper.unitOf` (the test definition's unit when none was typed); every class reading `getResultValue()` reads the unit too. | All of `src/main`; 4 named readers of the stored column, each with its reason (the mapper, the write path, two readers of the `ResultEntryDTO` built from `unitOf`) |
| `architecture/LabResultChartRound19GuardTest#d1092_*` + `mapper/LabUnitConversionGradingTest` | D1092: a result released without asking whether any range could grade it (a mg/dL glucose on g/L ranges released ungraded, no critical alert). Every class that sets `released` asks `isUngradedForUnitMismatch`; mass and molar units convert by a power of ten (`LabUnitConversion`), nothing that needs the analyte is converted. | Every `setReleased(true)` in `src/main` (auto-verification, manual release, MLLP inbound); 0 allowed |
| `architecture/LabResultChartRound19GuardTest#d1094_*` | D1094: an entity invariant that ties an order to the visit's clinician of record (`encounter.getStaff().getId()` in `model/`), answering a colleague with a 500. | All of `model/`; 1 allowed: `Prescription` (other branch's file; the service refuses first with a translated 400; flagged) |
| `architecture/LabResultChartRound19GuardTest#d1109_*` + `LabOrderLifecycleTest#d1109_*` | D1109: a specimen write that ignores the order's lifecycle (a collection on a released order). Each of the three specimen writes calls its `LabOrderLifecycle.accepts…`; a fourth `save` fails the guard. | `LabSpecimenServiceImpl`; every `LabOrderStatus` decided |
| `architecture/LabResultChartRound19GuardTest#d1120_*` | D1120: a lab test definition written without the duplicate-code check (`requireCodeFree`) — versions link instead. | Every `LabTestDefinition.builder()`; the service, its mapper (called by create only) and the dev seeder (looks the code up first) |
| `architecture/LabResultChartRound19GuardTest#d1113_*` | D1113: an immunization reader that attributes a hospital (timeline provenance, FHIR `primarySource`) without asking whether the dose was transcribed. | All of `src/main`; 0 allowed |
| `scripts/lib/lab-locale-format.test.mjs` (widened, `QA D1095`) | D1095: a template showing a lab value (`.resultValue`, the portal's `lab.result`, a `class="lab-value"` element) without its unit in the same expression or cell. | Every template; 0 allowed |
| `scripts/lib/notification-destinations.test.mjs` (new) | D1110 (D983 class): a notification type the server writes with no decided destination. Each type (every `createNotification` type argument, literal or same-file constant) is a key of the portal's `DESTINATIONS` or in `NO_DESTINATION` with its reason; stale entries fail too. | 19 staff-only / informational types declared, each with its reason |

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

## Guards added by the round-19 decimal slice (fix/r19-oe-decimal)
| Guard | Class | Scope / allowlist |
|---|---|---|
| `scripts/lib/lab-locale-format.test.mjs` (widened: rules in `scripts/lib/decimal-scan.mjs`) | D1106 / D1115 / D1116 (D1002 class, third round): a measured decimal shown with the stored point. The round-18 scan read only `lab/` and templates with a `.resultValue`, so the vitals tab ("37.9 °C"), the growth table and axis ("4.8 kg", "4.6"), the chart's Laboratoire table, the portal dashboard and the proxy viewer survived. Now EVERY template (inline ones too): an interpolation reading a decimal-named field (`resultValue`, `referenceRange`, `normalRange`, `bmi`, `temperature`, `…Kg`/`…Cm`/`…Celsius`/`…Mmol`), or a lab row's `result`/`value` (receiver named for a lab, or the loop variable of a `@for` over a lab-results collection), must pipe through `labValue` / `labRange` / `localeNumber` / `measure` / `number`, or be the argument of a component method whose body calls the shared formatter (a method that formats by hand fails, the growth `formatMeasure` shape); any `.toFixed(` in a template; and in every `.ts`, `.toFixed(`/`.toPrecision(`/`(Math.round(…)).toString()` except SVG coordinates. One formatter: `shared/i18n/app-locale.ts` (`formatLabNumber`, `formatLabRange`, `formatMeasure`, `formatDecimal`, `localizeDecimals`), template face `shared/pipes/lab-value.pipe.ts` + `measure.pipe.ts` (`lab/shared/lab-value.pipe.ts` re-exports). | Every template and `.ts`; no baseline (0). Integer-named fields (`…Bpm`, `…MmHg`, `…MgDl`, `…Ml`, `…Grams`: Integer columns) are out of the rule by name. |
| `i18n/DisplayNumberFormatGuardTest` (new) | D1106 (D1002 class) on the server: a decimal composed into display text with the stored point — a chronology title, a record-PDF row, a timeline summary, a vitals line, a refusal argument, a prescription copy or partner SMS. Every main class: `toPlainString()`, `String.format` with a float conversion, `getResultValue()` appended / concatenated / joined raw, a decimal vital getter turned into a string by hand. One formatter: `utility/DisplayNumbers` (`labValue`, `labValueWithUnit`, `decimal`, `quantity`, `measure`, `localizeDecimals`); `CdsCardText.decimal` and `CriticalValueNotificationService.localizedValue` delegate to it. A Number handed to a MessageSource is not matched (MessageFormat formats it in the message's locale). | 5 sites allowlisted, all wire formats: HL7 OBX (×2), the insurer-claim JSON, the controlled-drug register CSV, the invoice amount digits (money, formatted as F CFA there), the repeat-critical comparison key |
| `i18n:raw-enums` baseline | D1117: the growth table's Origine column printed TRIAGE / NURSE_STATION; it renders `p.source \| enumLabel: 'vitalSource'` (every source the backend writes: TRIAGE, NURSE_STATION, DELIVERY, PATIENT_REPORTED, plus the domain's others). | pin `growth-chart-tab.component.html::p.source` deleted (210 → 209) |

## Guards added by the round-20 backend-core slice (fix/r20-backend-core)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `cdshooks/bpa/BpaRulesOutsideSessionIT` (new, real Spring + repositories, no transaction) | D1121 (regression of the D1114 fix) / D1114: a CDS or BPA rule that reads a LAZY association outside a persistence context (open-in-view is off; the CDS hooks run without a session), so it throws and the engine dropped the card. Every registered `BpaRule` runs on a context the real engine built (its readings carry real Hibernate proxies) and must render its card in one of the two seeded scenarios (a new rule fails until the seed covers it); the `patient-view` hook returns the time-stamped cards with the reading time on the hospital's wall clock; every registered `CdsRule` runs on an order whose hospital is an uninitialised proxy. | none |
| `hms.cds.rules.fail-fast=true` (test profile; `BpaRuleEngine`, `CdsRuleEngine`) | D1121: a swallowed rule exception. In tests the engines rethrow, so any test that reaches a throwing rule fails; production logs at ERROR with the stack trace and keeps the chart loading. `BpaRulesOutsideSessionIT#theTestProfileMakesARuleExceptionFailTheTest` pins the flag. | none |
| `architecture/CancelVoidReasonGuardTest` (extended: appointments) | D1126 / D1132: a write path that cancels an appointment or marks it a no-show without a reason. A bytecode scan finds every main-code write of an appointment status (`Appointment.setStatus`, the builder's `status(...)`); each is listed with the reason's source and the test that refuses a blank one, "OWN REASON" (close-unseen, death record, the patient's own cancel) or "NEVER DROPS". The desk dialog's click-level spec is required too. | 15 writers listed, 0 unlisted |
| `architecture/PaymentReferenceUniquenessGuardTest` (new) | D1129 (D345 / D975 class): a till that stores a provider reference without asking every ledger. A bytecode scan finds every write of a payment reference (`PaymentTransaction`, `PharmacyPayment`, `PharmacySale`); each is CHECKED (its class calls `PaymentReferenceRegistry.requireUnused`, verified) or names the caller that checks it. | 4 writers, 0 unlisted |
| `architecture/CdsCardLocaleFormatGuardTest` (extended) | D1133: a card value padded to a fixed number of decimals (`decimal(v, 2)`: "8000,00 mg", "14,00 kg"). Computed quantities go through `CdsCardText.measure(…)`. | allowlist still empty |
| `i18n/SeverityAgreementGuardTest` (new) | D1134: a severity in words taken from the masculine `cds.enum.*` after a feminine noun ("gravité : inconnu"). No main code turns a severity into words through `enumWord` or a `"cds.enum." + …severity` key; every allergy, problem and interaction severity has a `cds.severity.*` word in every locale, and the French ones agree with a feminine noun. | none |

## Guards added or widened by round 20 (fix/r20-portal-overlays-display)
| Guard | Class | Scope / allowlist |
|---|---|---|
| `guard:a11y-dialogs` (widened, `lib/dialog-scan.mjs`) | D1125 / D1004 (NOT_FIXED r18-r20): an overlay that is not CALLED a modal. Rule `overlay`: a page cover (`*-backdrop`, `*-scrim`, or a `*-overlay` with a `(click)`) whose panel - the cover itself, a dialog inside it, or the element right after it - is not a dialog (the shell bell panel and profile menu, the reception and dashboard snapshot drawers, four platform drawers). Rule `escape-target`: a `(keydown.escape)` on an element that can never hold focus (aria-hidden, or no focusable content: the shell's backdrops). The phone sidebar (`<aside>` holding a `<nav>`, closing on its own Escape) is the one non-dialog panel. Containers named `popover` / `flyout` / `offcanvas` count as dialog containers. | 0 pins |
| `shared/a11y/focus-return.ts` + `focus-return.spec.ts` | D1125: focus came back to BODY when the opener disabled itself while loading (nursing intake). The three dialog directives take their opener from the last element that received focus. | every directive dialog |
| `scripts/lib/dialog-stacking.test.mjs` (new) | D1122 / D1100: a dialog rendered as a SIBLING of its backdrop, in the page flow under the fixed cover. Every template is read with the styles that apply to it (global first, then the component's); a sibling dialog must be `position: fixed/absolute` with a higher `z-index`. Click-level proof: `admissions-bed-release-click.spec.ts` clicks the element `elementFromPoint` finds. | 0 (admissions 1, mortality 2, transfusion 6 nested) |
| `i18n/SmsTextLocalizationGuardTest` (new) | D1123: an SMS body written as an English literal (`String.format("Confirm your '%s' role…")`, "Hello! Your username is: %s") or labelling a password. Every main class holding an `SmsService`. | 0 |
| `scripts/lib/null-target-open.test.mjs` (new) | D1130: an `openX(null)` button whose dialog is open only while its target is not null. | 0 |
| `scripts/lib/iso-date-display.test.mjs` (widened) | D1140: a date pattern fixing the order of a day and a month (`'MMM d'`, `'dd/MM/yyyy'`); use a named format or `\| dayMonth`. | 0 (the waitlist pin was removed at the round-20 merge, fixed by fix/r20-portal-forms) |
| `scripts/lib/glued-colon*.mjs` + `i18n/FrenchColonTypographyGuardTest` (widened) | D1138: `?` `!` `;` typed in the markup after a word, a name or an interpolation (`</strong>?`), or glued in a French bundle value; the mark comes from `COMMON.QUESTION_MARK` / `EXCLAMATION_MARK`. | birth-plan pin removed at the round-20 merge (fixed by fix/r20-portal-forms); privacy-policy English half 14 -> 15 (true false positive, reason in the baseline) |
| `scripts/lib/lab-locale-format.test.mjs` / `decimal-scan.mjs` (widened) | D1135 / D1106: a percentage with a typed "%" (`{{ x }}%`, `{{ x \| number }}%`, `+ '%'`), a rate/pct translation parameter not piped through a formatter, a `.ts` percent composed by hand (CSS lengths excepted). | 0 (18 sites fixed) |
| `scripts/lib/lab-correction-shown.test.mjs` (widened) | D1137: a unit after the correction badge in the same cell. | 0 |
| `utility/JobTitleCredentialsTest` (new) | D1136: a job title written in English for display (`formatJobTitle`, `JobTitle.getTitle()`). | 0 |
| `interceptors/csrf-refusal-race.spec.ts` (new) | D1145: a stale CSRF token answered as ACCESS_DENIED and turned into the forbidden page by the inner error interceptor before the CSRF replay. | real interceptor order |

## Guards added or widened by round 21 (fix/r21-core-clinical-rules)
| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/EmptyClinicalWriteGuardTest` (new) | D1148 / D1150 / D1151 / D1158: a clinical write whose all-empty body is accepted (a blank ultrasound report read "Normal", blank postpartum observations counted as checks, an empty triage, intake, note, history). Every POST/PUT/PATCH with a body on 22 clinical controllers must be refused empty at the edge (an all-null instance fails bean validation on a content property, run with the real validator), or be listed in `CHECKED` with the service method that calls `ClinicalContent.require…` (read in the source), or in `NOT_FINDINGS` with why. One choke point: `service/support/ClinicalContent`. | `KNOWN_GAPS`: 4 edit paths (imaging report, micro culture, maternal history, high-risk plan), each with its class id; shrinks only |
| `architecture/CancelVoidReasonGuardTest` (extended: clinical withdrawals) | D1157 / D1160 (and D1159 for the MAR): a handler outside money and stock whose path cancels, closes, discontinues, revokes, rejects, voids or holds, with no proof that a blank reason is refused. Waitlist close, recall cancel and the discharge-request cancel/reject closed with an empty body. Each of the 33 handlers is listed with its proof (a test, or `-> VALIDATED`, which runs the validator on the DTO), or `OWN REASON` / `NOT A WITHDRAWAL` / `NOT A CLINICAL RECORD`. | `KNOWN GAP`: refill reject, ROI cancel (each with its class id) |
| `i18n/MessageBundleParityTest#everyKeyTheCodeResolvesExists` (new test) | D1152 / D1123: a key handed to `messageSource.getMessage(…)`, `MessageUtil.resolve(OrRaw)` or any `new …Exception(…)` that no bundle defines, so the reader gets the English default (`"Invalid confirmation code."`) or the raw key. The old test read two exception types only. | 0 (5 keys added: `assignment.confirmation.invalid/.already/.actor-mismatch`, `insurance.link.forbidden`, `registration.hospital.notClinical`) |
| `i18n/ConstraintMessageKeyGuardTest` (new) | D1179: an `@Email` with no message key (Hibernate's own "doit être une adresse électronique syntaxiquement correcte"), and a `message = "{key}"` no bundle defines. | 0 (19 `@Email` keyed) |
| `i18n/PluralArticleNumberGuardTest` (new) | D1177: a plural article before a number that can be 1 ("dépasse les 1 cp", "supera los 1 cp"). FR and ES bundles. | 20 keys whose number is never 1 (clock times, constants, limits), each with why |
| `i18n/SeverityAgreementGuardTest` (widened) | D1168 (D1134 class, NOT_FIXED r21): a generic enum-label helper fed a severity (the record PDF's `label(t, a.getSeverity())`, masculine "modéré" beside "Confirmée"). | 0 |
| `architecture/CachedDisplayCopyGuardTest` (new) | D1147: a display copy written only while blank (`if (!notBlank(target.getMedicationDisplayName())) target.setMedicationDisplayName(…)`), so an edited order kept the first drug's name. | 0 |
| `architecture/EncounterProviderRoleGuardTest` (new) | D1164: a visit's staff check listing roles by hand (`isDoctor \|\| isNurse \|\| isHospitalAdmin`, no midwife, physician or surgeon). One list: `RoleValidator.ENCOUNTER_PROVIDER_ROLE_CODES`. | 0 |
| `service/support/VitalSignNotesTest#noWriterComposesTheScoreIntoStoredText` (new) | D1169: a vital-sign number composed into stored text in the writer's language ("Douleur : 8/10"). The score is a column (V257), rendered at read time. | 0 |
| `PrescriptionServiceImplTest#qaD1161_everyStatusIsClassifiedAsSignedOrNot` (new) | D1161: a prescription status a signature led to that is not withdrawn under the signed rule (author, or a prescriber of its hospital, with a reason, audited). | 0 |

## Guards added or widened by the round-21 portal clinical-forms branch (fix/r21-portal-clinical-forms)

| Guard | Class | Scope / allowlist |
|---|---|---|
| `guard:a11y-dialogs` rule `subform` | An inline action form INSIDE a dialog (a block opened from it holding a text field and a button closing that form, not the dialog) whose Escape bubbles to the dialog and drops the typed text (D1155 / D1144 / D1131, three rounds NOT_FIXED). Every inline form is found by construction: `@if` / `@else if` / `@else` bodies and `<ng-template>`s inside a dialog; a field and a Close in exclusive branches of one `@if` chain are a panel, not a form. Fix: `appInlineSubForm` + `(dismissed)` + `<app-inline-discard-ask>` (shared/a11y). | 1 pin: billing's invoice line edited in place spans two table cells; its Escape/focus behaviour is hand-written in billing.ts and DOM-tested (fix/r21-portal-shell-a11y-typography); the pin goes when the row moves onto the directive |
| `guard:a11y-dialogs` rule `focus` (widened) | A `cdkTrapFocus` dialog whose every control sits behind an `@if` (it loads): the trap captures nothing, focus stays on the opener, Escape never reaches the dialog (D1154 / D1004 / D1125). | 0 |
| `guard:a11y-dialogs` rule `label-icon` | An icon-font span without `aria-hidden` inside the element a dialog's `aria-labelledby` names: the ligature is read as the dialog's first word (D1174). | 0 (the 3 my-appointments pins were fixed in fix/r21-portal-shell-a11y-typography and deleted at the round-21 merge) |
| `scripts/lib/inline-editor-focus.test.mjs` | A row editor (`@if (editingX() === row.id) { <field> }`) that replaces the control that opened it without taking the keyboard (D1175). Fix: `appFocusOnRender` (+ `focusOnRenderReturn`). | 1 pin: billing/ (other branch) |
| `scripts/lib/raw-server-message.test.mjs` | A component reading `err.error.message` itself — bypassing `refusalReason` / `labelledRefusalReason`, so a validation refusal shows bare constraint text (D1156 / D1124 / D1089). | 0 in the clinical-forms folders; 51 pre-existing files pinned by count, shrinks only |
| `scripts/lib/terminal-action-reason.test.mjs` | A service call to a cancel / close / void / revoke / discontinue / withdraw / reject endpoint with an empty body: a one-click end of a record with no reason (D1157, D1160 class). Fix: `<app-reason-dialog>` (shared). | 6 pins, each with why it needs no reason |
| `scripts/lib/visit-provider-label.test.mjs` | A label next to a visit's provider (`staffName`, `assignedProvider`) that names a physician in FR/ES; a midwife holds visits (D1178). | 0 |
| `required-refusal` scan (narrowed) | A submit guard's reason method that RETURNS its text to `[appSubmitGuard]` (which marks the fields) is not an unmarked refusal. | — |

## Guards added or widened by round 21 (fix/r21-portal-shell-a11y-typography)
| Guard | Class | Scope / allowlist |
|---|---|---|
| `guard:a11y-dialogs` rule `opener` (new, `lib/dialog-scan.mjs`) | D1146 (regression of the D1124 fix): an opener rendered only while its dialog is closed (`@if (!cond)`, the `@else` of the dialog's own `@if`, `*ngIf="!cond"`) whose click reads that condition. The dialog's directive found the opener destroyed on close and focus fell to BODY. | Every template; 0 pins |
| `guard:a11y-dialogs` rule `panel` (new) + the cover rule's drawer exception (narrowed) | D1153 / D1125 / D1004 (NOT_FIXED r18-r21): a drawer or a menu that is not a dialog. A panel named a drawer / menu / sidenav / off-canvas / sheet must be a dialog with or without a backdrop (a `<section>`/`<details>` disclosure and a layout column called a sidebar are page content). The navigation drawer's own `(keydown.escape)` no longer excuses it behind a backdrop: it must carry `appDrawerDialog` + `(drawerClose)`. | Every template; 0 pins |
| `shared/a11y/drawer-dialog.directive.ts` + `shell.spec.ts` "the phone drawer is a keyboard modal" | D1153: a panel that is a landmark on a wide screen and a modal on a phone. While open: role and aria-modal, focus moved in, Tab kept inside, Escape from anywhere (the Menu button included), focus back to Menu; the page behind is `inert`. | The shell drawer (the only such panel) |
| `my-appointments.component.spec.ts` "QA D1146" (DOM) | D1146: keyboard open, Escape and the close button each give focus back to "Planifier un rendez-vous"; a second open does not wipe the form (D1124). | The booking dialog |
| `interceptors/csrf-refusal-race.spec.ts` (widened) | D1145 (NOT_FIXED r20): a replay refused because a sibling's refusal rotated the cookie meanwhile. Up to `MAX_CSRF_REPLAYS` (2) replays, each with the current or a fresh token; only the last is reported. | Every mutating request (one interceptor) |
| `billing.spec.ts` "a failed read says so" | D1145: a failed invoice read shown as "No invoices found" with zero totals. The list says it failed, with a retry; the tiles read "—". | The billing list |
| `scripts/lib/email-field-check.test.mjs` (new) | D1179: an e-mail field sent to the server unchecked (raw Jakarta text in a toast). Every template input that takes an address belongs to a component calling `isEmailAddress` / `invalidEmailAddresses` (`shared/forms/email-address.ts`) or `Validators.email`. | 17 pre-existing forms allowlisted with D1179 (tasklist standing debt round 21); shrinks only, a stale entry fails |
| `scripts/lib/lab-locale-format.test.mjs` / `decimal-scan.mjs` (widened) | D1166 / D1106 / D1135 (NOT_FIXED r19-r21): a "%" typed after markup (`{{ x }}<small>%</small>`, `</strong><small>%</small>`, `</span><span>%</span>`): tags between the interpolation and the sign are stepped over. | Every template; 1 pin: `profile/profile.html` security score (owned by fix/r21-portal-clinical-forms), pinned by its exact message, stale pin fails |
| `scripts/lib/lab-correction-shown.test.mjs` (widened) | D1167 / D1137 (NOT_FIXED r20-r21): a correction marker glued to its neighbour ("g/dLCorrigéBas"). The shared badge carries a word space on each side (every usage, enumerated, is spaced by construction); a hand-written `status-correction` marker after another element in its cell is preceded by `&ngsp;` / `{{ ' ' }}`. | Every template; 0 |

## Guards added or widened by round 22 (fix/r22-clinical-backend)
| Guard | Class | Scope / allowlist |
|---|---|---|
| `service/impl/PostpartumMonitoringCheckGuardTest` (new) | D1181 / D1151 (NOT_FIXED r21): an entry that checks nothing (a mood, an education note) restarts the 15-minute postpartum clock. Every property of `PostpartumObservationRequestDTO` is classified CHECK / NOT_CHECK / METADATA and each, set alone, is proven against `isMonitoringCheck`; a new property fails until classified. | 0 |
| `architecture/ObservationClockGuardTest` (new) | D1181 / D1188 class in the nurse station: a vitals clock or the triage gate reading the LATEST vitals row, so a weight or pain score alone restarts the 4-hour round or counts as the triage's vitals. Clocks read `PatientVitalSignRepository.findLatestObservationSet`. | 2 display sites (chart snapshot, worklist summary), each with why; stale entries fail |
| `architecture/EmptyClinicalWriteGuardTest` (drained) | D1182 / D1150 (NOT_FIXED r21): create guarded, EDIT not. The 4 `KNOWN_GAPS` edit paths are now `CHECKED` (imaging report, maternal history, high-risk plan) or `NOT_FINDINGS` (micro culture partial correction); `noKnownGapIsLeft` keeps the list at 0. | `KNOWN_GAPS`: **0** (must stay 0) |
| `EmptyClinicalWriteRulesTest#everyYesNoBoxTickedIsStillABlankUltrasoundReport_D1182` (new) | D1182: a form-defaulted yes/no box (cardiac activity) counted as a finding. Every Boolean of the ultrasound report set TRUE at once, with category and date, is still refused. | 0 |
| `service/DrugAllergyBrandEntryPointsTest` (new) + `DrugClassAllergyTest` stem scan (widened to brand names) | D1183 / D1149 (NOT_FIXED r21): brand names (Bactrim, Augmentin) past the drug-class allergy check. Every entry point (prescription create/update check, CDS order-sign class rule and name match, cross-reactivity advisory) is exercised with brands typed and from the catalogue; a file comparing an allergy with a medication must call `DrugClassAllergy` or be listed as not a check. | 4 not-a-check files (intake, PDF, chart summary, snapshot), each with why |
| `service/TriageAndIntakeContentRuleTest` (new) | D1188 / D1158 (NOT_FIXED r21): weight, height or pain alone accepted as a triage's vital sign; a blank medication row taken as an intake. Every triage request property is classified VITAL / NOT_ALONE / METADATA and proven against `requireTriageFindings`. | 0 |
| `service/pharmacy/DispenseUnitFormsTest#everyPairOfGroupsIsDecided` (new) | D1192 / D1163 (NOT_FIXED r21): a liquid form ("sirop", "gouttes") or an unknown unit silently accepted from a tablet lot. Every pair of unit groups is decided; only LIQUID_PREPARATION / CONTAINER cross. | 0 |
| `cdshooks/bpa/BpaRuleEngineTest#noBpaRuleMintsARandomCardId_D1194` (new) | D1194: a BPA card id that is random, so the 24-hour acknowledgement fell back to the card's (localized) text. | 0 |
| `service/i18n/NotificationLabValueLocaleTest#noNotificationIsBuiltWithANumberAlreadyFormatted` (new) | D1207: a notification built with a number formatted in the staff locale before the reader is known. A method that calls `createNotification(` / `NotificationText.of(` never calls `DisplayNumbers.` / `localizedValue(`. Partial: a helper one call away is not followed. | 0 |
| `db/PrescriptionDisplayNameBackfillMigrationIT` (new) | D1147 (NOT_FIXED r21): the stored rows edited before #1001 (V258 backfill, real PostgreSQL, idempotent). | n/a (data) |

## Guards added or widened by round 22 (fix/r22-reception-portal-backend)
| Guard | Class | Scope / allowlist |
|---|---|---|
| `architecture/TerminalReasonOverwriteGuardTest` (new) | D1186: a close / cancel / revoke / discard / reject that records a reason but does not refuse a record already closed, so a second call answers 200 and overwrites the first reason (waitlist close; siblings: advance-directive revoke, blood-unit discard, diagnosis removal, portal proxy revoke). Every method writing a close reason (`set…Reason(value)`, `requireCancelReason`) must name the status it writes in a check, or go through a recognised precondition (transition table, `ensurePending`, `isTerminal()`, an allowed-state set, a status-filtered load, a conditional claim). | All of `src/main` except `model/`. 7 upstream checks allowlisted, each with where the check is (private callees of a status-gated caller, entity `cancel()` methods, status-filtered loads) |
| `i18n/ExceptionProseRatchetTest` (widened + zero scopes) | D1187 / D1152 (NOT_FIXED r21, r22): English refusals one screen away from a keyed one. The scan now also sees `ClientSafeAccessDeniedException`, `ResourceNotFoundException` and a sentence handed to `String.format` / `MessageFormat.format`; `ZERO_PROSE_SCOPES` holds the front desk, the patient portal, scheduling (whole package), the registry (whole package), phone verification, registration, eligibility, guarantors, photos, assignments and advance directives at **zero** (constructor + helper scan), whatever the baseline says. | Baseline: 12 files removed (57 sentences keyed in en/fr/es, 524 → 467); the widened scan found 2 more (`PrescriptionClarificationService`, keyed) |
| `i18n/StoredTextProseGuardTest` (allowlist shrunk) | D1196: a patient's reschedule / cancel reason glued into the notes as "Patient rescheduled: …". | `PatientPortalServiceImpl` 2 → 0 (the reason is the appointment's `statusReason`) |
| `config/UploadStoragePersistenceCheckTest` + `UploadStoragePersistenceCheck` (new) | D1184: an upload directory not on the persistent volume (dev: volume mounted at `"/data/uploads "` with a trailing space, `APP_UPLOAD_DIR=/data/uploads`). Startup INFO of each resolved directory; WARN when it is not under `RAILWAY_VOLUME_MOUNT_PATH` (whitespace named), when no volume is attached on Railway, or when `/proc/self/mountinfo` puts it on the root filesystem. | The upload root and the patient-photo directory |
| portal `scripts/lib/stored-file-download-refusal.test.mjs` (new) | D1184: a stored-file blob download whose refusal (404 "n'est plus disponible") is shown as a generic toast, the JSON body arriving as a Blob. Every `.downloadDocumentBlob/downloadBlob/getAttachmentBlob/getPhotoBlob(…).subscribe` error handler that toasts must use `blobRefusalReason`. | Every `.ts` of `src/app`; 0 pins |

## Guards added or widened by round 22 (fix/r22-portal-frontend)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/terminal-action-reason.test.mjs` (widened) | D1180 / D1148 (REGRESSION): a server contract half shipped — #1001 required `{ reason }` to revoke a directive and the portal still sent `{}`. The scan now reads hospital-core's `CancelVoidReasonGuardTest` (every handler that requires a reason, and where: `DTO.field`, `body.field`, `@RequestParam name`) and fails any portal call to such a handler that does not send it. Empty bodies are read as `{}`, `null`, `undefined` or nothing; URL bases (`${this.baseUrl}`) are resolved from the file. | Every portal `.ts`; the directive pin is gone; 5 pins of non-clinical endpoints, each with why |
| `scripts/lib/no-native-prompt.test.mjs` (new) | D1201: a reason asked with `window.prompt` (a blank answer did nothing, silently). | Every portal `.ts`; 0 (6 sites moved to `<app-reason-dialog>`) |
| `scripts/lib/focus-after-action.test.mjs` (new) | D1202 / D1198 / D1199 / D1197: focus fell to the page body when the control that held it went away. `shared/a11y/focus-rescue.ts` is installed at start-up (app.config.ts) and moves focus to the nearest surviving part of the page. Also D1131 / D1190 / D1195 / D1208: every dialog directive (appModalDialog, appDialogFocus, appDialogA11y) builds `EscapeKeepsTypedText` — Escape over typed text asks first. | The two choke points |
| `dialog-scan.mjs` `focus` rule (tightened) | D1208: a bare `cdkTrapFocus` no longer counts as focus management (it keeps no typed text). The lab's 23 cdkTrapFocus dialogs use appModalDialog. | Every template; 0 |
| `scripts/lib/inline-editor-focus.test.mjs` (widened) | D1191 / D1195: a row editor that SAVES on Escape (`(change)`/`(blur)` fire as the field is destroyed) or drops typed text unasked. Every `@if (editingX() === …)` block (now also `=== 'section'`) commits on Enter or a button and is an `appInlineSubForm` when it holds a text field. | Every template; the billing pin is gone (the line editor is an appInlineSubForm row); 4 patient-note siblings fixed |
| `scripts/lib/server-refusal-shown.test.mjs` (widened) | D1189 / D1124: a write's error handler showing only a generic toast. Read 10 screens; now every component and service. | Every portal `.ts`; 0 (105 handlers in 50 files fixed) |
| `scripts/lib/required-form-submit.test.mjs` (new) | D1193 / D1089: a `<form>` with required fields whose submit checks nothing (Angular forms are `novalidate`). Its submit carries `[appSubmitGuard]` or `submitGuardRequired` (the guard names the blank required fields). | Every template; 0 (26 forms fixed) |
| `disabled-control-scan.mjs` (fixed) | D1206: `requiredTextMissing()` passed as "a request in flight" (it ends in -ing). A data gerund at the end of a name is data. | Every template; 0 |
| `scripts/lib/glued-label.test.mjs` (new) | D1200: a value glued to a translated word (`{{ n }}{{ 'X.MIN_WAIT' \| translate }}`, an English plural `'s'`). The value goes into the sentence as a parameter. | Every template; 0 (flow board, 3 dashboard sites) |
| `scripts/lib/badge-spacing.test.mjs` + `badge-spacing-scan.mjs` (new) | D1205 / D1167 / D1137: a status pill glued to the inline element or interpolation before it; a `&ngsp;` right after a block's `}` is dropped by Angular (shown by the lab-results spec). | Every template; 0 (142 pills in 72 templates spaced with `{{ ' ' }}`) |
| `scripts/lib/lab-result-picker.test.mjs` (new) | D1204 / D1176: the "Nouveau résultat" picker offering an order the server refuses. The picker's excluded statuses equal `LabResultServiceImpl.requireSpecimenTaken`'s. | lab-results.ts against the server rule |

## Guards added or widened by round 23 (fix/r23-portal-staff-shared)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/dialog-stacking.test.mjs` (widened) + `lib/nested-dialog-scan.mjs` (new) | D1209 / D1201 (REGRESSION of the D1201 fix; D1122 class): a dialog opened from INSIDE another dialog that stays open, painted under it (bed board: Précautions `.modal` z-index 1001 over the shared reason dialog's backdrop 1000). The scan enumerates every such pair (DOM nesting, or a handler bound inside dialog A that sets dialog B's open condition without closing A; a refresh in a `.subscribe` callback is not an open). Each pair must be listed with the mouse-click spec covering its shape; both ends must be built on a dialog directive; the three directives must open/raise/close a layer of `shared/a11y/overlay-stack.ts` and follow its `isTop()` for the keyboard. | Every template; 19 pairs listed (bed board → its own spec; the others → `overlay-stack.spec.ts` shapes) |
| `shared/a11y/overlay-stack.ts` + `overlay-stack.spec.ts` (new) | D1209: one stack orders every open dialog (z-order and keyboard); a layer opened over others is lifted above the highest when it renders. The spec clicks the child's confirm with the mouse (`testing/mouse-click.ts`, `elementFromPoint`) over a modal, a side panel and an appDialogFocus parent, for the shared reason and confirm dialogs and a dialog written inside its parent; Tab and Escape stay in the child. | The three dialog directives |
| `scripts/lib/dialog-button-labels.test.mjs` (new) | D1219: two buttons of one dialog with the same label and different actions (transfer cancel: "Annuler" / "Annuler"), and every `<app-reason-dialog>` / `<app-confirm-dialog>` whose confirm and dismiss keys read the same, in en/fr/es. | Every template; 0 |
| `scripts/lib/focus-after-action.test.mjs` (widened) | D1220 (the D1191/D1195 focus sibling): a late focus return asking "is focus on the body?" never runs once the focus rescue put focus on its stand-in (the price TD, the lines TBODY). Every late return asks `focusFell()`. | Every portal `.ts`; allowlist: focus-rescue.ts (defines it), focus-return.ts (records only) |
| `scripts/lib/search-rerun.test.mjs` (new) | D1216: a debounced server search that drops a query equal to the previous one (`debounceTime` → `distinctUntilChanged()` → `switchMap`), so the same patient name typed after the dialog reopened sent nothing. | Every portal `.ts`; 16 pipes fixed; 2 pins (reception waitlist/recalls panels, owned by the patient/desk branch) |
| `scripts/lib/row-pill-keys.test.mjs` + `row-pill-keys.mjs` (new) | D1222: a row's status pill reusing a count label key that is plural in es/fr ("Pendientes de publicación" on one result). | Every template; 0 |
| `shared/person-search.spec.ts` (widened) | D1218: an MRN ("CHW0410") stripped to its digits matched a phone (+22670004101). `phoneMatches` refuses a term with a letter; the exact identifier is listed first. `guard:person-search` keeps every people filter on these helpers. | The one phone matcher |

## Guards added or widened by round 23 (fix/r23-portal-patient-desk-billing)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/terminal-action-reason.test.mjs` (widened, rule 3) | D1217: a reason gate for a cancel or void that exempts a record by its status. `cancellingIssued()` skipped `'DRAFT'`, so draft invoices were cancelled with `cancelReason: null`. Any member named cancel / void / reason whose body names `'DRAFT'` fails. | Every portal `.ts`; 0 |
| `scripts/lib/date-range-bounds.test.mjs` (new) | D1215: (1) a date or datetime-local input that ends a range (id, name or model says to / end / ends / until) with no lower bound bound to its start; (2) a front-desk date input that is neither bounded below nor pinned with why it may be in the past. A write form also refuses on submit (`shared/forms/date-rules.ts`). | Rule 1: every template, 0 (16 range ends now bound: the waitlist, the billing filter and 14 in other screens). Rule 2: `reception/`, 3 pins (cockpit day, waitlist window start, EMPI DOB search) |
| `scripts/lib/portal-failure-reason.test.mjs` (new) | D1212: the patient portal shows a failure (an error / failed flag or an error toast) without the server's 4xx reason. | Every `patient-portal/**/*.ts`; 0 (13 handlers fixed) |
| `scripts/lib/server-refusal-shown.test.mjs` (fixed) | D1212 sibling: `READ_KEY` matched `UPLOAD_FAILED` as a read because of the `LOAD` inside `UPLOAD`. It now reads `(?<!UP)LOAD`. | 1 site found and fixed (my-documents upload) |
| `scripts/lib/raw-server-message.test.mjs` (pins shrunk) | D1156 class in the patient portal and billing. | patient-portal 8 → 0; billing.ts 11 → 10 |

## Guards added or widened by round 23 (fix/r23-portal-siblings)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/terminal-action-reason.test.mjs` (widened, rules 4-5) | The D1180 class widened (round 23: D1210, D1183, D1217): a server write rule that refuses unless a field is sent, and no portal screen can send it. #1011 refused an early dose without `earlyDoseReason` and a signature the allergy stop applies to without `allergyOverrideReason`; the portal's request types could not carry either, so the eMAR and the sign button were dead ends. Rule 4: each conditional contract (endpoint, field, DTO, refusal key) is checked against the server (the DTO declares the field, the key is in the bundles) and the portal (every call to the endpoint can carry the field through its body, method or declared body type, and a screen outside `services/` asks for it). Rule 5: every String `…Reason` field of a write request DTO (`payload/**/*Request*.java`, 34 files) is named by a portal source, or pinned with why. | 5 contracts (administer `earlyDoseReason`, sign `allergyOverrideReason`, create/edit `overrideReason`, invoice `cancelReason`); 0 reason pins |
| `scripts/lib/phone-field.test.mjs` (new) | D1211 (portal half): a phone input checked only by the server (`@PhoneNumber`, 27 fields), so a non-number came back as a banner after the save. Every phone input (`type="tel"`, or an id/name/model that says phone) carries `appPhoneField` (`shared/forms/phone-field.directive.ts`: the server rule `isFacilityPhone`, message on the field), and its component imports the directive (Angular ignores an unimported attribute directive silently). | Every template (`.html` and inline); 22 inputs; 2 pins (login bootstrap phone, user-list login phone: each has its own stricter check) |

## Guards added by the i18n review (fix/i18n-review-r22)

| Guard | Class | Scope |
|---|---|---|
| `scripts/lib/sentence-case.test.mjs` + `sentence-case-scan.mjs` (new) | i18n review 2026-11-01: English title case copied into a French or Spanish label ("Mode de Paiement", "Fecha de Nacimiento", "Oui, Annuler"), 232 of the review's 330 typography findings. A capitalised word that is not the first word and does not follow `. ! ? : ; — – - [ / … « " “ ¿ ¡` (a word right after `(` is read) fails. Not read: text in guillemets (a UI label quoted by name: `utilisez « Désactiver »`), identifiers glued to a capital or a digit (`SpO2`, `RxNorm`, `MenAfriVac`), example placeholders (`ex. Or, Argent`) and listed proper nouns, brands and units (`Burkina Faso`, `Rh`, `Mo`, `Google Authenticator`). | `fr.json` and `es.json`; 0 (790 values lowered, 45 rewritten by hand). The backend bundles are not read: their capitals are status and button names quoted in sentences and proper nouns (93 hits, none title case except 3 Spanish e-mail role labels, fixed). |
