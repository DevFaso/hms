PR 281 branch name chore/keycloak-verify-script
scripts/verify-keycloak-admin.ps1
# error body for each env. dev intentionally omitted — admin user there is
# `tiego`, not `kc-admin`; verify dev manually via the browser.

$ErrorActionPreference = 'SilentlyContinue'
Copilot commented 11 hours ago
Copilot
Copilot
AI
11 hours ago
$ErrorActionPreference is set to 'SilentlyContinue', which can hide non-terminating errors and makes this script behave differently from other PowerShell scripts in this repo (e.g., scripts/kc-inspect.ps1 and scripts/seed-keycloak.ps1 use 'Stop'). Consider switching to 'Stop' (and then selectively using -ErrorAction where needed) so unexpected failures surface immediately.

Suggested changeset 1 (1)
scripts/verify-keycloak-admin.ps1
Original file line number	Diff line number	Diff line change
 # error body for each env. dev intentionally omitted — admin user there is
 # `tiego`, not `kc-admin`; verify dev manually via the browser.
 $ErrorActionPreference = 'SilentlyContinue'
 $ErrorActionPreference = 'Stop'
 $envs = @(
   @{
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
scripts/verify-keycloak-admin.ps1
Comment on lines +47 to +54
  } catch {
    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'ERR' }
    $body = if ($_.ErrorDetails) { $_.ErrorDetails.Message } else { $_.Exception.Message }
    Write-Output "  HTTP $code  $body"
  }
}

if ($anyMissing) { exit 2 }
Copilot commented 11 hours ago
Copilot
Copilot
AI
11 hours ago
On failed login/token requests you print the HTTP status/body, but the script still exits 0 as long as the env vars are present. For a "verify" script this makes it hard to use in automation and easy to miss failures; track whether any environment returned an error in the catch block and exit non-zero at the end when any verification fails.




PR 283 branch name feat/v1.0-idle-session-timeout
docs/roadmap.csv
v1.0,2026-06-23,Pharmacy,T-71 Playwright E2E,"One end-to-end test per dispense path: Tier 1 in-house, Tier 2 partner, refill, AMU export",—,S,Frontend,not-started,#h1.exit
v1.0,2026-06-23,Pharmacy,T-72 perf baseline,k6 script at 50 concurrent dispenses; p95 < 800ms; recorded in docs/observability/performance-baseline.md,—,S,Platform,not-started,#h1.exit
v1.0,2026-06-23,Security,Idle session timeout (server-side),Track last-activity in Redis; reject requests after 15 min idle; JWT silent refresh respects window,—,M,Backend,not-started,#h1.exit
v1.0,2026-06-23,Security,Idle session timeout (server-side),Track last-activity in Redis; reject requests after 15 min idle; JWT silent refresh respects window,—,M,Backend,started,#h1.exit
Copilot commented 6 hours ago
Copilot
Copilot
AI
6 hours ago
The PR title suggests implementing the idle session timeout feature, but this change only updates the roadmap status. To avoid confusion in history/release notes, either include the actual implementation changes in this PR or rename/scope this PR as a docs/chore update.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
docs/roadmap.csv
Comment on lines +2 to +3
v1.0,2026-06-23,Clinical Safety,CDS Hooks expansion,Add order-select and medication-prescribe hooks with RxNorm bindings; integration tests against existing prescriptions module,—,M,Backend,completed,#h1.exit
v1.0,2026-06-23,Clinical Safety,Drug-drug interaction check,Extend hms-medication-allergy-check to fire on coexisting prescriptions; warn-card on critical interactions,CDS Hooks expansion,M,Mixed (Backend + UI),completed,#h1.exit
Copilot commented 6 hours ago
Copilot
Copilot
AI
6 hours ago
This PR updates statuses for Clinical Safety items in addition to the idle session timeout entry. If the intent is to track only the idle session timeout work here, consider moving unrelated roadmap status changes to a separate PR to keep changesets focused and easier to audit.


hospital-core/.../java/com/example/hms/security/IdleSessionGate.java
Remove this method and declare a constant for this value.

Intentionality
Maintainability


3
Low
confusing
Open
Not assigned
L103
5min effort
6 hours ago
Code Smell
Minor
Use "Arrays.copyOf", "Arrays.asList", "Collections.addAll" or "System.arraycopy" instead.

Intentionality
Maintainability


3
Low
clumsy
Open
Not assigned
L130
5min effort
6 hours ago
Code Smell
Minor
hospital-core/.../com/example/hms/security/oidc/KeycloakHospitalContextFilter.java
Complete the task associated to this TODO comment.

Intentionality
Maintainability



Info
cwe
Open
Not assigned
L79
0min effort
6 hours ago
Code Smell
Info
hospital-core/.../java/com/example/hms/security/InMemoryIdleSessionTrackerTest.java
Remove this use of "Thread.sleep()".

Intentionality
Maintainability


2
Medium
bad-practice
tests
Open
Not assigned
L38
20min effort
6 hours ago
Code Smell
Major
Remove this use of "Thread.sleep()".

Intentionality
Maintainability


