# PR CI repairs — 2026-11-16

| Defect | Class | Instances | Correction | Guard/evidence |
| --- | --- | --- | --- | --- |
| CI-LOCALE | A test asserts a French money format while its formatter reads another suite's cached locale | price-list.component.spec.ts on #1196, #1197 and #1198 | Set French storage and reset the locale cache before each test; restore both afterward | Existing strict French price assertions remain unchanged; full portal suites rerun |
| CI-SCRIPTS | Workflow additions violate existing source guards | #1196 scheduling, signatures and ROI; fixed counterparts already present in #1198 | Apply the existing guard repairs without new pins | 580 script tests pass on #1196; full lint/build/portal validation |
| CI-SONAR | A negative collection predicate succeeds vacuously on an empty invoice | TariffAtCheckoutTest.theCheckoutDaysPriceApplies on #1197 | Assert the invoice has a line before checking it has no tariff source | Full hospital-core suite; Sonar must confirm on the published head |

CI-LOCALE changes test isolation only; production formatting and the asserted
French grouping remain unchanged. CI-SONAR strengthens the existing assertion;
it does not change prices or invoice transitions. CI-SCRIPTS behavior changes
are the scheduling/signature/ROI guard repairs documented in
super-admin-workspaces-2026-11-16.md. No quality gate is disabled or relaxed.


## Published repair evidence

PR #1196 includes the concurrent review repairs from `31b709c7e`, merged rather
than replaced, and the scheduling coverage repair in `0d8c9ffe4`. The full portal
suite passes 5,600 tests; all coverage floors pass (scheduling branch coverage
89.01%, existing floor 71%). The full hospital-core suite, production portal
build, lint and 580 script tests pass locally. CI remains an independent check.

| Defect | Class | Instances and correction | Regression evidence |
| --- | --- | --- | --- |
| CI-GROUP | A required checkbox group has no native fieldset `required` attribute | Bulk scheduling weekdays: explicit `data-required-group` plus nonempty `aria-describedby`, a visible required explanation, and existing submission refusal retained | Required-field scanner accepts only both metadata attributes; negative cases reject either attribute alone; existing scan initially failed this fieldset |
| CI-COVERAGE | New scheduling refusal and workflow branches lacked exercised behavior | Bulk validation, failures, existing-day handling and reset paths | 39 scheduling tests; removing the added cases leaves the previously observed 60.24% branch coverage, below the unchanged 71% floor |

PR #1197 independently published the equivalent nonempty invoice assertion in
`8d1c7beda`, followed by additional upstream changes. The stale isolated local
checkout was not pushed over those changes. Its current CI/Sonar must be checked
on the latest remote head, not inferred from an older local run.
