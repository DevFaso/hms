# Laboratory results: compact list and evolution

The results list now displays ten rows initially, with 10/25/50 rows per page, previous/next controls, page count and the visible result range. Searching, changing tabs and changing page size reset the page. All existing result actions remain available in a single horizontal group; the table scrolls horizontally on smaller screens instead of expanding rows vertically.

Opening a result requests its detail through the existing authorized GET endpoint. Its patient/test history appears above reference ranges in a wider detail panel. The chart uses elapsed time on the horizontal axis and exact numeric observations on the vertical axis. Units are separate selectable series, with the selected result's unit preferred. Comparator, qualitative, undated and unknown-unit observations are retained in the observations table but excluded from plotting. Corrections retain their badges and distinct chart markers. Historical versions may be present in laboratory history; the explanatory text makes this explicit. No reference band, threshold or clinical interpretation is invented.

History loading and failure are distinct from insufficient history. Late responses are ignored after closing or switching patient details; a hospital scope change closes the detail. A mismatched detail response is rejected.

This is frontend pagination over the existing all-pages loader, not new server pagination. No backend, authorization, clinical rule, release, signing or correction policy changes are included.

Validation uses synthetic data and intercepted local APIs only. Repository screenshots are linked below. Browser checks cover French, English and Spanish, desktop/tablet/mobile, pagination, filtering, mixed units, correction markers, history failure and accessibility. Unit coverage includes page changes, filtering, stale history, history failures and numeric/unit/date exclusions.

Initial validation, before the follow-up recorded below: 4,975 frontend tests, 535 script tests, production build, ESLint, formatting, coverage floors, repository guards and translation parity/reference checks passed. That browser run passed 11 screenshot/accessibility checks with no page errors; desktop rows measured 53px in FR/EN/ES.

## Rex review corrections

Repository-visible previews (synthetic patient data): [compact list](assets/lab-results-evolution/results-fr.png), [evolution and observations](assets/lab-results-evolution/observations-fr.png).

| Finding | Class and hunt | Correction | Regression / class guard | Counterfactual evidence |
| --- | --- | --- | --- | --- |
| History/deltas | Replacing a clinical history display dropped prior information. `rg` across portal/core found one evolution component and its caller, and the now-unused `sortedTrendHistory` / `formatTrendDelta` methods. The separate comparison modal remains unchanged. | Restore newest-first observations and deltas; only adjacent exact observations with matching known units and strictly ordered dates have a delta. Remove the unused unsafe methods. | `result-evolution.component.spec.ts`, `result-evolution.spec.ts`, repository-wide `scripts/result-evolution-contract.test.mjs` | Before correction: `Expected 2 to be 3`; class guard failed on missing `LAB_RESULTS.DELTA`. |
| Display state | Result-specific unit selection was stored independently of result identity in the only evolution component. Hunt: every `<app-result-evolution>` caller. | Reset selection through `linkedSignal` when `resultId` changes; require the identity at every caller through the guard. | Direct component reuse test and caller-identity source guard. | Before correction the guard lists `lab-results.html` as missing `[resultId]`. |
| Empty value | The new chart called `.trim()` on a nullable server observation. Hunt: chart parser, old delta parser, DTO, value validation, comparison calculations. Backend `requireValidValue` explicitly allows absent values. The comparison endpoint is separate and is not used to build this chart. | One null-safe exact-number helper for chart and deltas. | Empty, comparator, incompatible-unit and coincident-date tests. | Original helper called `point.resultValue.trim()` without checking null. |
| Correction identification | The chart distinguished corrections only by colour; the observations table already has correction badges. | Hollow correction markers, with explicit correction text in the point title and table badge. | Component DOM/computed-style test plus browser check. | Before correction: expected white hollow fill, received brown solid fill. |
| CI unit selector | Raw-enum checker treats a new `<option>` variable as a possible enum. Traced `LabResult.resultUnit` and `LabResultTrendPointDTO.resultUnit` to String measurement-unit fields. | Document the exact free-text unit site in both existing allowlists. No enum translation, checker logic or thresholds changed. | Existing raw-enum and lab free-text guards remain strict for every other site. | Original CI failed `RAW ENUM ...::<option>u`. |

Review comments verified without a production change:

- Decimal commas are explicitly decimal separators in `LabResultValueRules.numericValue` / `normalizeDecimalComma`, invoked by create and amend. `1,200` means `1.200` in this contract, not a thousands-grouped integer; the portal formatter follows the same rule. Tests retain this interpretation and reject multi-separator text. Excluding three-decimal comma values would discard valid precision without an approved policy change.
- `app.config.ts` supplies `LOCALE_ID` from `currentLocale`; `switchLanguage` reloads the page. Axis `DatePipe` therefore uses the selected locale. No confirmed FR/ES US-date regression.
- `apiPrefixInterceptor` uses `effectiveHospitalIdForRequest` and omits hospital headers in global view. `LabResultController` detail/list roles match through `RoleExpansion` (verified super-admin inherits doctor and lab scientist); `readLabResultById` authorizes the result before querying readable patient/test history. No permission expansion was needed.

Behaviour changes: observations regain their previous newest-first order and delta column. Deltas across different units, censored values, empty observations or coincident/invalid dates display a dash rather than a misleading subtraction. Unit selection resets when another result is opened. Corrected observations gain a hollow marker. Clinical grading, release, signing and data visibility are unchanged.

### Follow-up on the published watcher revision

The scheduled watcher independently published `90e486059` while the interactive review was running. Its unit reset, delta restoration, hollow markers and exact free-text exceptions were integrated before the follow-up; no force push was used.

The detail history DTO supplies only date/value/unit and a correction flag, not a reliable identity for the superseded original. Histories containing corrections therefore use an explicitly labelled recorded-version diagram: discrete points, no connecting line and no deltas. Audit observations and correction badges remain readable. This does not claim to reconstruct a canonical clinical trajectory from version records; canonical version filtering would require additional backend metadata.

Reloads after actions retain the selected page, while user filters, tabs, page size and hospital scope still reset it. Displayed deltas retain the operands' decimal precision (up to 20 decimal places) rather than imposing two decimals. Invalid dates sort after valid observations and cannot produce a delta. Component and pager regression tests cover these paths.

Final follow-up validation: `npm run test:coverage` passed 4,989 tests; `npm run test:scripts` passed 537; build, lint, formatting, i18n/RBAC/repository guards and coverage floors passed. Fourteen local screenshot/accessibility checks passed without page errors. Backend `:hospital-core:test -PtestForks=1` passed with six tasks up to date in 29 seconds; backend sources are identical to the previously verified backend revision.

### Failed CI follow-up

Run 42527516550 failed because the D1428 lab test called `component.isEditable()` without creating its own fixture. Jasmine random ordering sometimes ran that test before any other test initialized the shared variable. Running only that assertion reproduced the exact CI TypeError (one failed test); adding its own awaited setup made the same isolated run pass. Full verification after incorporating the retained clamped-page correction passed 4,993 tests, lint, formatting, build and coverage floors. No clinical limits, role policies or backend code changed.