2
Medium
bad-practice
tests
Open
Not assigned
L71
20min effort
6 hours ago
Code Smell
Major
hospital-core/.../java/com/example/hms/security/RedisIdleSessionTrackerTest.java
Refactor the code of the lambda to have only one invocation possibly throwing a runtime exception.

Intentionality
Maintainability


2
Medium
junit
tests
Open
Not assigned
L142
5min effort
6 hours ago
Code Smell
Major



PR 284 branch name feat/v1.0-dr-runbook
docs/runbooks/disaster-recovery.md
Comment on lines +63 to +65
      and confirm `flyway_schema_history` (or `databasechangelog` for
      Liquibase) ends at the migration that was current at the chosen
      restore point.
Copilot commented 5 hours ago
Copilot
Copilot
AI
5 hours ago
The drill checklist references flyway_schema_history, but this repo’s backend uses Liquibase (and Flyway is disabled in configs). This will mislead operators during a drill; suggest removing the Flyway mention and documenting the expected Liquibase table/fields to check instead (e.g., databasechangelog).

Suggested changeset 1 (1)
docs/runbooks/disaster-recovery.md
Original file line number	Diff line number	Diff line change
 - [ ] **§ 3.1 Postgres PITR to a fresh database** — restore the
       production WAL stream into a *new* Railway Postgres instance
       and confirm `flyway_schema_history` (or `databasechangelog` for
       Liquibase) ends at the migration that was current at the chosen
       restore point.
       and confirm Liquibase `databasechangelog` ends at the changeset
       that was current at the chosen restore point (verify the latest
       `id`, `author`, and `filename` values match the expected release).
 - [ ] **§ 4 Service redeploy** — pick the `hms-backend` service in
       Railway, redeploy the last green build from one week prior, and
       observe the `HmsErrorBudgetSlowBurn` alert reset within 5 min.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
docs/runbooks/disaster-recovery.md
3. **Validate the restored DB before cutover.** From a local shell:
   ```bash
   psql "$RESTORE_DB_URL" -c "SELECT COUNT(*) FROM clinical.patients WHERE is_deleted = FALSE;"
   psql "$RESTORE_DB_URL" -c "SELECT id, filename, executed_on FROM databasechangelog ORDER BY executed_on DESC LIMIT 5;"
Copilot commented 5 hours ago
Copilot
Copilot
AI
5 hours ago
The databasechangelog query uses executed_on, but Liquibase’s tracking table column is typically dateexecuted (or dateExecuted depending on quoting). As written, this command will fail during a restore validation; update the query to use the actual Liquibase column names.

Suggested changeset 1 (1)
docs/runbooks/disaster-recovery.md
Original file line number	Diff line number	Diff line change
 3. **Validate the restored DB before cutover.** From a local shell:
    ```bash
    psql "$RESTORE_DB_URL" -c "SELECT COUNT(*) FROM clinical.patients WHERE is_deleted = FALSE;"
    psql "$RESTORE_DB_URL" -c "SELECT id, filename, executed_on FROM databasechangelog ORDER BY executed_on DESC LIMIT 5;"
    psql "$RESTORE_DB_URL" -c "SELECT id, filename, dateexecuted FROM databasechangelog ORDER BY dateexecuted DESC LIMIT 5;"
    ```
    The `databasechangelog` query confirms Liquibase state. If the most
    recent entry is the migration *before* the bad one, you're good.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
docs/runbooks/disaster-recovery.md
Comment on lines +196 to +201
**Prerequisites** — these must be recoverable from outside Railway:
- GitHub repo access (`origin = https://github.com/DevFaso/hms.git`).
- The current production secrets (see § 7 for the inventory).
- The latest Postgres backup file (§ 3 export-to-file procedure).
- The latest Keycloak realm export (`keycloak/prod/realm-export.json`
  in the repo, or the most recent manual export).
Copilot commented 5 hours ago
Copilot
Copilot
AI
5 hours ago
§5 requires “the latest Postgres backup file” and points to a “§3 export-to-file procedure”, but §3 only covers Railway PITR restore and doesn’t document how to produce/obtain backup.dump outside Railway. For a full project-loss scenario this is a blocking gap—either add an explicit export/download procedure (and where it is stored) or adjust §5 to use Railway’s snapshot export flow (if available) and document that path.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
docs/runbooks/disaster-recovery.md
Comment on lines +250 to +256
Production runs Grafana Cloud; provisioning is via the
[`grafana/provisioning/**`](../../grafana/provisioning) tree synced
through the Grafana Cloud API. Re-sync via:

```bash
./scripts/grafana-sync.sh   # if the script exists; otherwise re-import via the UI per dashboard
```
Copilot commented 5 hours ago
Copilot
Copilot
AI
5 hours ago
This runbook suggests running ./scripts/grafana-sync.sh, but that script doesn’t exist in the repo. Even with the “if the script exists” note, including a non-existent command in a DR runbook can slow recovery; either add the script (and document required env vars) or replace this with the concrete, repo-backed provisioning/import steps used in this project.



PR 285 branch name feat/v1.0-fr-completeness-gate
