# C12: inventory-driven routed-page accessibility

Brief: `test-split-codex-claude-2026-11-12.md`, C12. Executed 11 November 2026 (client date). Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Coverage

The existing CI entry point, `e2e/a11y.spec.ts`, now registers an inventory-driven case for every leaf screen in `app.routes.ts`: **146 routed screens**, including parameterized pages. Redirect aliases and layout parents are not separate pages. Unknown/non-literal route declarations and lazy child-route imports fail inventory construction rather than being silently omitted. Each case checks its final pathname, a rendered heading and captured application exceptions before accepting the audit. Redirecting to another screen cannot count as a pass.

Each screen uses a synthetic role allowed by its route declarations, fake IDs/profile, shared API mocks and intercepted session bootstrap. External requests are blocked for the inventory cases. Icon glyphs come from pinned dev-only `@fontsource-variable/material-symbols-outlined@5.3.9`, served by the test router; no Google font request reaches the network. The dependency is not imported into the application bundle. No buttons are clicked, including on the emergency page. No database, provider, live application, credentials or shared service ports are used.

The axe rules/tags and blocking impact levels match the existing smoke. The existing `color-contrast` exception remains; there are no new suppressions, baseline edits or workflow/gate changes. New routes register through the existing `npm run e2e:a11y` spec entry point.

## Regressions found and smallest corrections

The pre-correction full run completed 146 scans with **139 failures and 7 passes**. Most repeated failures were unreadable icon-font fallback text under offline conditions; the local real-font fixture fixes the harness, without hiding icons or changing accessible names. The remaining findings led to these minimal production template corrections:

- Name notification channel toggles using their translated notification type and channel.
- Name the hospital/status filters in assignments, the environment/action filters in governance, and status/type filters in signatures using existing translations.
- Remove an unsupported tablist role from ordinary section-switching buttons; no tab keyboard contract is claimed.
- Expose the emergency search's `aria-controls` only while its results element exists. No emergency action behavior changes.
- Make the shell's scrollable main region reachable by keyboard (`tabindex="0"`); its skip-link target remains the same.

An earlier server attempt was stopped after Angular's dependency prebundling produced an unlinked-library/JIT startup error. That was failed setup evidence, not an accessibility verdict. The final local-only server config disables prebundling, binds `127.0.0.1` on an OS-assigned port and uses an empty proxy file. Repository serve/CI configuration is unchanged.

## Verification

From isolated `C:/dev/hms-qa-c1/hospital-portal`:

```powershell
npm.cmd exec playwright -- test --config=C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c12.config.ts
# Exit 0: 153 passed (146 inventory cases, 6 existing smoke cases, 1 mocked-login setup).
npm.cmd run test:headless -- --browsers=ChromeHeadlessNoSandbox --include=src/app/shell/shell.spec.ts --include=src/app/slot-admin/slot-admin.spec.ts --include=src/app/super-admin/emergency/emergency.spec.ts --include=src/app/admin-assignments/admin-assignments.spec.ts --include=src/app/admin-governance/matrix-tab.spec.ts --include=src/app/digital-signatures/digital-signatures.spec.ts --karma-config=C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c2-karma.cjs
# Exit 0: 162 passed.
npm.cmd run lint
# Exit 0.
npm.cmd exec eslint -- e2e/a11y.spec.ts e2e/a11y-routed-pages.ts e2e/helpers/routed-page-inventory.ts e2e/helpers/local-icon-fonts.ts
# Exit 0 (explicitly includes E2E sources, outside the standard lint script).
npm.cmd run format:check
# Exit 0.
npm.cmd exec prettier -- --check e2e/a11y.spec.ts e2e/a11y-routed-pages.ts e2e/helpers/local-icon-fonts.ts e2e/helpers/routed-page-inventory.ts
# Exit 0.
cd ..
git diff --check
# Exit 0.
```

Touched files were formatted with Prettier (exit 0). The pinned dev dependency install used `npm.cmd install --save-dev --save-exact @fontsource-variable/material-symbols-outlined@5.3.9 --ignore-scripts --no-audit --no-fund --cache C:/Users/tiego/.codex/companions/cache/npm` (exit 0). Software dependency downloads are separate from provider/application calls.

Logs/artifacts are under `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/`: `c12-complete-tests.log`, `c12-complete-results.json`, `c12-component-tests.log`, `c12-lint.log`, `c12-targeted-final-lint.log`, `c12-format.log`, `c12-e2e-format.log`. Each route attaches its pathname, role, parameterization, application errors and all axe violations, including nonblocking findings. The failed pre-correction result is retained as `c12-before-correction-results.json`.

## Limits

This is automated initial-render coverage under mocks. It does not establish accessibility of every populated dataset, dialog, interaction or role permutation; detail routes use synthetic IDs and may render an empty/retry state. Contrast, keyboard-only/manual review, assistive technology testing and real-device coverage remain open. CI on the published head remains separate evidence. Do not tick the checklist's **full accessibility audit** solely from these results.

## Published CI blocker

On draft PR #1123 at `3ad2b95e61c2d1654ff2dec806eac6ca74dc3c69`, the a11y smoke, SonarCloud and CodeQL checks passed. The frontend lint/build/test job stopped at `npm run guards` (exit 1), causing the aggregate gate to fail: [job log](https://github.com/DevFaso/hms/actions/runs/43145846884/job/130943413950).

The name guard found zero new defects and six stale pins in `hospital-portal/scripts/a11y-names-baseline.json`: the two assignment filters (`filterActive`, `filterHospitalId`), two governance filters (`auditFilter`, `environment`), and two signature filters (`statusFilter`, `typeFilter`). Their missing-label defects were corrected by this PR. The guard requires deleting those obsolete entries.

The assignment explicitly prohibits baseline edits. None was made. On 12 November 2026 the user explicitly decided: **keep the baseline untouched and leave C12 blocked**. Do not remove these six pins as an automatic CI repair. Retained local CI log: `c12-ci-frontend-failure.log`. Local passing tests are not a claim that the complete published CI gate passed.

This decision-only report update was checked with Prettier and `git diff --check` (both exit 0). Application source and baseline files were unchanged; application tests were not repeated for this documentation-only update.
