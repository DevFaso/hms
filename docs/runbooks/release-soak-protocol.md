# Release soak protocol

> Roadmap row 13 deliverable: "freeze `feat/*` for 1 week, only
> `fix/*` allowed during soak". This runbook is the policy that
> phrase points at — what's frozen, what's allowed, who can
> override, how the soak ends.
>
> Pair this with [docs/releases/v1.0.0-rc1.md](../releases/v1.0.0-rc1.md)
> (the rc1 cut) and [scripts/cut-rc1.sh](../../scripts/cut-rc1.sh)
> (the helper script that pushes the tag).

## When the soak begins

The soak begins the moment a release-candidate tag is pushed
(e.g. `v1.0.0-rc1`). The release manager records the cut time +
the planned soak-end time in the GitHub release body and pins it
to the top of the team channel.

The soak is **7 calendar days** by default. Extending past 7 days
needs a written sign-off from the release manager and product owner
(comment on the GitHub release thread is sufficient).

## Branch-merge policy during soak

The freeze applies to the release branch (`main` for production
soak; `uat` if soaking on UAT first per the established
`develop → uat → main` chain). Other branches are unaffected.

| Branch prefix | Merge to release branch during soak | Rationale |
| --- | --- | --- |
| `feat/*` | **Forbidden** | Net-new behavior is the exact thing the soak is testing the absence of. |
| `feat/v1.1-*` | **Forbidden on release branch.** Allowed against `develop` so v1.1 work continues. | Same as above. |
| `fix/*` | **Allowed** | Bug fixes that need to land before GA. Each requires the post-merge gates below. |
| `chore/*` | **Allowed** if scope is dependency bumps or cleanup with no behavior change. CHANGELOG entry mandatory. | Some chores (test-only, doc-only) shouldn't gate the cut. |
| `docs/*` | **Allowed** | Documentation drift discovered during soak. |
| `revert/*` | **Allowed** with release-manager sign-off | Reverting a `feat/*` that landed before the cut and is mis-behaving. |
| `hotfix/*` | **Allowed**, treated as a `fix/*` with extra urgency | Reserved for production incidents. |

A merge that violates the policy is reverted on sight; the author
opens a `fix/*` PR if the underlying issue is real.

## Per-merge gates during soak

Every PR merged during the soak window MUST:

1. Pass all CI gates that ran on the cut tag (backend tests,
   jacoco verification, frontend lint / format / Karma /
   Playwright, FR i18n parity, production build).
2. Have at least one human review (no `--admin` merges).
3. Be referenced in the soak log (see below).

For a `revert/*`, also: the release manager signs off in the PR
review explicitly, and the revert reason is recorded in the soak
log.

## Soak log

The release manager keeps a running soak log as a comment thread
on the GitHub release page (or in `docs/releases/v1.0.0-rc1-soak.md`
if the team prefers). One line per event:

```
2026-MM-DD HH:MM UTC  cut       v1.0.0-rc1 pushed at <commit>
2026-MM-DD HH:MM UTC  fix       PR #N merged — <one-line summary>
2026-MM-DD HH:MM UTC  ci-green  overnight pipeline #N green
2026-MM-DD HH:MM UTC  dr-rehearsal-pass  per docs/runbooks/disaster-recovery.md §X
2026-MM-DD HH:MM UTC  promote   v1.0.0 tag pushed at <commit>
```

If a `fix/*` lands the soak clock does NOT reset (it was always
allowed). If a `revert/*` lands the soak clock DOES reset.

## What must happen during the window

The soak is more than "wait and see." Two concrete deliverables MUST
land before promotion:

1. **Two consecutive clean overnight CI runs** against the rc tag.
   "Clean" means every job green, no flake retries, no skipped
   tests beyond the documented exceptions in
   [hospital-portal/e2e/keyboard-nav.spec.ts](../../hospital-portal/e2e/keyboard-nav.spec.ts)
   and [hospital-portal/e2e/a11y.spec.ts](../../hospital-portal/e2e/a11y.spec.ts).
2. **One DR rehearsal** following
   [docs/runbooks/disaster-recovery.md](disaster-recovery.md). The
   rehearsal confirms the Railway snapshot + restore path works
   against a fresh environment and that the observability stack
   (Grafana Cloud OTel + Splunk HEC) reconnects without manual
   intervention. Pass / fail recorded in the soak log.

If either deliverable fails, the issue is filed as a `fix/*` and
the soak continues until the next attempt passes.

## Exit criteria

The release manager promotes `v1.0.0-rc1` → `v1.0.0` (GA tag) when
ALL of the following are true:

1. The 7-day window has elapsed (or the documented extension).
2. Both soak deliverables above have passed.
3. No `revert/*` is pending review.
4. No P0 / P1 production incident is open against the rc1 deploy.
5. All `fix/*` PRs that landed during the soak are themselves at
   least 24 hours old on the release branch (so they got their own
   overnight CI exposure).
6. The release manager has GPG-signed the GA tag (`git tag -s
   v1.0.0`) and the signature verifies on at least one second
   workstation.

A blocker discovered during soak that cannot be fixed within the
remaining window triggers a fresh `v1.0.0-rcN+1` cut; the soak
clock resets to day 0 of the new cut.

## Who can override

Only the release manager can:
- Extend the soak window past 7 days.
- Sign off on a `revert/*` during soak.
- Promote `v1.0.0-rc1` → `v1.0.0` when exit criteria are met.
- Issue a `v1.0.0-rcN+1` re-cut if a blocker surfaces.

Backup release manager (named in the release announcement) inherits
all of the above if the primary is unreachable for > 4 hours
during an active incident.

## Communications

- The cut is announced on the team channel + posted in the GitHub
  release.
- Each `fix/*` merged during soak is summarised in the soak log.
- The GA promotion is announced on the team channel + GitHub release
  + (for clinical pilots) the customer-success channel.
- A blocker that triggers a re-cut is announced immediately on the
  team channel and noted in the soak log.

## Archival

After GA, the soak log is preserved on the GitHub release thread
(or moved into `docs/releases/v1.0.0-rc1-soak.md` if the team
maintains historical soak records in-tree). The release manager
writes a brief postmortem in
`docs/releases/v1.0.0-postmortem.md` covering: what worked, what
needed an in-soak fix, what we'd change for v1.1.0-rc1.
