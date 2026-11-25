# Codex display fixes awaiting a dev re-test

These entries describe code and local evidence. They are **fixed-pending-retest**,
not scorecard passes or proof of a dev deployment. Claude owns the dev re-test.

| Defect | Class and sibling paths | Evidence and guard | Status |
|---|---|---|---|
| D3650 | Quantities joined directly to an uninflected dosage unit: received/remaining lots, medication history, held stock, prescription quantity | Shared `countUnit`; real goods-receipt DOM and production rollback test; `presentation-regressions` scans every template | fixed-pending-retest |
| D3651 | Unknown patient-directory counts displayed as zero during loading or failure | Matching count now follows the existing unknown-state KPI behavior; patient-list tests and template guard | fixed-pending-retest |
| r72 imaging category | An invoice line's coarse SERVICE code hid its already stored IMAGING tariff category, both while displaying and editing | Read-only stored-category DTO projection; all categories/null and unchanged-amount mapper tests; billing DOM and template guard. No price lookup, coverage rule or calculation changes | fixed-pending-retest |
| r72 read-only timeout | A failed read described an uncertain write | Existing read-only behavior verified by three locale banner tests; this branch adds regression evidence | fixed-pending-retest |
| D3701 | Nested record-rights form controls exceeded their phone-width card | Bounded form/card/control sizing; real request and desk journey at 390/1440 px | fixed-pending-retest |
| D3703 | The delivery administration namespace copied English into Spanish | Full Spanish namespace translated; copied/missing-leaf guard without a prose exemption baseline | fixed-pending-retest |
| D3704 | Two consent labels implied a narrower effect than their shared sender categories | All three locales now say appointments/follow-up and pharmacy cover **all messages**; shared label tests. Sender behavior is unchanged | fixed-pending-retest |
| D3705 | Verification gave neither a sent acknowledgement nor the destination | Patient/desk live status announces the masked actual destination and expiry; safe backend mask, stale-response and role-loss tests, real browser flow. Raw recipients/codes are never returned | fixed-pending-retest |
