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
| `architecture/PartialUniqueReleaseFlushGuardTest` | A row released from a partial unique index with a plain `save()`/`delete()`, then its replacement built in the same method: Hibernate runs INSERTs first, so PostgreSQL refuses it every time and H2 never sees it. Also lists every partial unique index in the migrations, so a new one has to name its entity. | CX-011 | **0** |
| `architecture/MicroCultureOutcomeGuardTest` | A validated culture report changed outside the one rule: a write to its outcome, CORRECTED stamp or isolates outside `MicroCultureServiceImpl`; a public change that skips the row lock; a second place stamping CORRECTED; an isolate removal or outcome write that does not reach `requireConsistentOutcome`. | CX-009 | **0** |
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

### Round 24 (`fix/r24-clinical-text-and-attribution`)

| Guard | Class it stops | QA defects | Allowlist today |
|---|---|---|---|
| `security/ClientSuppliedIdentityGuardTest` (extended) | The endpoint scan now reads closing-actor fields (`discontinuedBy…`, `liftedBy…`, `resolvedBy…`, `closedBy…`, `voidedBy…`, `rejectedBy…`, `reversedBy…`, …); `everyClosingWriteRecordsTheSessionActor` reads every `set/…By*` write of a discontinue, lift, resolve, revoke, close, cancel, void, reject, reverse, archive, suspend or complete in the services and refuses one fed from the request other than through `CallerIdentity.resolveActor/confirmActor` (which falls back to the caller when the screen names nobody). | D1226 | **0** (the isolation lift is `CHECKED` in the endpoint list) |
| `i18n/ExceptionProseRatchetTest` (extended) | `noProseHandedToAThrowingHelper`: a sentence (literal or same-file constant) handed to a helper that throws its String parameter (`normalizeRequiredText(v, "Clinical indication is required…")`, `findPlanInReach(u, id, MSG_DENIED)`), which the constructor scan never saw; `roundTwentyFourClinicalFilesHoldNoProse`: the clinical record's services (imaging reports, isolation, cultures, deaths, chart updates, intake/output, vitals, nursing notes, treatment plans and consents, high-risk pregnancy plans) at zero whatever the baseline says. Baseline lowered by 85. | D1232 | thrower scan: **0** (FHIR package outside, English OperationOutcomes by convention); clinical files: 2 internal SHA-256 failures |
| `i18n/DisplayDateFormatGuardTest` | A day-month display pattern (`dd/MM`, `MM/dd`, `d MMM`) or a raw date/time handed to a refusal or resolved message (exception constructors, `MessageUtil.resolve`, `messageSource.getMessage`, `getLocalizedMessage`, `NotificationText.of`) without `DisplayDates`. | D1234 | 1 parsing-only site (bulk-import date reader) |
| `i18n/DrugDisplayNameGuardTest` | A refusal, check reason or message array that names a catalogue drug with a raw `getNameFr()/getGenericName()/getBrandName()`, or a file other than `DrugDisplayNames` choosing a catalogue name by language. | D1235 | 1 audit array (English audit row, stored name) |
| `cdshooks/bpa/BpaLatestReadingClaimGuardTest` | A BPA card text claiming "latest / most recent / currently" (EN/FR/ES) whose rule does not read the newest value. | D1231 | 3 true claims, each with the method that makes it true |

### Round 26 (`fix/r26-backend-validation`)

| Guard | Class it stops | QA defects | Allowlist today |
|---|---|---|---|
| `repository/StringFunctionNullParameterGuardTest` | A query parameter handed bare to a string function (`LENGTH(:p)`, `SUBSTRING(:p, …)`, `LOWER(:p)`, `CONCAT('%', :p, '%')`, `TRIM`, `COALESCE`, `LOCATE`, `REPLACE`, `\|\|`): a null reaches PostgreSQL as bytea and the whole statement fails ("character_length(bytea) does not exist"); H2 accepts it. Reads every `@Query` (value and countQuery, constants assembled) and every other query literal. | D1253 | **0** |
| `repository/RepositoryNullTextParameterPostgresIT` | The same class on the real engine: every read-only `@Query` with a text or date-time parameter is run on PostgreSQL (Testcontainers, schema from the changelog) with those parameters null. `PatientSearchPostgresIT` runs both patient searches over all 128 + 16 filter combinations. | D1253 | **0** |
| `architecture/RequiredRequestIdGuardTest` | A missing request id that reaches a lookup and answers 404 "… ID : null": a constrained body bound without `@Valid`, a lookup of `request.getXId()` with no `@NotNull` and no field-named refusal first, a null-id refusal that names no field. | D1258 (D1245 server half) | bodies: 1 (`StaffController#update`, a partial edit; create validates its `OnCreate` names since round 30, and the enum `@NotBlank`/`@Size` debt is gone); lookups: 3 internal/optional, each with its reason |
| `architecture/CloseReasonWordingGuardTest` | The order-cancellation wording used by a closing action that is not a cancellation (deactivate, withdraw, revoke, reject, close); the shared close-reason body carrying one action's wording. | D1267 | **0** |
| `i18n/FieldLabelCoverageTest` | A request field a default-message refusal can name with no `field.label.<name>` in EN, FR and ES (the raw property inside a French sentence). | D1268 | **0** (parsed scalars without a default-message constraint: standing debt) |
| `i18n/InBasketKeyedTextGuardTest` | An In-Basket producer that stores a title or body as prose without its key, so every reader sees the writer's language. | D1269 (D1053 class) | 1 user-typed body (lab rejection reason) |
| `i18n/DisplayDateFormatGuardTest#noJdkGluedDateTime` (extended) | A date-time glued by the JDK's locale pattern (French has no comma before the time) outside `DisplayDates`. | D1266 | **0** |
| `service/pharmacy/StockWriteOffReasonTest` (extended) | A write-off reason in free text (any word, any position, EN/FR/ES, gender/number) read as a two-way correction. | D1243, D1261 | **0** |

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

