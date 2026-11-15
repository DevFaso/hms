# C11: scheduled-report field errors

External brief (not committed here): `test-split-codex-claude-2026-11-12.md`, C11. Executed 11 November 2026 (client date). Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Regression and smallest correction

The create form displayed server recipient validation beside its control, but server errors for `name`, `reportType` and `period` fell back to a general toast. New DOM-level tests reproduced this: before the correction, four tests failed (three field cases plus simultaneous field errors), native exit 1.

The small production correction in `reports.ts` maps those three server field names to their existing control IDs and uses the established `refuseFields` helper. Each reason appears beside the appropriate input/select with `aria-invalid`, an alert message and `aria-describedby`; focus goes to the first refused control. Existing recipient handling remains. The reset list now includes both selects so a corrected retry removes stale errors. No backend, validation policy, translations, thresholds or CI changes.

Six new Karma cases cover each of the four server fields, simultaneous name/period errors followed by success, and a subsequent response that refuses a different field while the old error is cleared. Existing cases also cover missing required values, malformed recipients, generic backend refusal and in-flight submission handling. All service calls are spies; no HTTP or live environment is involved.

## Verification

Original author verification from an isolated portal checkout:

```powershell
npm.cmd run test:headless -- --browsers=ChromeHeadlessNoSandbox --include=src/app/reports/reports.spec.ts --karma-config=PATH_TO_LOCAL_LOOPBACK_KARMA_CONFIG
# Before production correction: exit 1, 4 failures / 13 passes.
# Original final: exit 0, 18 passes; one stale-error regression was added after the 17-case before-run.
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

The ephemeral external Karma config loads the repository config and binds only `127.0.0.1` on OS-assigned port 0. It and the original command logs are not committed artifacts; use the PR CI runs as shared evidence. No shared port or tracked test configuration changed.

CI/Rex checks are separate from these local results. No live QA checklist line is ticked by component-level evidence.

Review follow-up, 12 November: unmapped field refusals remain visible in a toast even alongside mapped inline errors; blank recipient reasons no longer suppress the fallback or steal invalid-field focus. `REPORT_FIELD_IDS` only controls clearing and explicit focus order: client blank validation still checks name and recipients explicitly. Three regressions add mixed-error/focus, blank-recipient fallback and stale-recipient clearing coverage. The retry test now checks that old markers disappear while the second request is still pending, before the dialog closes. Focus is asserted with the fixture attached to the document. The final local selection passes all 21 Karma cases; full portal ESLint, changed-file Prettier and whitespace checks pass. No backend validation policy changed.
