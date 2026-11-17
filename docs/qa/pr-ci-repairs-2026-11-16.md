# PR CI repairs — 2026-11-16

| Defect | Class | Instances | Correction | Guard/evidence |
| --- | --- | --- | --- | --- |
| CI-LOCALE | A test asserts a French money format while its formatter reads another suite's cached locale | price-list.component.spec.ts on #1196, #1197 and #1198 | Set French storage and reset the locale cache before each test; restore both afterward | Existing strict French price assertions remain unchanged; full portal suites rerun |
| CI-SCRIPTS | Workflow additions violate existing source guards | #1196 scheduling, signatures and ROI; fixed counterparts already present in #1198 | Apply the existing guard repairs without new pins | 579 script tests pass; full lint/build/portal validation |
| CI-SONAR | A negative collection predicate succeeds vacuously on an empty invoice | TariffAtCheckoutTest.theCheckoutDaysPriceApplies on #1197 | Assert the invoice has a line before checking it has no tariff source | Full hospital-core suite; Sonar must confirm on the published head |

CI-LOCALE changes test isolation only; production formatting and the asserted
French grouping remain unchanged. CI-SONAR strengthens the existing assertion;
it does not change prices or invoice transitions. CI-SCRIPTS behavior changes
are the scheduling/signature/ROI guard repairs documented in
super-admin-workspaces-2026-11-16.md. No quality gate is disabled or relaxed.