## Guards added or widened by round 24 (fix/r24-portal-proxy-and-ui)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `architecture/SwallowedNestedTransactionGuardTest` (new) | D1212 (reopened, round 24): a `try/catch` that does not rethrow, wrapped around a call that JOINS the caller's transaction (a Spring Data repository, or a bean method `@Transactional` without `REQUIRES_NEW`/`NOT_SUPPORTED`/`NEVER`, or in a `@Transactional` class). The nested interceptor marks the shared transaction rollback-only, the swallowed failure returns at the outer commit as `UnexpectedRollbackException`, and the page answers 500 (the proxy `/records` for a cross-hospital grantor, four rounds running, because mocked unit tests have no transaction). Best-effort reads go through `service.support.IsolatedReads` (own read-only transaction). Backed by `controller/PatientProxyCrossHospitalIT` (all five proxy endpoints × cross/same-hospital grantor × with/without `X-Hospital-Id`, real Postgres). | Every main source file that declares `@Transactional`; 71 pre-existing call sites (66 entries) pinned as standing debt (repository reads/writes, the bulk user import, the (pre-)check-in consent capture), each with its reason; may only shrink |
| `architecture/StoredReasonExposureGuardTest` (new) | D1225: a `reason` / `*Reason` / `reasonFor*` column stored on an `@Entity` that no read DTO of that entity declares, so no screen can show it (the MAR's early-dose, hold, refusal and five-rights override reasons). | Every `@Entity` reason field (82); read models = payload classes named after the entity plus an explicit map; 14 fields pinned as standing debt |
| `scripts/lib/mar-reason-surfaces.test.mjs` (new) | D1225 portal half: a screen that reads MAR rows (its `.ts` names `NurseMedicationTask`) without `<app-mar-dose-history>` (dose history with reason, override reason, giver, witness). | nurse-station, eMAR; 0 |
| `scripts/lib/no-native-prompt.test.mjs` (widened) | D1229: the browser's `confirm()` and `alert()` (not only `prompt()`), bare or through `window`/`globalThis`/`self`; a method declared with the name is not a call. A yes/no goes through `ConfirmService.ask()` (shared `<app-confirm-dialog>`). | Every portal `.ts`; 0 (15 sites converted) |
| `scripts/lib/dialog-scan.mjs` rule `panel-escape` (new, run by `npm run guards`) | D1230: a `*-panel` element holding its own no-argument close button (`close…()` / a flag set off) that Escape does not close: no `(keydown.escape)` on it or an ancestor, no `appInlineSubForm` + `(dismissed)`, not a dialog. | Every template; 0 (chat new-conversation, microbiology culture detail, data-residency edit panel fixed) |

## Guards added by the i18n review (fix/i18n-review-r22)

| Guard | Class | Scope |
|---|---|---|
| `scripts/lib/sentence-case.test.mjs` + `sentence-case-scan.mjs` (new) | i18n review 2026-11-01: English title case copied into a French or Spanish label ("Mode de Paiement", "Fecha de Nacimiento", "Oui, Annuler"), 232 of the review's 330 typography findings. A capitalised word that is not the first word and does not follow `. ! ? : ; — – - [ / … « " “ ¿ ¡` (a word right after `(` is read) fails. Not read: text in guillemets (a UI label quoted by name: `utilisez « Désactiver »`), identifiers glued to a capital or a digit (`SpO2`, `RxNorm`, `MenAfriVac`), example placeholders (`ex. Or, Argent`) and listed proper nouns, brands and units (`Burkina Faso`, `Rh`, `Mo`, `Google Authenticator`). | `fr.json` and `es.json`; 0 (790 values lowered, 45 rewritten by hand). The backend bundles are not read: their capitals are status and button names quoted in sentences and proper nouns (93 hits, none title case except 3 Spanish e-mail role labels, fixed). |

## Guards added or widened by round 24 (fix/r24-prescribing-safety-authz)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `service/DrugAllergyBrandEntryPointsTest#everyReaderOfAnOrdersCatalogueItemIsClassifiedAndTheChecksUseTheAgreementRule` (widened) | D1223 (D1149/D1183 NOT_FIXED): one input hides another. An order names its drug twice (typed name, catalogue item by id or code) and a safety check read only the item, so "Bristopen" sent with paracetamol's id was created and signed for a penicillin-allergic patient. Every main file that reads the catalogue item behind an order (`getMedicationCatalogItemId()`, `findByHospitalIdAndCode(`, `resolveCatalogItem(`, the RxNorm lookup, `proposedCatalogItem()`, `.catalogItem()`) is either an identity check that asks `OrderedDrugIdentity` (or `AllergyDrugIdentity.of`, which asks it) or is listed as not a check with why; `AllergyDrugIdentity` must call `OrderedDrugIdentity.agrees(`; `OrderedDrugIdentityRule` must stay a Spring `CdsRule`. | 4 checks (prescription service, dispense DRUG check, CDS allergy hook, engine rule); 10 not-a-check files, each with why; stale entries fail |
| `service/PrescriptionServiceImplTest#d1223_createMatrix / d1223_editMatrix / d1223_signMatrix` (new) | D1223: the create, edit and sign paths x penicillin and sulfonamide allergy x free text / catalogue / mismatched catalogue id / stale id on edit. Free text and catalogue end on the critical allergy card, a mismatched or stale id on the `prescription.catalog.nameMismatch` refusal; nothing is saved or signed. | 22 cases |
| `config/PreAuthorizeMatcherPairingTest` (allowlist shrunk 11 -> 2, two tests added) | D1224: a blanket `/patients/**` matcher (admin-only DELETE, PUT without the pharmacist) shadowed the `@PreAuthorize` of chart sub-resources, so a clinician got 403 deactivating an allergy or removing a diagnosis. The 9 accepted `/patients` pairs are gone (allergy DELETE x3, PUT pharmacist, diagnosis DELETE, photo DELETE x4); a new test refuses any accepted pair under `/patients`, another proves the clinicians pass the edge on allergy and diagnosis writes while whole-patient DELETE stays HOSPITAL_ADMIN/SUPER_ADMIN. | 2 lab pairs left (another branch's files) |

## Guards added or widened by round 25 (fix/r25-clinical-records-api)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `architecture/R25FieldNamedRefusalGuardTest` (new) | D1245 (D1089 / D1124 NOT_FIXED four rounds: the server half was never enumerated): a request body refused with an EMPTY `fieldErrors` (a class-level `@OneOf` rule) or a blank / unparsable UUID, enum or date not named. Every `@RequestBody` type of every controller: an empty `@Valid` body yields only violations with a property path; every UUID / enum / date field sent `""` and `"not-a-value"` comes back from `GlobalExceptionHandler` with `fieldErrors` naming it (the date case found that 54 date fields were never named: the handler read only the most specific cause, a `DateTimeParseException`). Also D1246 (every `upsertDischargeSummaryForCheckout` caller goes through `AfterVisitSummaryContent.require`) and D1248 (every vaccination save goes through `refuseDuplicateDose`). | 0 allowed |
| `architecture/TerminalReasonOverwriteGuardTest#activeFlagClosersReadTheFlagFirst` (new test) | D1242 (sibling of D1186 missed by the round-22 scan, which read only `set…Reason` columns: the allergy's reason was never stored): a `deactivate…/delete…/remove…/retire…/revoke…/archive…/suspend…/withdraw…` method that switches `setActive(false)` without reading the flag first, so a second call answers 200 and writes another trace. The birth plan's `isWithdrawn()` is now a recognised precondition. | 15 catalogue / platform closers, each "not a patient's record" |
| `architecture/CancelVoidReasonGuardTest#everyDeleteIsListedWithItsReasonProof` (new test) + `CLINICAL_VOID_PATH` widened with `deactivat` | D1241 / D1247: a DELETE of something recorded with no reason (the co-signed birth plan), a "deactivate" with an empty body (the guarantor). Every DELETE handler (69) is listed with its reason proof, OWN RECORD, NOT A CLINICAL RECORD, or KNOWN GAP; 8 deactivate handlers joined the clinical-withdrawal list. | 14 KNOWN GAP (D1241 class) entries, each in tasklist standing debt |
| `architecture/EnteredInErrorReaderGuardTest` (new) | D1240: a reader of problem-list rows that lists a diagnosis removed as entered in error (the patient's medical history showed it five times; the D1214 fix covered the active lists only). Every `PatientProblemRepository.find…` caller filters `isEnteredInError()` or reads only ACTIVE statuses; every aggregate query excludes `enteredInError`. | 4 staff readers that show the removal on purpose |
| `i18n/ResponseProseGuardTest` (allowlist shrunk) | D1249: "Allergy entry deactivated." / "Diagnosis removed successfully." answered in English to FR/ES. | 2 entries removed |
| `repository/PatientNameRankingSearchTest` (new) | D1239: a capped name search that cuts its matches at the page limit unranked ("Awa" → 8 Sawadogo). `searchPatientsExtended` / `searchPatients` order by `PatientRepository.NAME_RANK_ORDER` before the limit. | the one query behind every picker, the duplicate check and FHIR |

## Guards added or widened by round 25 (fix/r25-mar-cds-pharmacy-billing)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `architecture/MarFrequencyReaderGuardTest` (new) | D1237: a schedule read from a free-text frequency outside the one reader ("toutes les six heures" was not read and ended the course), and D1236: a schedule read from a dose row's time outside `MarNextDose` (the next dose was the last slot plus the interval, whatever time it was really given). Rule 1: no main file but `MarDoseSchedule` inspects a frequency text. Rule 2: whole-hour `intervalHours(` (null for an unread frequency and for "5 fois par jour") only where a whole-hour regimen is the point. Rule 3: no main file adds time to a dose row's `getScheduledTime()` / `getAdministeredAt()`. Rule 4: a file that handles MAR rows and reads a frequency is `MarNextDose` or `MarDoseInterval`. | Every main file; 1 whole-hour reader (`PrescribedQuantityEstimate`, with why); stale entries fail |
| `service/impl/MarDoseScheduleTest#d1237_everyHourWordInEveryLanguageReadsAsItsNumber` (new) | D1237: every number of hours 1-12 and 24 written as a word, in the French, Spanish and English forms ("toutes les %s heures", "cada %s horas", "every %s hours"), reads as that number. | 39 phrasings |
| `cdshooks/bpa/BpaAcknowledgementCoversWhatItSawTest` (new) | D1238: an acknowledgement keyed on rule + level hid every later reading for 24 hours. For EVERY `BpaRule` class (found by source scan), a triggering reading entered after the acknowledgement brings the card back; nothing new, or a reading that does not trigger, keeps it acknowledged; a worse level brings back a milder acknowledged card. A new rule without a triggering reading here fails. | 5 rules |
| `service/pharmacy/StockWriteOffReasonTest#everyPortalReasonCodeHasADirection` (new) | D1243: a write-off reason with a positive quantity added stock. Every adjustment reason code the portal offers (and the legacy codes it maps) is classified server-side as a write-off (only removes) or a two-way correction. | 7 codes |
| `architecture/InvoiceBalanceGuardTest#aReceiptPrintsOneMomentOnly_D1244` (widened) | D1244: a receipt mixing two moments (today's total beside the amount paid then). Every main call to `generateReceiptPdf` passes the total of its own moment (seven arguments), and the method assembling it reads no `invoice.getTotalAmount()` / `invoice.getAmountPaid()`. | 1 caller (`renderReceiptPdf`, staff and portal receipts) |

## Guards added or widened by round 25 (fix/r25-portal-forms)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/required-form-submit.test.mjs` (widened, rules D1245) + `scripts/lib/required-field-scan.mjs` (new) | D1245 (D1089 / D1124 NOT_FIXED again in round 25): a required input the submit path never checks. The round-22 rule read only a `<form>` whose controls carry `required`; the prescription dialog is a `.modal` with a `(click)` "Créer" and its "Nom du patient *" search input had only the star, so `patientId: ''` was sent. Rule `star-undeclared`: a control whose `<label>` shows `*` declares it (`required`, `[required]`, `aria-required`, `required` on `<app-patient-picker>`). Rule `unchecked`: every declared-required control is checked by its scope — the closest `<form>`, dialog (`role="dialog"`, `<dialog>`, `.modal`, `appDialogA11y`, `appModalDialog`) or `[data-submit-scope]` section, the same list as the guard's `root()` — through `submitGuardRequired`, its id in `[submitGuardFields]`, or a handler that refuses field by field. A field-group component (`<app-provider-evidence-form>`) is checked where it is used. Rule 3: a template that uses the guard imports `SubmitGuardDirective` (a bare `appSubmitGuard submitGuardRequired` without it is two inert attributes). The directive now refuses a listed field that declares `required` and is blank whatever its expression says, reads `aria-required="true"`, honours `data-submit-blank` (a typeahead holding a query but no pick) and `[data-submit-scope]`. | every template; 223 starred controls declared, ~70 scopes guarded; 0 allowed (the shared picker's own template is its hosts') |
| `scripts/lib/terminal-action-reason.test.mjs` (widened) | D1247: a one-click `/deactivate` with an empty body (the guarantor). `deactivate` joins cancel / close / void / revoke / discontinue / withdraw / reject. | 6 new pins, all configuration or access records (scheduled reports, role assignments, interaction rules, partner staff, visit types, session templates), each with why |
| `scripts/lib/document-type-filter.test.mjs` (new) | D1252: a list filter that cannot reach a type the server returns (the patient's documents filter reused the upload list and lost the medical certificate). The portal's `PatientDocumentType` union equals the server's enum, the labels are a `Record` over the union (a missing label does not compile) and the filter iterates every type. | /my-documents |
| `shared/person-search.spec.ts` `rankPeopleByName` + `patient-picker.component.spec.ts` (new cases) | D1239 (portal half): a patient typeahead that cuts the list before ranking ("Awa" filled eight places with Sawadogos). Every patient typeahead (the shared picker and the 11 hand-written ones) ranks exact / name-start / word-start before contains, then cuts; the shared picker asks for 20 and shows 8. | 12 typeaheads |

## Guards added or widened by round 26 (fix/r26-mar-schedule)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `service/impl/MarScreenServerAgreementTest` (new) | D1254 (REGRESSION of the D1236 fix) / D1262: the MAR status every screen keys on disagreeing with the administer call. Over 10 frequencies (hourly to daily, counts that do not divide the day, number words, an unread one) x 4 last outcomes (GIVEN, HELD, REFUSED, MISSED) x 5 lateness values x every 7 minutes for 30 hours (~51 000 cases), using the real `resolveMarStatus`, `slotRuleAllows` and `MarDoseInterval`: an offered dose passes the slot rule; offered and flagged `earlyDose` asks for a reason, offered and not flagged does not; a dose the server accepts is offered (only a missed backlog slot inside the gap may wait, D1236); right after any outcome the row is never OVERDUE. | 0 |
| `service/impl/MarDoseScheduleTest#d1263_everyCountOfDosesADayInEveryLanguageAndFormIsRead` (new) | D1263 (sibling of D1237): a count of doses a day the reader does not know ("cinq fois par jour"). Every count one to twelve, as a word in FR / ES / EN and as digits, in 8 "per day" forms, reads as 24 h / n; the word list is shared with the hours test, so the two readers cannot drift. | 192 phrasings |
| `NurseTaskServiceImplTest#d1255_*` + `NurseTaskControllerTest#getOrdersDefault / getHandoffsDefault` (changed) | D1255: a worklist silently cut (the MAR at 20 rows for the hospital, vitals at 20, the order queue and the pending handoffs at 6). The MAR and vitals return every row, soonest first; the order queue and pending handoffs return every item unless the caller asks for a limit (`NurseTaskService.NO_LIMIT`); the completed handoffs (a history) keep 6. | MAR, vitals, orders, handoffs; announcements and the inbox keep their explicit feed lengths |
| `scripts/lib/mar-reason-surfaces.test.mjs` (widened) | D1254 / D1265: a MAR screen that hides an early dose, or titles the unreadable-frequency refusal "Dose before the minimum interval". Every component reading `NurseMedicationTask` renders the row's `earlyDose` flag and takes the reason dialog's keys from `earlyDoseDialogKeys` (early-dose.ts). | nurse station, eMAR; 0 |
| `scripts/lib/required-field-star.test.mjs` (new) | D1272: a field the submit guard names when it refuses ("Patient, Sens") whose label shows no star. Every id in a `[submitGuardFields]` has a `*` in its visible `<label for>` (a visually hidden label: the control declares `aria-required`). | every template; 3 pins with why (fields required for one action, or one of several) and 23 pins of the same class in screens owned by fix/r26-portal-forms (to be removed there; stale pins fail) |

## Guards added or widened by round 26 (fix/r26-portal-forms)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `shared/forms/submit-guard.directive.ts` (choke point) + `scripts/lib/required-form-submit.test.mjs` rule `QA D1256` (new) + `required-field-scan.mjs#partialGuardFindings` | D1256 / D1257 (D1089 / D1124 / D1245 NOT_FIXED again in round 26): a refusal that names only part of the blank required fields. The directive read the form's required controls only when the expression let the click through, so an empty prescription named only the drug, the handoff only "Patient, Sens", the stock transaction only its type, and the lab order / Nouvelle consultation (patient tested by the expression, never listed) fell to the generic sentence. Now every refusal reads every field the guard is about (listed + every rendered required control under `submitGuardRequired`). The scan fails a guarded submit without `submitGuardRequired` whose scope holds a required control it does not list, unless another submit of the same scope carries it (a secondary action); a secondary action with fields of its own sits in its own `data-submit-scope`. | every template; 9 found by the first run, all fixed (billing invoice, ultrasound order, walk-in, mark-ready; OTP, referral message and three procedure-order sub-forms scoped); 0 allowed |
| `required-form-submit.test.mjs` rule `QA D1272` + `required-field-scan.mjs#unstarredRequiredFindings` (new) | D1272: a required control whose label shows no star ("Code du test", "Nom du test"). Every control that declares `required` and has a visible label shows `*` in it, written in the template or in the label's translation (checked in FR, EN and ES; a conditional `' *'` counts). | 35 found by the first run, all starred; 0 allowed |
| `required-form-submit.test.mjs` rule `QA D1272` (listed) + `required-field-scan.mjs#listedUndeclaredFindings` (new) | D1272, other half: a field a guard refuses blank (listed in `submitGuardFields`) that declares nothing (the handoff's "Patient" and "Sens"). | 30 found; 2 fixed (mortality and transfusion patient pickers), 28 pinned with owner and reason (8 nurse-station / eMAR → fix/r26-mar-schedule); stale pins fail |
| `scripts/lib/list-escape.test.mjs` (new) + `shared/a11y/list-escape.directive.ts` + `comboboxKey` | D1259 (D1251 sibling): Escape over an open suggestion list closed the whole dialog. Every suggestion list (`role="listbox"`, `picker-dropdown`, `autocomplete-list`, `suggestions…`, `…__suggestions`, `…__popup`, `…typeahead__list`, `picker-results`) belongs to a field that handles Escape (`[appListEscape]` or a `(keydown)` handler; a dialog's or backdrop's own Escape never counts). | 18 lists; 15 fields fixed; 0 allowed |
| `scripts/lib/badge-spacing.test.mjs` rule `QA D1271` + `badge-spacing-scan.mjs#gluedHints` (new) | D1271: a hint / meta / sub-line element glued to the text before it ("CHW7213(3 nov. 1993)"). | 74 spaced across 40 templates; 1 pinned (eMAR queue meta → fix/r26-mar-schedule) |
| `scripts/lib/focus-after-action.test.mjs` rule `QA D1260` + `shared/a11y/focus-return.ts` | D1260 (D1004 sibling): a dialog opened from inside a dialog that closes in the same click returned focus to the page root. Every dialog directive registers its box with its opener; `restoreFocus` walks the opener chain. | 3 dialog directives |

## Guards added or widened by round 27 (fix/r27-portal-markers-focus)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `shared/a11y/focus-rescue.ts` + `focus-return.ts` (choke point) + `scripts/lib/focus-after-action.test.mjs` rules `QA D1283` (new) + `shared/a11y/focus-after-success.spec.ts` (new) | D1283 / D1284 (D1004 / D1260 NOT_FIXED in round 27; the D1270 sibling): focus lost to the page component after a SUCCESSFUL dialog action. The screen reloads its list under `@if (!loading())`, so the row button that opened the dialog leaves with the table, and the rescue's nearest surviving ancestor was `APP-NURSE-STATION` / `APP-LAB` (the eMAR `SECTION`). Escape passed: a cancel reloads nothing. Now an opener not on the page when its dialog closes is handed to the rescue (`awaitReturn`); its twin (same control, same row signature) takes focus when the list is back, or the row's first action when the control left its row; meanwhile focus waits on the place the table names (`data-focus-return`) or the page heading, never a component host. The scan holds every dialog of the portal (254) to the three dialog directives, each directive to `restoreFocus` on destroy, `restoreFocus` to `awaitReturn`, and the rescue to "twin first, never a host". The Karma spec drives each directive through the reload; the nurse station, eMAR and lab specs drive the reported screens. | every template; 0 allowed |
| `scripts/lib/badge-spacing.test.mjs` rule `QA D1288` + `badge-spacing-scan.mjs#gluedLifecycleNotes` (new) | D1288 (D1271 NOT_FIXED in round 27): the inactive-guarantor and inactive-allergy notes glued the time to "Motif". The D1271 rule read elements by class name (`-hint`, `-meta`); these spans had none. Every inline element that shows a lifecycle reason (`…Reason`) or close time (`deactivatedAt`, `revokedAt`, `suspendedAt`, `cancelledAt`, `archivedAt` …) must not run into the text before it. | 18 sites in 12 templates found by the first run, all spaced (the reported notes now read "02/11/2026 12:27 · Motif : …"); the eMAR queue-meta pin of the D1271 rule deleted (spaced); 0 allowed |
| `scripts/lib/required-field-star.test.mjs` (shrunk, widened) | D1280 (D1272 NOT_FIXED in round 27): the 17 "another branch's screens" pins outlived round 26. All starred and declared (assign bed, bed transfer destination / reason / override, precaution reason, lab rejection reason, read-back value, the three password fields); the lab-approval study fields were false positives (their star is in the translation, which the rule now reads in FR, EN and ES). | 26 → 8 pins, each a field required only in part by design (one of several parts, one action, a secondary code step) |
| `scripts/lib/required-form-submit.test.mjs` rule `QA D1272` (listed, shrunk) | D1280 / D1281: a field a guard refuses blank that declares nothing. | 25 → 9 pins (one of several, conditional, secondary code steps); `patients/patient-form.html:otpCode` is in fix/r27-portal-search-forms' file |
| `scripts/check-required-refusal.mjs` (widened, shrunk) | D1279 / D1281 (D1256 / D1272 class): a handler refusal that names one field per press and marks nothing ("Sélectionnez d'abord une voie.", "Le nom d'utilisateur et le lien de parenté sont requis" with the name typed, the portal question refused as too short). The rule now reads `…TOO_SHORT…` keys too; fluid balance, family grant, document upload, portal question, break-glass reason, impersonation reason and the password length now mark the field. | 128 → 124 pins; 1 new pin for `patients/patient-form.ts` `PHONE_TOO_SHORT` (fix/r27-portal-search-forms owns the file, D1289) |

## Guards added or widened by round 27 (fix/r27-portal-search-forms)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/patient-term-search.test.mjs` (new) + `shared/patient-query.ts` (choke point) | D1274 (D1253 NOT_FIXED: birth date and e-mail): a patient search box that sends what was typed without reading what it is. Every search goes through `PatientService.list(hospitalId, term)` / `searchPatientsByTerm()`, which add the birth-date (`dob=yyyy-mm-dd`, from ISO, dd/mm/yyyy, dd-mm-yyyy, dd.mm.yyyy, ddmmyyyy) or e-mail (whole or a piece) search the term calls for; a page filters what it holds through `patientMatchesTerm`. Rules: `raw-name-search` (`patientService.search({ name })` outside the service), `direct-patient-search` (an HTTP call to `/patients/search` or `/patients?search` outside the service), `hand-composed-filter` (a page building its own filter from `personNameMatches` + `phoneMatches`). | every `.ts`; the shared picker and the patient list fixed; 11 `list(hid, term)` typeaheads served through the service; 0 allowed |
| `scripts/lib/one-at-a-time-refusal.test.mjs` (new) + `joinReasons` (shared/required-fields.ts) + the submit guard naming the blank required fields WITH a string reason | D1256 class closure (D1277, D1278, D1279; D1089 / D1124 / D1245 NOT_FIXED again): a form that names its missing fields one per press. Rule `one-at-a-time`: an unguarded submit handler showing two or more different `…REQUIRED…` / `…MISSING…` keys without aggregating them (`missingRequiredMessage` / `joinReasons`). Rule `reason-one-at-a-time`: a guard reason method returning two reasons in a row, or `true` before a reason. | 15 found by the first run: 9 fixed (billing adjustment, triage, appointment, microbiology susceptibility, eligibility, order set, dispensing reason and witnessed cancel, check-in, eMAR); 8 allowed with why (a context, not a field; states read for the chosen patient/scope; fluid balance → fix/r27-portal-markers-focus, D1279); stale entries fail |
| `scripts/lib/escape-discard.test.mjs` (new) + `escapeDiscardConfirmed()` (shared/a11y/escape-keeps-typed-text.ts) | D1285: a dialog whose own Escape handler vetoes on entries swallowed the Escape the reader confirmed with "Discard" (triage, nursing intake). Every handler bound to a dialog's Escape output that reads `hasEntries` / `isDirty` / … also reads `escapeDiscardConfirmed()`. | every template; 2 fixed; 0 allowed |
| `scripts/lib/phone-submit.test.mjs` (new) + `refuseInvalidPhones` / `invalidPhoneInputs` (phone-field.directive.ts) + the submit guard refusing a refused number in its scope | D1289 (D1211 class, submit side): a form that sends a phone number the server refuses ("70a2b" went to `POST /users/admin-register`). Every `appPhoneField` (or a field group holding one) is read by its scope's submit: a guarded button that is not a secondary action of a form, or a handler calling `refuseInvalidPhones` / `invalidPhoneInputs` / `isFacilityPhone`. | 6 found by the first run (registration, demographics edit, role-welcome, pre-check-in × 2, guarantor); all read now (the guarantor through the guard, file untouched); 0 allowed |
| `scripts/lib/cfa-amount.test.mjs` (widened) + `cfaAmountMessageKey(problem, requiredKey)` (no default) | D1294: a non-payment amount refused with the payment's words (a blank unit price said "Enter a payment amount greater than zero"). Every call names its wording; a `price` problem never takes the payment key. | 3 calls fixed; 0 allowed |
| `:where(.patient-picker-wrap) :where(.picker-input)` (shared/styles/_page-common.scss) | D1290: a picker placed outside a styled field rendered as a 207×21 px browser-default box. The base look lives on the picker, at zero specificity, so a page's own field rules still win. | every hand-written picker |

## Guards added or widened by round 27 (fix/r27-backend)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `architecture/PatientMatchKeysGuardTest` (widened) + `PatientServiceImpl#searchPatients` (router) | D1274 (D1253 NOT_FIXED: birth date and e-mail): an endpoint that routes a typed term itself. Every name search goes through the router, which sends a birth date (ISO, dd/mm/yyyy, dd-mm-yyyy, dd.mm.yyyy) to the date of birth, an address with `@` to the e-mail and a number to the phone. `PatientSearchPostgresIT` runs every combination on real PostgreSQL. | every patient search endpoint |
| `architecture/RequiredRequestIdGuardTest` (widened: 2 rules, fully qualified exception names) | D1282 (D1258 NOT_FIXED): a missing required id refused without a field, through an id-or-alternative chain or a local copy (chat recipient, invoice patient, handoff recipient, dose and stock witness, routing pharmacy, staff patient/user hospital). | `src/main/java`; the StaffRequestDTO allowlist from round 26 stays |
| `architecture/PatientQueueDisplayNameGuardTest` (new) | D1276: a queue DTO that names the patient only by id. | 29 id-only DTOs allowed with reasons; `PharmacySaleResponseDTO` is D1276 debt |
| `architecture/NotificationReaderLanguageGuardTest` (new) | D1286 (D1269 sibling): an inbox item stored as prose in the writer's language. Every producer stores a key and arguments rendered in the reader's language. | human-typed messages (admin message, custom prenatal reminder) allowed |
| `architecture/ActiveAllergyDuplicateGuardTest` (new) + `ActiveAllergyDuplicates` | D1287: the same allergen active twice (same code, or same name ignoring accents, case and spacing). | API create/update, nursing intake, legacy importer |
| `architecture/PaymentMethodRequiredGuardTest` (new) | D1293: a payment or copay with no method recorded as OTHER. | every payment entry path |

## Guards added or widened by round 28 (fix/r28-portal-focus)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `shared/a11y/focus-rescue.ts` `standInFrom` (choke point) + `scripts/lib/focus-after-action.test.mjs` rule `QA D1297 / D1298` (new) + `shared/a11y/focus-after-success.spec.ts` "QA D1297 / D1298" (widened) | D1297 / D1298 (D1283 / D1004 NOT_FIXED in round 28): focus after a successful action rested on a place that announces nothing (`div.portal-page`, a bare `tbody`, the flow board's `col-body`, `div.chart-container`) or on the page H1 behind a detail dialog that was still open. The round-27 rule saw only "a component host"; the class is any unnamed place or any place behind an open dialog. The stand-in is now, nearest first: the surviving row's first action, the nearest named place (named dialog/landmark/region/table, `main`) or heading that titles an ancestor (a heading inside a list item is skipped), never outside the open dialog on top (its heading, else the dialog), last the page heading or `main`. The scan holds `standInFrom` to those return values only and `restoreFocus` to noticing a `focus()` that did not take (a disabled opener re-enabled later in the same pass is awaited). The Karma spec drives a removed row, an open parent dialog with a disabled opener, a page wrapper, a board column and a heading inside a list item. | the rescue (every screen of the portal); 0 allowed |
| `scripts/lib/inline-editor-focus.test.mjs` rule `QA D1307` (new) | D1307 (the D1175 sibling the `editingX() === row.id` pattern cannot see): a form opened by a ROW action and rendered elsewhere (above the table) that leaves the keyboard on the row. Every `@if (x())` / `@if (x)` block with a field, outside a dialog or inline form, whose condition a row `(click)` sets (in the binding or through the component method) must be an `appInlineSubForm` (or carry `appFocusOnRender`, or be a dialog). | every template; 1 pin with its reason (eMAR master-detail queue) |

## Round 28 (fix/r28-portal-required)

| Guard | Class it catches | Scope / allowlist |
|---|---|---|
| `guard:required-refusal` (widened: rule `toast-only-refusal`) | D1306 / D1302 / D1305 (D1272 / D1256 NOT_FIXED): a refusing block (`if (…) { …; return }`) that shows a toast or a translated `.set(…)` and marks no field. The method-level rule missed an early return in a method that calls `refuseFields` later (patient payment dialog) and any refusal whose key is not REQUIRED-shaped (`cfaAmountMessageKey`). Page/session/device messages (`NOT_A_FIELD` key vocabulary) are not fields; a builder returning `FieldRefusal[]` is exempt. | Every component; pins 118 → 90 (≈60 sites fixed this round; 11 new pins: 6 non-field sites with reasons, 5 in other branches' files: `billing.ts`, `nurse-station.ts`) |
| `refuseFields(root, FieldRefusal[], all)` (shared/forms/invalid-fields) | D1256 class: one press names every refused field, each with its own reason (amount AND reference). A refused id missing from `all` is now marked too. | Choke point |
| `required-form-submit.test.mjs` / `required-field-star.test.mjs` (widened) | D1304: an `sr-only` label or an `aria-label`-only required control shows no visible star (both were exempt); D1305: a control whose id is built (`'rx-clarification-text-' + id`) and its guard's listed expression now match, and `[for]` bindings are read. | Every template; 0 pins (lock screen, addendum, mortality/transfusion pickers, MAR reason, MFA codes fixed) |
| `scripts/lib/round28-required.test.mjs` (new) | D1296/D1302/D1303: a field refused blank (REQUIRED/MISSING key or `COMMON.FIELD_REQUIRED`) that does not declare `required`; D1296: `<option [value]="null">` (posted as the string "null"); D1309: an encounter `<option>` not labelled through `encounterOptionLabel` (day, time, department); D1311: a "born" string that does not agree with the sex its key names, or a template naming `PATIENTS.BORN*` directly instead of `bornLabelKey(sex)`. | Every component/template/locale; 2 pins (`billing.html` line edit, other branch) |

## Guards added or widened by round 28 (fix/r28-backend)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `architecture/UnsortedPageGuardTest` (new) | D1295 (patient safety): a capped page cut from a query with no order, so the database picks which rows survive (the nurse inbox asked for 20 unsorted rows and dropped today's NEWS2 16). Every `PageRequest.of(page, size)` without a `Sort` that reaches a repository method - as an argument, through a local `Pageable`, or one hop through a service method that takes the page - must reach a method whose name has `OrderBy` or whose `@Query` has `ORDER BY`. The round-26 sweep (D1255) fixed worklists by name; this reads the behaviour. | every main file; 7 harmless sites (counts read from `getTotalElements()`, `Integer.MAX_VALUE` pages summed whole, the dev seeder), each with why; stale entries fail. `repository/SortedPagePostgresIT` runs every query given an order on PostgreSQL |
| `architecture/RequiredRequestIdGuardTest#everyMissingRequestValueRefusalNamesItsField` (new) | D1299 (D1258 / D1282 NOT_FIXED: the rules read only `getXId() == null`): any `if` testing a request-body getter for absence (`== null`, `isBlank()`, `isEmpty()`, `!hasText(...)`, a presence flag computed from the body and tested negated) that throws something other than a `FieldBusinessException`; and a helper that refuses a blank PARAMETER with no field, called with a body getter (`requireStatusReason(status, request.getStatusReason())`). One choke point: `exception/RequiredFields` collects every check and throws ONE `FieldBusinessException` naming all of them; the handler answers every field under `fieldErrors`. | every service; 67 direct refusals and 34 helper calls fixed by the first run; 0 allowed |
| `architecture/RequiredRequestIdGuardTest#everyServiceRequiredFieldIsAlsoABeanConstraint` (new) | D1300: a field the service refuses absent on a body that also declares bean constraints, so `{}` names it one press later. Fix: `@NotNull` (with `groups = OnCreate.class` when create-only, the create handler validating `@Validated({Default.class, OnCreate.class})`) or a class-level `@OneOf`. | 19 conditionally required fields (each with when) and 15 KNOWN GAP D1300 fields (tasklist standing debt) |
| `architecture/RequiredRequestIdGuardTest#everyDuplicateValueRefusalNamesItsField` (new) | D1301: a value refused because another record holds it (a duplicate reference, a code or name taken), read by its message key - literal or a `static final String` constant (`PaymentReferenceRegistry.DUPLICATE_KEY`) - with no field. | every service; 10 keys allowed with why (a record as a whole, a state, four 409 conflicts and the auth controller's own MessageResponse as D1301 debt) |
| `i18n/FieldLabelCoverageTest` (widened) + `GlobalExceptionHandler#invalidFieldReason` | D1312 (D1268 standing debt): a field Jackson parses (enum, UUID, date, number) named by its raw property in "La valeur saisie pour « copayMethod »". Every parsed request field has `field.label.<name>` in EN, FR and ES (355 added); a field with no label gets the sentence without a name, never its property. Flags are not read: a checkbox sends true or false. | every request body; 0 |
| `service/impl/MarDoseScheduleTest#d1308_everyBareCountBeforeAPerDaySlashIsRead` (new) | D1308 (D1291 NOT_FIXED): the D1263 / D1291 templates all put "fois", "x" or "times" between the count and the slash, so "2/j" was never tried. Every count 1-12 bare before every per-day slash in FR, ES and EN (15 forms); a bare count that is not a day's doses stays unread. | `MarDoseSchedule` (the one reader) |
| `architecture/PatientFreeTextSearchGuardTest` (new) | D1310 (D1274 class on another screen): a search box over other records that finds the patient by name only. Every repository query (with the text constants it concatenates) and every specification that compares a term with a patient's first name also matches e-mail, date of birth and phone. `repository/InvoiceSearchPostgresIT` runs the invoice search for every identifier under every combination of its other filters. | repositories and specifications; the invoice list and the appointment list fixed, the unused `findByPatientName` removed; 0 allowed |

## Guards added or widened by round 29 (fix/r29-portal-clinical)

Rounds 27, 28 and 29 each found 20–30 new siblings of the required-field classes on screens the template scans read as fine. These guards drive the screen instead of reading it.

| Guard | Class it catches | Scope / allowlist |
|---|---|---|
| `src/app/testing/form-refusal-behaviour.ts` (new, Karma) | D1256 / D1272 / D1245 class, behaviourally: `expectEmptySubmitRefusal(fixture, { button, alsoMarked?, notMarked?, sent? })` presses the real submit and asserts every blank required control of its form is starred, `aria-invalid` and described by a visible message, a filled control is never marked, at least one field is marked (never a toast alone), and nothing is sent; `expectFieldsMarked` + `fieldErrorsResponse` assert a server 400/409 `fieldErrors` marks each named control with the server's reason. | Used by the eMAR verify (D1329), vitals capture (D1330), treatment plan (D1332), lab test definition (D1338), labour start and delivery (D1339) specs |
| `npm run guard:form-behaviour` (new, part of `npm run guards`; `lib/form-behaviour-scan.mjs`) | Enumerates every submit from the code: rule `empty-submit-untested` — a `<button>`/`<input>` with `appSubmitGuard` or `type="submit"` whose static `data-testid`/`id` no spec in its directory passes to `expectEmptySubmitRefusal(` (a selector used elsewhere in the spec does not count; a submit with no static anchor must be given one); rule `server-field-errors-unmapped` — a guarded form whose component never calls `refusedFieldReasons(` / `fieldErrorsOf(`. A NEW form fails CI without anyone listing it. | Every template; 303 pins / 314 sites (83 front-office pins owned by fix/r29-portal-front-office; the rest D1256/D1341 standing debt); pins only shrink |
| `scripts/lib/date-time-seconds.test.mjs` (new) + `shared/pipes/date-time.pipe.ts` (`dateTime`) | D1340: a date-time with seconds — `date: 'medium'/'long'/'full'/'*Time'` or a pattern with `ss`, `formatDate(…, 'medium')`, `toLocaleString()`/`toLocaleTimeString()` without options, `second:` or a `timeStyle` other than short. `dateTime` is the locale's medium date + short time ("2 nov. 2026, 15:00"); a fixed pattern would break D1140. `server-time.mjs`, `iso-date-display` (D1049) and `lab-locale-format` (D1002) read `| dateTime` as a date pipe (serverTime still required in front of it). | Every template and `.ts`; 12 front-office files pinned for fix/r29-portal-front-office, 1 number-format probe |

## Guards added or widened by round 29 (fix/r29-portal-front-office)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `shared/forms/server-field-errors.ts` `serverFieldErrorsInterceptor` (choke point, app.config.ts) + `server-field-errors.spec.ts` | D1341 / D1320 / D1321 (the D1245 / D1258 / D1301 class, NOT_FIXED r27–r29): a write refused with `fieldErrors` showed only a toast because each form had to wire its own map. The interceptor remembers the form, dialog, `[data-submit-scope]` (else the page section) the reader acted in and marks each named field there (`data-server-field` tokens, then id / `name` / `formcontrolname`, then the one id ending with the key), with the server's reason; the mark leaves on edit or on the next write from that place. `markServerFieldErrors(scope, err, idByField)` for a page that knows better. | every write of the portal; 0 allowed |
| `src/app/testing/form-behaviour.spec.ts` + `form-behaviour.registry.ts` (generated) + `scripts/lib/form-behaviour-registry.{mjs,test.mjs}` | The behavioural form guard asked by round 29: every component of the covered directories whose template holds a submit (or a field and a button) is mounted, its reads answered (a synthetic row, else empty lists), its buttons pressed one level deep; every submit whose place holds a blank required control is pressed: no write may go out, every blank required control is marked (`aria-invalid` + message), every refused control shows a star; every control of a submitting place must be reachable by its server key through the shared mapper. The registry test fails when a new form is not in the generated list. Floor: at least 15 places reached. | `COVERED_DIRS`: appointments, billing, checkout, patient-portal, patients, pharmacy, reception. 2 components NOT_MOUNTED with why (appointment-form, patient-demographics-edit: driven by their own specs) |
| `scripts/lib/round29-front-office.test.mjs` (new) | D1328 `?? '—'` (blank string shown blank → `\| dash`); D1323 `'PREFIX.' + row.field \| translate` (raw key → `\| keyLabel: 'PREFIX.'`); D1326 a label saying "(obligatoire)" in words; D1327 a patient key family (BASE / _FEMALE / _MALE) incomplete or not agreeing in FR/ES, or named directly in a template (→ `genderedKey`); D1342 / D1320 a submit guard on a button whose handler refuses each field with its own reason (the guard answered first with the generic text). | covered directories (the first two), every locale and template (the others); 0 allowed. D1340 is held by fix/r29-portal-clinical's `date-time-seconds.test.mjs` (its shared `dateTime` pipe is used here) |
| `scripts/lib/inline-editor-focus.test.mjs` rule `QA D1307` (widened) | D1335 (the D1307 sibling): a row action that opens a form several calls away (claim answer → openFormFor → openForm). Methods reached through `this.fn(` are followed to depth 5. | 1 new pin: bed-board `board` (a data reload, not a panel) |
| `shared/forms/submit-guard.directive.ts` `isBlank` (widened) | D1325: a listed group of choices (radiogroup / fieldset) with nothing ticked is blank and marked. | the directive |
| `scripts/lib/pipe-scope.mjs` `RESOLVING_PIPES` (+ `keyLabel`) | so `i18n:raw-enums` reads `\| keyLabel` as a label | — |

## Guards added or widened by round 29 (fix/r29-backend)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `controller/WriteEndpointRequiredFieldSweepIT` (new, behavioural) | D1299 / D1258 / D1282 / D1245 / D1301 / D1312 / D1318 / D1316 (NOT_FIXED three rounds: every wave fixed what a source pattern could see, QA found the rest by calling the endpoints). Every POST / PUT / PATCH handler with a `@RequestBody`, read from Spring's request-mapping registry (a new endpoint is swept the day it exists), called through MockMvc on a real persistence context as an authorised caller: (1) `{}` is never 5xx, never 2xx unless listed, else a 400 whose `fieldErrors` keys are request properties; (2) every named field filled with a value of its type and re-sent: no field the body still omits may be named now (one at a time), no 400 without `fieldErrors`, no 5xx; (3) each named field left out alone: never 2xx, never 5xx; (4) every UUID / enum / date / time / number sent `""` at once: every required one named in ONE answer. Its first run found 84 endpoints not refused by name, 11 naming fields one at a time, 92 stopping at the first blank typed field, 10 with keys the form cannot map, 4 answering 500. | 424 endpoints. `NO_REQUIRED_FIELD` (58: filters, partial updates, protocol endpoints, optional notes, each with why), `NOT_A_FIELD_ONCE_FILLED` (11: a state or role the sample cannot satisfy), `CONDITIONAL` (2). Stale entries fail. |
| `architecture/R29FieldNamedRefusalGuardTest` (new) | Where a call cannot reach: a 400 with an empty body (`badRequest().build()`, 10 found: calendar, encounter list, KPI and morbidity dashboards, reception encounter status); an `@AssertTrue` cross-field rule without `@ReportedOn` naming a request field (16 found, D1317); a refusal keyed "required" thrown without a field (50 converted to `FieldBusinessException`); a message showing a raw camelCase property (32 keys in 4 locales rewritten, D1319). | `EMPTY_400_ALLOWED` 1 (CDS Hooks protocol); `NOT_A_FIELD` 13 states with why, plus categories (hospital context, whole body, path id, caller role); `CAMEL_CASE_WORDS` 8 units / scores / DHIS2 terms |
| `i18n/FieldLabelCoverageTest#everyNameableFieldIsNamedInTheRefusal` (new) | D1313 (regression of D1312): the "no label" test was `label.equals(key)`, so every real label that spells its key (EN relationship, date, amount) lost its name. Every nameable request field, in EN, FR and ES, through the real handler and bundles. | none |
| `GlobalExceptionHandler` choke points | D1338 / D1317: `fieldErrors` keys are the request's JSON path (`RequestFieldPaths`: `@JsonProperty` names, `@ReportedOn` for rules) on body, list-body and entity-constraint refusals; a 409 `field:` conflict carries `fieldErrors`; an entity constraint failing at commit is the same 400, not a 500. | — |
| `config/RequestBodyDefaultsModule` + `JacksonConfig` | D1316: a blank enum / UUID / date / time / number is read as absent, so bean validation names every required one at once. | — |
| `repository/WaitlistFilterPostgresIT` (new), `repository/InvoiceSearchPostgresIT#theAppointmentSearchFindsThePatientByMrn` (new) | D1324 / D1322: the waitlist's null-status branch and the appointment MRN subquery on real PostgreSQL, every parameter combination the callers pass. | — |

## Guards added or widened by round 30 (fix/r30-portal-forms)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `src/app/shared/all-pages.ts` (`allPages` / `allRows` / `allRowsOfArrays`, choke point) + `all-pages.spec.ts` | D1347 (and the D1213 / D1295 / D1346 family): a list read from ONE page of a fixed size and treated as the whole list (the lab orders page read the 200 newest of 216 and counted them). Every page is read on the server's own paging; a repeated row is kept once; past 200 pages the read FAILS instead of returning a partial list. Service list methods without a `page` (`LabService.listOrders`, `EncounterService.list` / `listForPatient`, `TreatmentPlanService`, `StaffService.list`, the portal's invoices / refills / disclosures) read every page. | — |
| `npm run guards` → `check-list-caps.mjs` (new; `lib/list-cap-scan.mjs` + `.test.mjs`) | D1347: rule `fixed-size` (a literal `size:` / `limit:` / `.set('size', …)` / positional `(…, 0, N)` on a read), `page-flattened` (a page reduced to its rows in a `map`), `page-content` (a page's rows read on a screen that shows no total). | every `.ts` of src/app; `scripts/list-caps-baseline.json`: 25 pins, each a count probe, a preview titled as such, a typeahead, a pager with the server total, or an open D1347 sibling in another branch's file (prescriptions.*, the server-capped `/slots/search`) |
| `repository/SortedPagePostgresIT#worklistsPageOnATotalOrder` (new) | D1347 server half: the lab-order worklist orders on (`orderDatetime`, `id`) so a page-by-page read never repeats or skips an order; the encounter, staff and invoice sorts the portal now sends run on PostgreSQL for every filter combination. | — |
| `scripts/lib/required-fields-named.test.mjs` (new) | D1357 / D1358: a required-fields refusal worded by a FIXED sentence (`…REQUIRED_FIELDS`, `…REQUIRED_MISSING`, …) instead of the blank fields (`missingRequiredMessage`). 27 sites moved. | 8 neutral sentences pinned in the test with why |
| `scripts/lib/round30-server-keys.test.mjs` (new) | D1352 / D1354 portal half: the precaution type, transfer destination, dispensed quantity, lot quantities and witness pickers answer to the `fieldErrors` key the server sends (the shared mapper marks them). | the handoff recipient (nurse-station.html, MAR branch this round) is flagged, not covered |
| `medical-certificate-dialog.component.spec.ts` (D1356 tests) | D1356: a signing dialog whose attested content opens pre-filled, so an untouched form signs. The type and the days start empty and are required. | — |

## Guards added or widened by round 30 (fix/r30-prn-interval)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/form-behaviour-scan.mjs` rule `create-stays-armed` (widened, part of `npm run guard:form-behaviour`) | D1345: a write's success handler that closes its dialog only on a condition (`if (!advisories.length) closeModal()`) leaves it in create mode on the other branch, so a second press posted the same prescription again. Such a handler must also set a `created` / `applied` / `saved` / `locked` signal to true (the dialog hides its submit on it). | every component source; 0 pinned |
| `admissions/order-set-picker` spec (D1345 sibling) | The applied order set offered Apply again from the same preview (every order created twice). | the picker |
| `architecture/MedicationRouteFieldGuardTest` (new) | D1359: a request field naming an administration route accepted any text (`BOGUS`, `par la bouche`), while the MAR five-rights route check reads it. Every `@RequestBody` field named `route` (nested types too) carries `@MedicationRoute` (the `MedicationRoutes.canonical` vocabulary) or is listed with why it is another vocabulary. | 4 listed (patient-reported home medications at intake and discharge, the vaccine route — branch 1's file —, the catalogue default route); stale entries fail |
| `scripts/lib/as-needed-frequency.test.mjs` (new) | Round 30 PRN interval (user decision 2026-11-02): the prescribing form's copy of the as-needed words must equal `MarDoseSchedule.isAsNeeded` (the server reading), or the form hides the field the server refuses the order for. | the two files |
| `MarNextDoseTest` / `MarScreenServerAgreementTest` (widened) | A PRN order ended after one dose; now it stays offered, never OVERDUE, early (with a reason) inside the prescriber's interval; an older order without an interval stays open and flagged. The agreement test runs the PRN kinds (with and without an interval) through every outcome and moment. | — |

## Guards added or widened by round 30 (fix/r30-service-field-errors)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `architecture/RequiredRequestIdGuardTest#everyRefusalOfAGivenRequestValueNamesItsField` (new) | D1352 / D1354 (the round-29 standing debt "about 30 value refusals are still plain BusinessException"; there were ~250): a plain `BusinessException` whose guard reads a request-body value - an `if` condition or the statement an `.ifPresent(x -> throw)` hangs on, directly or through a local initialised from one - refuses a value the user GAVE with no `fieldErrors` (duplicate precaution, same-bed transfer, handoff to oneself, dispense above the prescription, adjustment above the lot, witness refusals). Also one call away: a method whose throw is guarded by a parameter, called with a body getter in that position, from its own class or (public) through a field of its type. Each throws `FieldBusinessException` naming the request property. | every service; `REFUSAL_OF_THE_RECORD`: 2 record-state keys the D1301 rule excuses too, 3 controlled-substance safeguards (a state of the order, as round 29 reads them), 8 `PrescriptionServiceImpl` sites (KNOWN GAP D1354: file owned by fix/r30-prn-interval this round) |
| `architecture/RequiredRequestIdGuardTest#everySharedHelperRefusalOfAMissingParameterNamesAField` (new) | D1353: `CancelledOrders.requireCancelReason(reason)` refused a blank reason with no field from eight order services; the D1299 helper rule read only helpers called from their own class. A `public static` service helper that refuses a missing parameter names a field (taken from the caller). | every service; `PeriodResolver#resolve` (a DHIS2 job parameter) |
| `architecture/RequiredRequestIdGuardTest#everyMethodNamesAllItsMissingFieldsInOneRefusal` (new) | D1355: a method that refuses two or more missing values each on its own (`requirePresent` twice, consecutive `if (x == null) throw new FieldBusinessException`) names one field per press. Collect them in `RequiredFields.check()...orThrow()`. | every service; 3 entries with why (exclusive switch arms, a server-context refusal, and `TreatmentConsentServiceImpl#record` as KNOWN GAP D1355) |
| `repository/PatientMedicationActiveFirstPostgresIT` (new) + `PatientMedicationServiceImplTest#d1346_*` | D1346 (patient safety): a list cut at a fixed limit before the status it is grouped by was known, so 20 cancelled orders pushed every running medicine off "My medications". Both reads (own, staff) on PostgreSQL; every caller's limit keeps every active order first. | `PatientMedicationServiceImpl.activeFirst` (the one choke point of the staff list, the portal list, the proxy view and the health summary) |

## Guards added or widened by round 30 (fix/r30-request-validation)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `controller/WriteEndpointRequiredFieldSweepIT` phase 5 **Malformed typed** (widened) | D1349 (D1313 / D1316 NOT_FIXED): a body with two malformed values, or a malformed value beside blank required fields, named only the first (Jackson stopped there, bean validation never ran). Every UUID, enum, date, time, number and flag field of every write body is sent a value it cannot read, all at once: one 400 must name each of them AND every field the empty body named. A required value checked only in a service cannot be named with a malformed value, so the 14 endpoints the first run found declare theirs at the edge (bean validation, `OnCreate` / `OnRefusal` groups, `@AssertTrue` + `@ReportedOn`). | every POST/PUT/PATCH with a JSON body (424); 0 allowed |
| `controller/WriteEndpointRequiredFieldSweepIT` phase 6 **Blank text** (widened) | D1351: every named text field of the filled body sent `"   "` at once; each must be named in that answer (a `@NotNull String` let a blank through to a service check that named it one request later). | 424 endpoints; `BLANK_TEXT_ACCEPTED` empty |
| `architecture/NotNullTextRequestFieldGuardTest` (new) | D1351 statically: a request-body text field (every `@RequestBody` class, nested classes, list elements) that is `@NotNull` without `@NotBlank`. | 0 (6 converted: precaution, discontinue, transfer, transfer cancel, questionnaire responses, education topic) |
| `architecture/FreeTextStatusRequestFieldGuardTest` (new) | D1348: a request field named `status` that is a free `String` stored as sent, outside the vocabulary its readers key on (immunization "FAIT", "Donné"). It is an enum, or listed with the code that checks it (or why no rule reads it). | 6 listed with where each is checked (lab instrument, lab order ×2, MAR administration, super-admin lab order, chart-section disposition) |
| `i18n/FieldLabelCoverageTest` (widened) | D1350: flags (`highRisk`, `flexibilityAcknowledgment`, `accepted`) and the elements of list bodies (`notificationType`, `channel`) are nameable fields; new test `everyNameableParameterHasALabel`: every `@RequestParam` a refusal can name (required, or parsed) has `field.label.<name>` in EN/FR/ES (`slotId`, `from`, `to`). 305 labels added. | 0 |
| `GlobalExceptionHandler` + `RequestBodyProblems` + `MalformedRequestBodyAdvice` + `RequestBodyDefaultsModule.CollectMalformedRequestValues` (choke point) | D1349: while a `@RequestBody` is read, each malformed value is recorded by its JSON path and read as absent; the advice then validates the body (same `@Valid`/`@Validated` groups, each list element) and refuses it with every malformed field and every validation refusal. A recorded body never reaches a controller. Outside a body read Jackson stays strict. D1350: a list element's default-template refusal is named (`[0].notificationType`); a missing or unparsable parameter with no label names nothing instead of its raw name. | `R30RequestBodyRefusalsTest` |
| `architecture/RequiredRequestIdGuardTest` (widened) | `UNVALIDATED_ALLOWED` now fails on a stale entry; `StaffController#create` removed (it validates its three required names, group `OnCreate`). | 1 allowed (`StaffController#update`) |

## Guards added or widened by round 31 (fix/r31-lists-portal)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `config/StablePageOrderConfig` + `utility/StablePageOrder` (choke point) | D1361 (D1347 NOT_FIXED): a page cut from an order that is not total. `GET /lab-results` paged a query with no ORDER BY (the controller has no default sort and the portal sends none since #1033), so PostgreSQL broke the order differently for each OFFSET: 16 of 202 results came back twice and 16 never. Every Spring Data repository proxy now closes the `Pageable` of every call with the entity's unique key: a caller's sort gets `id` appended, an own ORDER BY gets `id` as its tiebreak, a query with no order at all gets newest first (`createdAt DESC, id DESC`, else `id`). Native SQL and unpaged reads are left as written. | every repository (no opt-out) |
| `repository/SortedPagePostgresIT#everyPagedRepositoryReadEndsOnId` (new) | D1361 behaviourally: EVERY repository method that takes a `Pageable` (derived, JPQL, Specification, inherited, custom; >200) is called on PostgreSQL with a second page, unsorted and (for root-entity methods) sorted on `createdAt`; each paged SQL statement must end its ORDER BY on an `id`. A new query that cannot take the id, or a repository outside the choke point, fails. | 3 entities whose key is not `id`, each pinned to the exact ORDER BY of its whole key (`ImagingReportAttachment`, `RegionPolicy`, `UserRole`) |
| `architecture/UnsortedPageGuardTest#everyHandPagedQueryEndsOnId` (new) | D1361 sibling: a native query that pages by hand (`LIMIT :limit OFFSET :offset`) takes no `Pageable`, so its own ORDER BY must end on an id (the two chat conversation lists ordered on `sent_at` alone). | 0 |
| `scripts/lib/list-cap-scan.mjs` rules `fixed-limit` + `slice-count` (widened, part of `npm run guards` → list caps) | D1362 (D1347 NOT_FIXED): the "Mon dossier médical" lab card read `getMyLabResults(50)` and said "+ 46 plus" from those 50 (121 more existed); the medications card counted 44 of 444. `fixed-limit`: a read called with one literal number. `slice-count`: a template "+ N more" computed from the rows on hand (`labs().length - 4`); the count is `moreCount(serverTotal, shown)` (`shared/more-count.ts`), with the server's totals on the health summary (`labResultTotal`, `medicationTotal`). | 4 new pins with reasons (analytics trend days, patient dashboard preview, two super-admin previews) |
| `scripts/lib/round30-server-keys.test.mjs` test `D1376` (widened) | D1376: the profile e-mail change refused with `fieldErrors.newEmail` and only a toast showed (`#editEmail` / `name=email`). Every own-account control of the profile answers to the key its endpoint names (`newEmail`, `currentPassword` ×2, `code`, `newPassword`, `phoneNumber`). | 0 |

## Guards added or widened by round 31 (fix/r31-prn-mar)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `hospital-portal/src/app/shared/as-needed-frequency.cases.json` (new shared fixture) run by `MarDoseScheduleTest#d1360_*`, `PrnMinIntervalTest#d1360_*` and `as-needed-frequency.spec.ts` | D1360 (safety): an as-needed wording ("au besoin", "à la demande", "si nécessaire", "si douleur", "según necesidad", "when required" ...) not read as PRN, so a PRN order (controlled morphine included) needed no minimum interval. One fixture, both sides: every wording is AS_NEEDED on the server and in the form, and a morphine order in each is refused without its interval (edge and service). | 55 as-needed and 18 non-as-needed cases |
| `scripts/lib/as-needed-frequency.test.mjs` (widened) | D1360: the two phrase lists (`MarDoseSchedule.AS_NEEDED_PHRASES`, portal `AS_NEEDED_PHRASES`) stay equal, hold the minimum FR/ES/EN wordings, and every phrase has a case in the shared fixture. | 31 phrases |
| `PrescriptionEditAndWithdrawalIT#d1360_*` (new) | D1360 over HTTP: morphine "au besoin" / "à la demande" / "si nécessaire" / "si douleur" without an interval is a 400 on `prnMinIntervalHours`, nothing stored. | the create endpoint |
| `NurseTaskServiceImplTest#d1375_*` (new: reflective answer-vs-list comparison + source scan) | D1375: an administer answer rebuilt a subset of the MAR row by hand (asNeeded=false, no interval, no label). Every DTO field of the answer equals the list row read after the dose, except the dose's own (id, status, dueTime, witnessName); every `NurseMedicationTaskResponseDTO.builder()` goes through `withOrderReading` / `answerAfterDose`, and only `NurseTaskServiceImpl` builds one. | 4 builders |
| `controller/WriteEndpointRequiredFieldSweepIT` phase 7 **value refused after the edge** (widened) | D1371 / D1372: a value the server refuses once the named fields are filled must be named with the edge's refusals; from the filled body each edge-named field is left out alone and the answer must still name the refused value. | `VALUE_REFUSED_AFTER_THE_EDGE`: 15 KNOWN GAP endpoints (cross-field and value rules in the service, tasklist round-31 standing debt) |
| `architecture/StatusRefusalLabelGuardTest#noMessageListsRawCodes` (widened) | D1371: a message listing its allowed values as raw codes ("Utilisez SHIFT_CHANGE, WARD_TRANSFER ..."). Every bundle, every locale: no message lists two or more UPPER_SNAKE codes. | 0 allowed (5 messages reworded) |
| `architecture/RequiredRequestIdGuardTest#theBedChokePointsRefuseAPickedBedOnItsField` (new, partial) | D1366: an occupied / unfit / held bed refused with no field. In `BedAssignmentService` and `TransferServiceImpl` every plain `BusinessException` is a listed state of the stay or order. (A full guard is not possible: the bed id reaches the choke point as a plain parameter through the controller and the admission service, beyond the D1352 rule's one-call reach.) | 3 state keys; `transfer.r7.destinationTaken` removed from 2 allowlists (now named on `toBedId`) |
| `NurseHandoffCreateRequestDTOValidationTest`, `DischargeApprovalDecisionDTOValidationTest` (new) | D1371 / D1372: the handoff type is validated at the edge over the service's one list; reject validates `OnRefusal` (rejectionReason) and approve `OnApproval` (dischargeDiagnosis), read from the controller's own `@Validated` groups. | the two endpoints |
| `scripts/lib/round30-server-keys.test.mjs` (widened) | D1366 / D1371 / D1372 portal half: `#adm-bed-select` (bedId), `#ho-direction`, `#ho-recipient`, `#decision-diagnosis`, `#decision-note` answer to the keys the server names. | 5 controls added |

## Guards added or widened by round 31 (fix/r31-refusals)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `architecture/FreeTextStatusRequestFieldGuardTest` (widened; D1348 NOT_FIXED) | D1373: a closed-vocabulary request field stored as free text. Round 30 read only fields named `status`; it now reads every field named status, gender, sex, priority, paymentMethod, method, channel, orderChannel or direction (and names ending Gender / Sex). A String one passes with an edge `@Pattern(regexp = TheEnum.CODES)` (patient sex `PatientGender`, payment method `PaymentMethod`, lab-order status / channel, `ClinicalPriority` for lab, ultrasound and nurse-task priority). New rule `everyEdgeCheckListsTheEnumsCodes`: each `CODES` lists exactly its enum's values, any case, blank left to the required rule. | every `@RequestBody` class; `CHECKED_IN_CODE` 9 (3 statuses checked in code, a chart-section disposition, the handoff direction until D1371 lands, crossmatch method, EMPI search sex, family-history relative sex, MFA destination - each with why); 2 round-30 entries removed |
| `architecture/RequiredRequestIdGuardTest#everyConstrainedNestedRequestObjectIsCascaded` (new) | D1369: a nested request object (or list of them) whose class declares constraints, held without `@Valid`, so its refusals came a request late or at flush in English (birth-plan introduction). | every validated body; `UNCASCADED_ALLOWED` 6 with why (sign-up patient, registration insurance, encounter note template default, 3 discharge row lists as KNOWN GAP D1369) |
| `architecture/RequiredRequestIdGuardTest#everyUnrecognisedSwitchValueRefusalNamesItsField` (new) | D1374: a value no case of a switch recognises (`?action=PARTIR`) refused with no field. | services and controllers; 1 record-state key |
| `architecture/RequiredRequestIdGuardTest#everyFieldNamedRefusalNamesAPropertyOfTheRequest` (new) | D1368: a field-named refusal whose key the request does not have (`fieldErrors.reason` for `withdrawalReason`). | every service; 0 |
| `architecture/RequiredRequestIdGuardTest#everyDuplicateValueRefusalNamesItsField` (widened) | D1365 / D1367: messages pre-resolved through `messageSource.getMessage("key", …)` are read; "staff.unavailable" (a held slot) and "otherPatient" keys are duplicate-shaped. Found and fixed: appointment conflict (create and edit), slot hold/book, recall link, invoice visit of another patient, department name/code (409 now `name:` / `code:`), duplicate encounter. | `immunization.r25.duplicateDose` removed from the allowlist (fixed) |
| `architecture/RequiredRequestIdGuardTest#everyRefusalOfAGivenRequestValueNamesItsField` (allowlist shrunk) | D1354 KNOWN GAP closed: the 8 `PrescriptionServiceImpl` sites now name status, controlledSubstance, requiresCosign, encounterId or medicationName. | `REFUSAL_OF_THE_RECORD` 5 |
| `i18n/FieldLabelCoverageTest#labelsNameAField` (new) | D1374: a field label that is a bare preposition or article (« du ») or a recipient word for the period end `to`. | every label, EN/FR/ES and the base bundle; 0 |
| `scripts/lib/round30-server-keys.test.mjs` (widened) | D1365 / D1367 / D1368 / D1373 portal half: the appointment form and reschedule dialog (startTime, appointmentDate, staffId), the vaccination date, the prescription withdrawal reason and both sex selects answer to the key the server names. | the handoff recipient is still flagged (MAR branch) |

## Guards added or widened by round 32 (fix/r32-list-caps)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `scripts/lib/list-cap-scan.mjs` rule `default-limit` (new, part of `npm run guards` → list caps) | D1379 (D1347 class): a read method whose row limit is a DEFAULTED parameter and that answers a bare array (`getMyVitals(limit = 10): Observable<VitalSignSummary[]>`); the call `getMyVitals()` passes no number, so `fixed-limit` never saw it, and `/my-vitals` showed 10 of 45 records with no way to the rest. A method that answers a page with its total is not reported. | 21 pre-existing sites pinned with reasons: 9 super-admin preview feeds, one typeahead, one unused default, 10 open siblings listed in tasklist.md round-32 standing debt |
| `scripts/lib/list-cap-scan.mjs` rule `payload-length-count` (new) | D1380: a count or total read from the length of a list in a server payload (`c?.medications?.length ?? 0` in a member named like a count): the chart review badges said MÉDICAMENTS 20 for 667 prescriptions. Counts are the server's totals (`ChartReviewDTO.totals`). | 0 |
| `src/app/shared/range-pager/` (choke point) + spec | D1379 / D1380 / D1390: a server-paged list shows "Showing 21–40 of 667" from the SERVER's total and moves page by page (`/my-vitals`, every chart review tab, the doctor timeline, the slot admin list). | — |
| `repository/SortedPagePostgresIT#wholeReadsBehindTheServerPagedScreensRun` (new) | D1380 / D1390 server half: the chart review, the doctor timeline's visit rows and the slot search read every row WHOLE (unpaged, same filters) and count it before cutting a page; each read runs on PostgreSQL for every parameter combination its callers use, and none sends OFFSET/LIMIT. `/my-vitals`' paged history ends its ORDER BY on `recorded_at desc, … id`. | — |
| `ChartReviewServiceImplTest#pagesEachSectionAndCountsEveryRow`, `#accountsOnlyTheForeignRowsOnThePageReturned`, `#theCompleteReviewForThePrintedRecordIsNotCut`; `PatientServiceImplTest#getDoctorTimelinePagesEveryEntryWithTheTotal_D1380`; `SlotInventoryServiceImplTest#everyOpenSlotOfTheWindowIsReachedPageByPage_D1390` | D1380 / D1390 behaviourally: every page read in turn returns each row exactly once and the total counts every row (the booked-over slot filtered BEFORE the cut). | — |

## Guards added or widened by round 32 (fix/r32-labels-validation)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `i18n/FieldLabelMeaningTest` (new) | D1377 (REGRESSION of D1374): one `field.label.<name>` key served two meanings - `?to=` (a period's end) and the invoice e-mail body's recipient `to` - so relabelling it for one renamed the other. Every request-body field (per declaring DTO) and every query parameter (per controller) is rendered in EN/FR/ES through the handler's resolution (a parameter reads `field.label.param.<name>` first); two contexts of different value natures (date, number, flag, text; a body field against a parameter or another body field) must not render the same label. Pins the recipient / period meanings and drives the e-mail refusal through the real handler. | every body field and parameter; `SAME_MEANING` 3 (alcoholUse, occupationalHazards, priority - each with why) |
| `i18n/FieldLabelCoverageTest#labelsNameAField` (assertion corrected) | D1377: the D1374 assertion "field.label.to never reads as a recipient" encoded the regression; it now holds `field.label.param.to` to the period and `field.label.to` to the recipient. | — |
| `architecture/FreeTextStatusRequestFieldGuardTest` (widened; D1348 sibling) | D1383: blood type stored as free text through PUT/POST while PATCH refused it - the name list had no blood type. `bloodType` / `bloodGroup` (and endings) are vocabularies; new `everyBloodTypeFieldUsesTheAboRhVocabulary`: every blood-type request field carries the ONE `AboGroup.BLOOD_TYPE_CODES` pattern, which accepts exactly ABO x Rh. | every `@RequestBody` class + `SignupRequest`; 0 |
| `architecture/QueryParameterVocabularyGuardTest` (new) | D1385 (D1350 class): a `String` query parameter picking from a closed list matched nothing for an unknown value (200 + empty list). Choke point `controller/support/QueryParameterValues.oneOf` (trimmed, any case, `InvalidQueryParameterException` → 400 `INVALID_PARAMETER` + `fieldErrors.<param>`, named by its label); every such parameter goes through it or is listed with where it is checked; every `oneOf` parameter has a label. | every controller; `CHECKED_ELSEWHERE` 21 (4 D1384 in branch 1's files, 8 refused-without-field, 6 OPEN standing debt, 3 not a closed list - each with why); stale entries fail |
| `architecture/RequiredRequestIdGuardTest#everyRefusalOfAGivenRequestValueNamesItsField` (widened) | D1386 (D1299 class): a value rule refused in a `catch (NumberFormatException)` (it read only `if` guards), in a package-private static helper called from another service of its package (it read only public ones). A throw in a catch of a parse failure (NumberFormatException, DateTimeParseException, IllegalArgumentException) refuses what its try block parsed. | every service; 0 new pins |
| `architecture/RequestFieldColumnLengthGuardTest` (pins shrunk) | D1387 (D1050 / D1316 class): 13 lab pins deleted (LabResult, LabOrder, LabQcEvent, LabReflexRule request fields now carry `@Size(max = <column>)`). | pins −13 |
| `scripts/lib/unlabelled-reason.test.mjs` (new) | D1389: an undo / correction / rejection reason interpolated bare (under a badge it read as the record's own reason). It goes through `COMMON.REASON_VALUE` or follows a label. | every template; 0 |

## Guards added or widened by round 33 (fix/r33-mar-stay)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `service/emar/MarOrderStandingTest` (new, enumerates `PrescriptionStatus`) + choke point `service/emar/MarOrderStanding` | D1392: a dose or a bedside check on an order whose own status ended it (a cancelled morphine order took allPassed=true and a GIVEN dose). A new status fails the test until someone decides whether it takes a dose. | every `PrescriptionStatus`; 0 |
| `architecture/MarDoseIntervalGuardTest#everyMarWriteAsksWhetherTheOrderStillStands` (widened) | D1392: a file that writes a MAR row (`setAdministeredAt`, `.administeredAt(`, `setScanVerifiedAt`) without `requireOrderStillStands`; the four doors of `NurseTaskServiceImpl` are counted. | every main source; 0 |
| `architecture/CancelledOrderLockGuardTest#aCancelledStayIsNeverWhereANewOrderGoes` (widened) | D1393: an open-stay lookup that reads only the encounter's status (`AdmissionIdIsNotNull` derived queries), and a cancel that does not end the stay's orders and encounter or gives it the discharge's meaning. | every main source; 0 |
| `repository/MarAndOpenStayQueriesPostgresIT` (new) | D1393 / D1409: `findOpenStayEncounters` on PostgreSQL for every admission status, every closed encounter status, a missing admission row, another patient and hospital; the MAR rows' total order for two doses in one minute. | — |
| `db/CancelledStayMigrationIT` (new) | D1393: V263 on rows shaped as dev's (written after the cancel, before it, take-home, held, a cancel with no `cancelled_at`, stays that were not cancelled, a second run). | — |
| `NurseTaskServiceImplTest#d1402_*` (new) | D1402 (D1347 class): a tile computed by a rule of its own. The vitals tile is the list's row count (`dueVitalRounds`), each medication tile a filter of the eMAR rows, the workboard card the eMAR's and the list's rule. | the nurse dashboard tiles; orders and handoffs already read the list's own query (the handoff count's 100-row cap is gone) |
| `architecture/QueryParameterVocabularyGuardTest` (allowlist shrunk) | D1408: `NurseTaskController#status` now goes through `QueryParameterValues.oneOf` on all four lists. | `CHECKED_ELSEWHERE` −1 |

## Guards added or widened by round 33 (fix/r33-prn-ordersets)

| Guard | Class it stops | QA defects | Allowlist today |
|---|---|---|---|
| `service/impl/FrequencyWordingTest` + portal `shared/as-needed-frequency.spec.ts` (new, one fixture) | A trigger word list deciding what an unticked frequency may say: it refused `TPI-SP` and `selon ordonnance` and let `pour douleur` and `max 4/j` through. The rule is inverted: a schedule, and every other word accounted for. `as-needed-frequency.cases.json` holds every fixed-schedule set of the QA scripts (r32, r33), every filed wording, the dropdown, and the repo's frequencies, and BOTH sides read it; a word list differing between Java and the TS mirror fails the test. | D1391, D1395 (D1378, D1360 NOT_FIXED: a net of triggers can only catch what it lists) | none |
| `architecture/MarAsNeededFlagGuardTest` (widened) | Rule 4 now holds `FrequencyWording.classify` to `PrnMinInterval.safetyNet`. Rule 5b: a writer of order-set items without `OrderSetItemQuantityRules`. Rule 6: an edit goes through `applyToEdit` (judged on what it wrote), and the MAR's readers never read the wording rule. | D1391, D1395, D1396 | `FLAG_READERS` unchanged |
| `scripts/lib/as-needed-frequency.test.mjs` (widened) | Only the prescribing form reads the mirror, for a hint; the verdict never sets the tick. | D1391, D1395 | none |
| `controller/PrescriptionWriteAuthorizationIT` (new) | A role that may not prescribe reading a 400 about the body instead of a 403, through the real filter chain. | round 33, RF | the other controllers: tasklist standing debt |
| portal `prescriptions.spec.ts` (`QA D1412`) with `npm run guards` list caps | `/prescriptions` reads one page with the server's total; each tab count is the server's. | D1412 (D1347 class) | list-caps baseline unchanged |

## Guards added or widened by round 33 (fix/r33-lab-patient-meds)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `mapper/LabResultComparatorGradingTest` (new, table) | D1394: a value the system accepts but cannot grade passing as if it were fine. Every value of the report and the variants of comparator, comma, case and unit: `> 6,5`, `<1,5`, `>500 mg/dL`, `7 mEq/L`, `600 MG/DL`, `30 MG/DL`; a bound that settles nothing is UNSPECIFIED + `gradeUnknown`, never NORMAL; calcium, magnesium and any other analyte in mEq/L stay a unit mismatch. | The one grader, `LabResultMapper#determineSeverityFlag`, read by every severity reader. |
| `architecture/LabResultChartRound19GuardTest#d1394_everyReleasePathAsksWhetherTheValueSettledAGrade` (new) | D1394: a path that releases a result without asking whether it settled a grade. | every `setReleased(true)` in main; no allowlist. |
| `architecture/LabResultChartRound19GuardTest#d1394_aLabValueIsReadAsANumberInOnePlace` (new) | D1394: `Double.parseDouble` of a raw value (fails on `>6,5`, reads `NaN` as a number) instead of `LabResultValueRules.numericValue`. | one pin with its reason: the trend direction, which grades nothing. |
| `controller/LabOrderEndToEndIT#aCriticalInAnySpellingReachesTheDoctor` (new) | D1394: through the real chain, each spelling is CRITICAL on entry, pages the doctor on release and closes by read-back. | |
| `scripts/lib/list-cap-scan.mjs` rule `server-limit` (new, part of `npm run guards` → list caps) | D1399 (D1347 class): a read of a `PatientPortalController` list whose limit has a server DEFAULT and no `offset`, so no number appears at the call site for `fixed-limit` or `default-limit` to see. The limited paths are read from the controller, so a new one is covered the day it is added. | 2 pins, previews with the server's total. 2 `default-limit` pins deleted (`getProxyMedications`, `getMyLabResults`). |

## Guards added or widened by round 33 (fix/r33-reception-billing)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `service/ReceptionServiceImplTest$CardsCountWhatTheyOpen`, `reception-cockpit.component.spec.ts` (walks every `.sum-card`) | D1400 + D1401: a summary counted apart from the rows its card opens. Choke point: `ReceptionQueueFilters` | every card, mixed fixture, both sides; a new card on the strip must keep the rule |
| `architecture/ReadOnlyPostGuardTest` (new) | D1404: a POST that only reads, refused in read-only mode. Spring's mapping registry (`testsupport/ControllerMappingRegistry`) over every controller; every POST through the real filter in both directions | a POST named as a read declares `@ReadsOnly` or sits in `WRITES_DESPITE_THE_NAME` with what it writes; `DECLARED_READS` is pinned so declaring a write as a read is seen in review |
| `architecture/PatientIdentityWriterGuardTest` (new) | D1403 + D1414: a writer of a patient's birth date or names that skips `PatientIdentityRules` | known writers listed; `createPatient` stays the backfill's alone |
| `architecture/ReceptionistClinicalReadGuardTest` (new), `controller/support/ReceptionistResponseShapingTest`, `controller/ChartReviewReceptionistAccessTest` | RCPT-CLIN: clinical content reaching a receptionist. Every handler's real `@PreAuthorize` evaluated for the role; the response type walked for clinical field names | removed in `ReceptionistResponseShaping.REMOVED`, or `THE_DESK_NEEDS_IT` with a reason; stale entries fail |
| `architecture/RequestFieldColumnLengthGuardTest` (pins shrunk) | D1418: `InvoiceItemRequestDTO#itemDescription` pins deleted | the sibling invoice DTOs already carry `@Size` |
| `i18n/FieldLabelMeaningTest#providerSignatureIsTheCliniciansInEveryLanguage` (new) | D1411 | en, fr, es |

## Guards added or widened by round 33 (fix/r33-platform-admin)

| Guard | Class it stops | Scope / allowlist |
|---|---|---|
| `npm run guards` → `scripts/check-destructive-confirm.mjs` (new; `lib/destructive-confirm-scan.mjs` + `.test.mjs`) | CX-010 / CX-001 (D1197, D1229 class): a destructive call reachable from a template action with no confirmation on the path. It enumerates every `http.delete`, every service write named as ending something (delete, remove, revoke, deactivate, cancel, archive, discontinue, void, unlink, purge, retire, withdraw, terminate, suspend, decommission, discard, unassign, reject, forceLogout, forceMfa, killFeature, broadcast) plus `DowntimeService.toggle`, follows `this.x()` within the component, and clears a path that asks (`.ask(`), answers a dialog (`(confirmed)`) or sits inside an opened dialog. A correction reason or note on the row is not a confirmation. | every template; `scripts/destructive-confirm-baseline.json`: 3 pins (two inline panels the row's action opens; the typed FORCE LOGOUT ALL phrase) |
| `scripts/lib/list-cap-scan.mjs` rule `server-stream-limit` (new, part of `npm run guards` → list caps) | CX-003: a backend service cutting a list with a fixed number (`.limit(10)`, `.limit(MAX_ROWS)`): the super-admin department table stopped at 10 of 14 with no word, and no portal rule can see a cut the server made. `.limit(limit)` from the caller and `.limit(1)` are not reported. | every `hospital-core/.../service/**.java`; 15 pins with reasons, 7 of them OPEN siblings in tasklist.md round-33 standing debt |
| `architecture/PlatformAnalyticsHonestNumbersGuardTest` (new) | CX-002: a dashboard service setting a metric to a literal (`.staffCount(0)`, `.encounterTrend(emptyList())`); CX-003: a `.limit(` in the analytics service; CX-005: a platform-schema entity with a unique constraint whose duplicate reaches the constraint and comes back naming nothing (a new unique entity fails until declared). | the three dashboard services; every `model/platform` entity; `NOT_A_TYPED_VALUE` 6 (each with why) |
| `repository/PlatformAnalyticsCountsPostgresIT` (new) | CX-002 / CX-003 on real PostgreSQL: every grouped query of the analytics through the real service; the staff count held to `findByHospitalIdExcludingDeletedUsers`, the query of `GET /staff/hospital/{id}`; 15 departments all listed, a prescription anchor left out. | — |
| `scripts/lib/form-behaviour-registry.mjs` `COVERED_DIRS` (widened) | CX-004: `super-admin`, `feature-flags`, `admin/adt-intake-config` are driven by `form-behaviour.spec.ts`. | pins for the plan, flag, emergency and verification submits removed or driven by `expectEmptySubmitRefusal` |
| `scripts/lib/activity-summary-enums.test.mjs` (new) | CX-006: the dashboard joins a record's fields into one sentence in TypeScript, where `i18n:raw-enums` (templates only) cannot see them. Every field of `ACTIVITY_FIELDS` has a label domain or is named free text. | partial by nature: this one table; other sentences built in TypeScript remain outside the raw-enum gate |
| `architecture/RequiredRequestIdGuardTest#NOT_A_TYPED_VALUE` (pin shrunk) | CX-005: `platform.releaseWindow.duplicate` now marks `name`. | pins −1 |

## Guards added or widened by round 33 (fix/r33-followup)

| Guard | Catches | Baseline |
|---|---|---|
| `scripts/lib/list-cap-scan.mjs` rule `server-query-top` (new), `check-list-caps.mjs` scans controllers for it | A derived `findTop50By...` query: the same silent cut as `.limit(50)`, one layer down. The return form's `.limit(50)` sat on a query that stopped at 50 anyway. | pins −7 (storyboard 3, snapshot 3, return form 1), +4 open siblings with tasklist.md |
| `architecture/ReceptionistClinicalReadGuardTest#noClinicalFieldIsWrittenByTheRole` (new rule), `REFUSED` widened | RCPT-CLIN, write side: a request body the role can send holding a clinical field that is neither refused, kept from the stored value, nor reasoned. `EncounterController#checkOut` now refuses the role; on `PUT /patients/{id}` and `PUT /registrations/{id}` the stored text is kept, and `PATCH /patients/{id}` refuses the two clinical fields. | `THE_DESK_WRITES_IT`, each entry with why it overwrites no clinician's text |
| `architecture/ReadOnlyPostGuardTest#theWriteAuditReadsTheSameDeclaration` (new) | Every POST of the application through the real `WriteAuditInterceptor`: a declared `@ReadsOnly` leaves no row, every other POST leaves one unless `@WriteAudited(skip)` names who records it. One declaration for the audit and the read-only filter; the name-based list is gone. | no list |
| `architecture/DoseNumberReaderGuardTest` (new), `cdshooks/rules/DoseRangeTest` (new) | A class that holds a dose's text and parses text must say what a range does there; the table holds ranges in FR, ES and EN with decimal commas, and single doses that must come back untouched. | `KNOWN`, each entry with what a range does there |
| `service/impl/NurseTaskServiceImplTest#routineVitals_*`, `controller/NurseTaskControllerTest#getDueVitalsNoReadableWindowIsTheSharedLookAhead` (new) | The routine interval (2 h) and the look-ahead (30 min) are two constants; the list and the tile read one look-ahead, and a reader's window never stretches the interval. | none |
| `architecture/ReceptionistClinicalReadGuardTest` (vocabulary widened: bloodgroup, abogroup, rhfactor, antibody, triage, isolation, highrisk), `controller/support/ReceptionistResponseShapingTest` (a test per field, receptionist against doctor and nurse) | RCPT-CLIN, user decision 2026-11-06: triage's chief complaint, the blood type and the high-risk flag out of what a receptionist reads, on the same endpoints | `THE_DESK_NEEDS_IT`, `THE_DESK_WRITES_IT` with a reason; stale entries fail |
| `architecture/ReadOnlyAuthWritesGuardTest` (new) | Every write under `/auth` from Spring's mapping registry is classified open, refused, or open only when it blocks the sign-in, and goes through the real `ReadOnlyModeFilter`. A new one fails until classified | `CLASSIFIED`, each class stated; the filter's `OPEN_AUTH_WRITES` must equal it exactly |
| `service/TransfusionRunningBagPostgresIT` (new), `service/TransfusionServiceImplTest` | An incompatible crossmatch on a bag out of the lab reaches the requester and the running nurse, after the commit; a running bag stays ISSUED and shows the result | none |
| `service/PrenatalRecordedRiskCadenceTest` (new: every role x box x recorded state), `repository/PrenatalRecordedRiskPostgresIT` (new), `architecture/ReceptionistClinicalReadGuardTest#aRiskFlagTheRoleCanSendDecidesNothingAlone` (new rule) | The prenatal cadence planned from a box the desk does not count, with the recorded risk unread. A high-risk flag the desk can send must name the class where the record decides instead; only that class reads it, and the recorded value must reach the cadence | `THE_RECORD_DECIDES_INSTEAD`; a stale entry fails |

| `architecture/EmergencyBloodReleaseGuardTest` (new), `service/TransfusionEmergencyReleaseTest`, `service/TransfusionEmergencyReleasePostgresIT`, `service/support/EmergencyReleasePolicyTest` (new) | A bag reaches ISSUED in one line reached by the two issue paths, each checking the bag and writing its release row; a verdict is set in one place behind the rule that fixes it, and a release never writes the crossmatch; the O positive rules live in one class; the action keeps its exact name and "least incompatible" appears in no language. |


## Guards added or widened by round 34 (fix/r34-frequency-dose)

| Guard | Catches | Baseline |
|---|---|---|
| `service/impl/FrequencyWordingTest#aWordingAcceptedAsAScheduleIsNeverUnreadOnTheMar`, `#aWordingConfirmedIsUnreadOnTheMar` (new) | D1419 + D1426, one class: the rule that accepts a wording and the MAR that schedules it disagree. The shared fixture drives `MarNextDose` itself: every accepted wording is never `frequencyUnread` after dose 1, every wording asked about always is. | none |
| `service/impl/FrequencyWordingTest#everyScheduleReadsAsTheFixtureSays`, `as-needed-frequency.spec.ts` (widened) | The server and the portal mirror read each accepted wording to the same kind, minutes and course moments (`readings`), not only the same verdict. | none |
| `service/impl/FrequencyWordingTest#theFixtureGivesEveryFiledWordingOneOutcome` (new) | A wording of D1419 or D1426 with no outcome or two (`filed`). | none |
| `service/impl/FrequencyWordingTest#theWordListsMatchThePortalMirror` (widened) | The word lists of `FrequencyCadence` too. | none |
| `service/impl/FrequencyWordingTest#d1395_theFiledConditionsAreRefusedOnTheTick`, `#aConfirmationDoesNotPassACondition` (new) | D1395, the re-test gate: a condition is refused on `asNeeded`, and confirming never passes it. | none |
| `architecture/MarAsNeededFlagGuardTest` (rule 2 names `FrequencyWording` as the rule's reader) | As before; the rule asks the one reader what a text says. | `FLAG_READERS` unchanged |
| `scripts/lib/as-needed-frequency.test.mjs` (widened) | Both forms answer the question on `frequencyConfirmed`, marked by the server, and neither sets the tick from it. | none |
| `service/impl/FrequencyWordingTest#reviewOfRound34_*`, `#secondReviewOfRound34_*` (new) | A sentence with more in it read at its first cadence: a second phase, a stated gap, two intervals, a ceiling, a repeated single dose, a reason beside a cadence. | none |
| `cdshooks/rules/DoseRangeTest#d1420_*` (new table) | A mixed number or a thousands separator read below what was written, and any valid single dose whose reading changes. | none |


## Guards added or widened by round 34 (fix/r34-stay-orders)

| Guard | Catches | Scope / allowlist |
|---|---|---|
| `architecture/ClosedRecordWriteGuardTest#everyEditMethodRefusesAClosedRecordOrSaysWhyNot` (new) | D1428: a public `update*`, `edit*` or `amend*` of any service that does not refuse a record already in its terminal state. A new edit method is classified on purpose. | Every service; the allowlist says why each entry has no check (no terminal state, a designed reopen, or an open D1428 sibling named in tasklist.md) |
| `architecture/ClosedRecordWriteGuardTest#theNamedWritesKeepTheirCheck` (new) | D1428 / D1432 / D1435: the writes not named as edits, each pinned to its check: the stay's order sets and bed release, a closed visit's PUT and delete, a finalized summary at checkout and on the portal read, a dispensed order, a recorded dose, every way a stay ends closing its nursing work. | 23 pins |
| `architecture/CancelledOrderLockGuardTest` (widened) | The plan's cancelled lock now lives in `requirePlanOpen`, read as a lock. | Same |
| `scripts/lib/glued-colon.test.mjs` (new rule) | D1438: a colon glued to a closing tag. The scan reads a tag as a break, so `<strong>…</strong>: x` passed it; `admin-assignments.html` had the same. | Every template; 0 |


## Guards added or widened by round 34 (fix/r34-reception-billing)

| Guard | Catches | Baseline |
|---|---|---|
| `architecture/ReceptionistClinicalReadGuardTest#noNonClinicalClassReadsAnEncountersClinicalText`, `#everyReaderOfEncounterNotesIsNamed` (new; compiled calls read with ASM, so a variable's name cannot hide one), vocabulary widened (`encounterdescription`, `visitdescription`, `hospitalcourse`, `encountersummary`) | D1421: a billing, front-desk, scheduling or portal class reading `Encounter.notes`, the chief complaint, the instructions, the diagnoses or the note rows; any new reader of `Encounter.notes`. The field vocabulary reads names, and `encounterDescription` read as nothing | `NON_CLINICAL_CLASS_READS` 1 entry (the queue's visit reason, shaped for the desk), `ENCOUNTER_NOTES_READERS` 8 entries, each with who receives the text |
| `mapper/BillingInvoiceMapperTest#noClinicalTextOfTheEncounterReachesTheInvoice_D1421` (new) | Every clinical text of the encounter planted, none in the invoice's JSON, for every reader | none |
| `architecture/ReceptionistClinicalReadGuardTest#theDeskWritesNothingClinicalOnAnEncounter` (new), `REFUSED` widened | D1431: an `EncounterController` write the desk reaches other than a new walk-in and closing an unseen visit | none |
| `architecture/AppointmentSlotRulesGuardTest#aStaffBookingOffTheSlotsNeedsItsReasonAndAPatientTakesASlot` (new) | D1429: the staff create and move lose the overbooking rule, the patient on `POST /appointments` loses the slot, or the portal gains the staff rule | none |
| `architecture/UnknownIdAnswers404GuardTest` (new) | D1437: a lookup that throws a bare `RuntimeException`, or an Optional read with `.get()` after a `find…` | 1 entry (`AuthController`: the user who has just authenticated) |
| `scripts/lib/destructive-confirm-scan.mjs` (widened), `.test.mjs` | D1430, why the gate missed it: it judged the SERVICE method's name, and `/registrations` deactivated through `update(id, { active: false })`. A component method named as ending something, or sending `active: false`, through any writing service method now counts. It also caught `/profile`'s recovery-contact removal, fixed | 3 pins, unchanged |
| `scripts/lib/identity-refusal.test.mjs` (new) | D1422 (a regression): a form reading the identity rules outside `identityRefusals`, or taking refusals without `refuseFields`. `required-refusal.test.mjs` could not see it: the method called `refuseFields` for the blank fields | `patient-form.ts`, whose template binds a message per field |
| `controller/support/PatientResponseShapingTest#noResponseNestsAnAppointment` (new) | D1429: a response type embedding `AppointmentResponseDTO` where `PatientResponseShaping` does not walk, which would send the desk's overbooking reason to a patient or proxy | none |

## Guards added or widened by round 34 (fix/r34-adult-paracetamol)

| Guard | What it catches | Allowlist |
|---|---|---|
| `architecture/AdultParacetamolGuardTest` (new) | A path building a CDS rule context outside `CdsRuleEngine`, which would reach the rules without the paracetamol facts; the adult rule turning into a stop or a review by a side effect; a second reading of the adult age | none |
| `cdshooks/rules/AdultParacetamolRuleTest`, `ParacetamolFactsReaderTest` (new) | Every boundary of the single dose, daily ceiling, interval and each lower group; which orders and which problem rows count | none |
| `controller/PrescriptionEditAndWithdrawalIT#adultParacetamol*` (new) | The alerts over the real create endpoint with real rows, and the reader from a CDS hook with no session (LAZY encounter, QA D1121) | none |


## Guards added or widened by round 34 (fix/r34-maternity-records)

| Guard | Catches | Baseline |
|---|---|---|
| `architecture/AllergyActiveReadersGuardTest` (new) | A class that reads the allergy rows and is not listed with what a deactivated row does there (active only, or history with a mark); a listed reader that lost its filter; the snapshot's problem, diagnosis and medication filters, the storyboard's directive filter | 12 readers, each with its reason |
| `validation/BloodPressurePairGuardTest` (new) | A request payload with a systolic and a diastolic without `isBloodPressureOrdered` (`VitalSignLimits.bloodPressureOrdered`, `@ReportedOn`) or without both pressures bounded inside `VitalSignLimits` (the newborn form refuses only a negative pressure; `NewbornAlertRules` warns instead); the scan reads every class under `payload` | none (read-only `*Response*` payloads placed by name) |
| `i18n/StoredAlertReaderLanguageGuardTest` (new) | A mapper handing a stored alert sentence to the reader; a class writing `NotificationLocales.STAFF` text that is not listed with who reads it; a labour alert code of the bundle that cannot be rendered again from its row in EN, FR and ES | 17 classes, each with its reason |
| `scripts/lib/maternity-date-server-marks.test.mjs` (new) | A date or time input of the maternity screens that the server's `fieldErrors` key cannot reach (no `data-server-field`, no field map) | none |
| `service/maternity/NewbornAlertRulesTest` (new) | Every sourced newborn number at and either side of its boundary, in both glucose units: a threshold moved, a rule dropped, an older stricter urgent alert lost. Each rule was moved by one step and the test failed | none |
| `validation/ClinicalTimestampFutureValidationTest#d1449_*` | A date-only field refused with the wording about a time of day | none |

## Guards added or widened by round 35 (fix/r35-clinical-lab)

| Guard | Catches | Baseline |
|---|---|---|
| `npm run guards` -> `check-event-cancel.mjs` (new; `lib/event-cancel-scan.mjs` + `.test.mjs`) | D1451: a template event expression that can answer `false` (`a && b()`, `!x`, a comparison, `x = false`, a method declared boolean) on a cancelable event of an element that holds or is a field, label, link, button or component. Angular cancels the event and every checkbox inside goes dead. The sanctioned backdrop is `appBackdropClose`. | 0 sites, must stay 0 |
| `architecture/InBasketRecipientGuardTest` (new) | D1461: a class that files an in-basket item for a role the controller, the portal route or the nav link shuts out. The roles are read from the code that picks the recipients; a new sender fails until it is listed. | 5 classes listed, none excused |
| `architecture/PregnancyDatingGuardTest` (new) | D1465 + D1481: a request taking a due date whose service skips `GestationalDating.requireDueDateFitsLmp`; a request's gestational age with no `@Max`. It found `ObgynReferralCreateRequestDTO`, fixed. | none |
| `architecture/ClosedRecordWriteGuardTest#theNamedWritesKeepTheirCheck` (6 pins added) | D1477: vitals, nursing task, handoff, precaution and the chart's vitals keep `DischargedPatientGuard`; a precaution named on an ended stay is refused. | 6 pins |
| `service/support/DischargedPatientGuardTest` (new) | D1477: stay and visit outcomes, one row each. | none |
| `cdshooks/rules/ParacetamolFactsReaderTest`, `DuplicateMedicationOrderRuleTest`, `AdultParacetamolRuleTest`, `PediatricDoseRuleRound18Test` (widened) | D1462 + D1478: a cited brand is the drug; a name nothing names is said, for the order and beside the total; the same drug under another name is a duplicate. | none |
| `service/maternity/NewbornAlertRulesTest#aLowValueAsksToRemeasure` (new) | D1482: the check flag at the low end. | none |
| `scripts/lib/round35-clinical.test.mjs` (new) | D1479: a count of minutes written by hand in a template. D1480: a button built at run time with no global style. | none |
| `e2e/dialog-checkboxes.spec.ts` (new) | D1451 in a browser: mouse, label and a trusted Space key, on the crossmatch box and the O-negative statement (the mortality boxes by click in Karma: the page has no route). | none |

## Guards added or widened by round 35 (fix/r35-desk-billing-portal)

| Guard | What it fails on | Scope / allowlist |
|---|---|---|
| `scripts/lib/tracker-action-map.test.mjs` (new) + `patient-tracker.component.spec.ts` (QA D1463, every route role × every card action) + `security/ActionPermissionsMirrorTest` (6 new entries) | D1463 (D1431 NOT_FIXED: `frontDeskOnly` named only the receptionist): a card button not under a gate of `TRACKER_ACTIONS`, a button gated by a flag naming one role, an action whose endpoint roles drift from the server | the tracker card; no allowlist |
| `architecture/AccessExpiryGuardTest` (new) | D1464: a service storing a request's `expiresAt` / `expiresOn` that never compares it with the clock | every service; none |
| `service/BillingInvoiceServiceImplTest#creditCopay_*` + `repository/CopayFoldSchemaPostgresIT` (new) | D1466: a copay above, equal to or below the visit total not folded once and whole; a CHECK on the real table holding `amount_paid` under the total | the fold; `@Check` removed from the entity (it existed only in the H2 schema) |
| `i18n/ExceptionProseRatchetTest#noFieldRefusalCarriesASentence` (new, not a ratchet) + `FIELD_PROSE` widened to any field expression | D1469: `new FieldBusinessException(<any field>, "<sentence>")` anywhere in `src/main`; a sentence behind a `FIELD_*` constant was invisible | all of `src/main`; 0 allowed. Baseline lowered on 13 files; `RoiRequestService`, `ProResponseService`, `PatientPrimaryCareServiceImpl` joined `ZERO_PROSE_SCOPES` |
| `architecture/PatientContactTextGuardTest` (new) | D1471: a service writing a patient's emergency contact name with no `PatientIdentityRules` call | every service; none |
| `architecture/DeactivatedRegistrationGuardTest` (new) + `repository/RegistrationActiveFlagPostgresIT` (new) | D1476: a desk visit opener (booking, walk-in, waitlist) that does not refuse a deactivated registration; the question itself on real PostgreSQL | 3 openers, listed; add a new one here |
| `scripts/lib/payment-method-names.test.mjs` (new) | D1485: two names for one payment method in a language, or a portal name unlike what the server prints on a document | every object of codes in `en/fr/es.json` against `pdf.payment.method.*`; none |

## Guards added or widened by round 35 (fix/r35-frequency-reader)

| Guard | What it catches | Allowlist |
|---|---|---|
| `service/impl/FrequencyWordingTest#aFormWithAnythingElseBesideItIsNeverASchedule` (new), and its twin in `as-needed-frequency.spec.ts` | D1452-D1459, one class: a cadence read from a part of a sentence. Generated: every form of `grammarForms` with every clause of `matrixClauses`, in front and behind, is not read. | none |
| `service/impl/FrequencyWordingTest#everyFormOfTheGrammarHasItsCadenceInTheFixture` (new) | A line of `FrequencyCadence.GRAMMAR` or `PARTS` with no stated reading, or read otherwise than its fixture line. | `MANANA`, `TARDE` alone are not forms |
| `service/impl/FrequencyWordingTest#theGrammarMatchesThePortalMirror` (new) | A form on one side only. `#theWordListsMatchThePortalMirror` still holds the word lists. | none |
| `service/impl/FrequencyWordingTest#d1472_everyChoiceOfThePrescriptionFormIsASchedule` (new) and `scripts/lib/as-needed-frequency.test.mjs` (widened) | A choice of the form's list that asks, or reads differently in another language, or has no label in one. | none |
| `cdshooks/rules/PediatricDoseRuleRound18Test#d1455_*`, `AdultParacetamolRuleTest#d1455_*` (new) | An unread frequency judged as one dose a day, or a limit card that does not say the total is unknown. | none |
| `service/impl/MarNextDoseTest#d1457_aStoredWordingNotReadStaysOpenAndFlagged` (new) | A stored order the grammar does not read ended, or given a cadence. | none |

## Guards added or widened by round 36 (fix/r36-clinical-registration)

| Guard | What it catches | Allowlist |
|---|---|---|
| `architecture/DeceasedPatientCareGuardTest` (new) | D1488: every POST, PUT, PATCH and DELETE endpoint of every controller is classified: it refuses new care through `DeceasedPatientGuard` (the named service method must call it), or it stays open with why, or its whole controller is not patient care. A new endpoint or controller fails until classified. | `STAYS_OPEN` and `WHOLE_CONTROLLER`, each with its reason; stale rows fail |
| `architecture/DeactivatedRegistrationGuardTest` (widened) | D1489, D1495: the check-in and the patient's own reschedule and pre-check-in join the list, and the check-in refuses before its consent call, with no try around it. | none |
| `repository/DeathRecordEnteredInErrorPostgresIT` (new) | D1488: on PostgreSQL, a row entered in error in any query of the register, and V275's partial index holding one live row per patient. | none |
| `service/pharmacy/OrderedDrugIdentityTest#aCitedBrandIsThisDrugOnlyForItsOwnGeneric` (new) | D1492: a cited brand dispensed from anything but its own generic, a combination brand from one ingredient above all. | none |
| `architecture/DeceasedPatientCareGuardTest#oneEligibilityRule` (new) | The urgent dispute: a service reading the death itself to decide about care, or a refusal not asking `careBlocked`: an exemption of its own, which would admit a patient whose medication or blood stays blocked. | `MortalityServiceImpl` (the death record itself) |
| `service/support/DeathDisputeEmergencyPathwayTest` (new) | A step of the emergency pathway that does not go through the rule. Mocked persistence: QA walks it on a running system. | none |

| `service/impl/WardLoadPostgresIT` (new) | QA D1314: the MAR list, the medication tiles, the vitals round, the workboard or a page of `/prescriptions` growing with the rows again: statement counts on 15,000 orders on PostgreSQL. Also `#marListAgreesWithTheRuleOrderByOrder`: a row the batch read hides or reads differently from the order-by-order rule. | none |
| `architecture/UserProfileFetchGuardTest` (new) | QA D1314: a query that fetches a User without its two profiles, which costs one SELECT per row. | `NOT_YET`: the repositories D1314 did not touch, counted per file; it can only shrink |
| `architecture/JvmContainerBudgetGuardTest` (new) | QA D1314: a native bound dropped from the entrypoint, or a heap default or bound whose worst case no longer fits the container. | A 2 GB limit may be up to 170 MB over: stated in the runbook |

## Guards added or widened by round 36 (fix/r36-frequency-strict)

| Guard | What it catches | Allowlist |
|---|---|---|
| `service/impl/FrequencyWordingTest#everyNumberSlotOfTheGrammarHasItsBound` (new), and its twin in `as-needed-frequency.spec.ts` | D1486, D1487, D1512, one class: a number in a slot of the grammar that takes a value no prescriber means as a schedule. Generated: every line of `FrequencyCadence.GRAMMAR` holding a number, with every number `slotBounds` says its kind of form reads and every one it says is asked. A new line or a new kind of form with no bound fails. | none |
| `filed.D1486`, `filed.D1487`, `filed.D1512`, `filed.R36-slots` in the shared fixture (new, run by every list test of `FrequencyWordingTest` and the spec) | The slots outside the table: clock times joined by "à" or "vers", impossible hours, a lone "1h30", course moments, posology intakes, N/24, zero doses and rates, a number word of another language than its form. Each is asked on both sides and flagged on the MAR. | none |
| `service/impl/FrequencyWordingTest#d1491_aBlankFrequencyIsRefusedAndNeverEndsACourse`, `PrnMinIntervalTest#d1491_*`, `OrderSetItemDispatcherTest#anItem*NoFrequency*`, `PrescriptionEditAndWithdrawalIT#d1491_*` (new) | D1491: no frequency accepted on a new order, or read as a single dose on an older one. Each path that writes an order is named: create, constraint, edit, emptying edit, untick, withdrawal, order-set save, order-set apply. | none |
| `service/impl/NurseTaskServiceImplTest#d1508_*` (new) | D1508: an unread order with no `frequencyUnread` on the MAR until its first dose. | none |

## Clinical rules wave: route, exception and exposure (CR-01, CR-02, CR-03)

| Guard | Class it fails on | Allowlist |
|---|---|---|
| `architecture/OrderRouteEntryPointGuardTest` (new) | CR-01: a dose rule entry point without the ordered route, so an oral limit judges an IV order: an engine overload, a second way to build the order, a caller passing null or the catalogue item's route, a dose rule that stops reading it. | none |
| `architecture/AdultParacetamolGuardTest#theRuleOnlyEverAlerts` (changed) | CR-02: the adult card shapes: an alert, the contraindication, or a held finding with its key; never a hard stop, never a review. | none |
| `cdshooks/rules/AdultParacetamolRouteExposureTest`, `cdshooks/rules/PediatricDoseRouteLimitTest` (new) | The route, held-finding and exposure matrices of the two rules. | none |
| `service/PrescriptionServiceImplTest#cr02_*`, `#cr01_*` (new) | The exception at the signature: token, version, findings, signer, audit; a reason alone, a stored reason and a pharmacist's review pass nothing. | none |
| `repository/MarAndOpenStayQueriesPostgresIT#cr03_*` (new) | The documented doses on PostgreSQL across midnight. | none |
| `service/emar/ParacetamolExposureAfterCommitIT`, `architecture/AdultParacetamolGuardTest#theExposureIsReadOutsideTheDoseAndTheMarTakesNoPatientLock` (new) | CR-03: a failed exposure read undoing a dose, a read inside the dose, a patient lock in the MAR; two concurrent doses, the last to commit counting both. | none |
| `service/PrescriptionServiceImplTest#cr01_*` (new) | The urgent exception of a child's cannot-assess order: a routine order and the review by age cannot use it. | none |
