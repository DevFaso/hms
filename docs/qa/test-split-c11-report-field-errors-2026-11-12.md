# C11: scheduled-report field errors

Brief: `test-split-codex-claude-2026-11-12.md`, C11. Executed 11 November 2026 (client date). Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Regression and smallest correction

The create form displayed server recipient validation beside its control, but server errors for `name`, `reportType` and `period` fell back to a general toast. New DOM-level tests reproduced this: before the correction, four tests failed (three field cases plus simultaneous field errors), native exit 1.

The small production correction in `reports.ts` maps those three server field names to their existing control IDs and uses the established `refuseFields` helper. Each reason appears beside the appropriate input/select with `aria-invalid`, an alert message and `aria-describedby`; focus goes to the first refused control. Existing recipient handling remains. The reset list now includes both selects so a corrected retry removes stale errors. No backend, validation policy, translations, thresholds or CI changes.

Six new Karma cases cover each of the four server fields, simultaneous name/period errors followed by success, and a subsequent response that refuses a different field while the old error is cleared. Existing cases also cover missing required values, malformed recipients, generic backend refusal and in-flight submission handling. All service calls are spies; no HTTP or live environment is involved.

## Verification

From isolated `C:/dev/hms-qa-c1/hospital-portal`:

```powershell
npm.cmd run test:headless -- --browsers=ChromeHeadlessNoSandbox --include=src/app/reports/reports.spec.ts --karma-config=C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c2-karma.cjs
# Before production correction: exit 1, 4 failures / 13 passes.
# Final: exit 0, 18 passes.
npm.cmd exec prettier -- --write src/app/reports/reports.ts src/app/reports/reports.spec.ts
# Exit 0; spec formatting repeated after the last case was added.
npm.cmd run format:check
# Exit 0.
npm.cmd run lint
# Exit 0 on the final source.
cd ..
git diff --check
# Exit 0.
```

The external Karma config loads the repository config and binds only `127.0.0.1` on OS-assigned port 0. No shared port or tracked test configuration changed. Logs: `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c11-regression-before.log`, `c11-final-tests.log`, `c11-final-lint.log`, `c11-format.log`.

CI/Rex checks are separate from these local results. No live QA checklist line is ticked by component-level evidence.
