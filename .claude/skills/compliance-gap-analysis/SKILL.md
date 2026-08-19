---
name: compliance-gap-analysis
description: Use when adding or updating SOC 2 (CC1–CC9) or HIPAA Security/Privacy Rule gap analyses, the companion controls CSV, BAA template, PHI inventory, or any new control surface under docs/compliance/. The pattern is shared across both regimes — control inventory + companion CSV + remediation backlog + critical path.
---

# Compliance gap analyses (SOC 2 + HIPAA)

`docs/compliance/` carries the codebase's compliance posture as living
documents — every claim cites a real file path. The pattern is shared:
both SOC 2 (row 37) and HIPAA (row 38) follow the same shape.

## Artefacts per regime

For each compliance regime, four files:

- `<regime>-gap.md` — full control inventory grounded in code-level
  survey. Every "Present" rating cites a real file path. Includes a
  scorecard table (rows × Present / Partial / Gap / Total / Weighted %)
  + a "Headline" call-out + a remediation backlog (P0 / P1 / P2 with
  target dates) + a critical path section.
- `<regime>-controls.csv` — machine-readable matrix. Columns:
  `ref, family/criterion, status, owner, priority, effort,
  evidence_or_action, target_close`. One row per control point.
- `<regime>-baa-template.md` (HIPAA only) / equivalent execution
  artefact (SOC 2: customer-facing trust report draft).
- `<regime>-supporting-inventory.md` (e.g.
  `phi-inventory.md` for HIPAA) — operational reference cited by the
  gap doc.

## Status vocabulary (only three values)

| Value | When to use |
| --- | --- |
| `present` | Control fully implemented + cited in code/runbook + tested. Cite the file path. |
| `partial` | Control mechanism exists but coverage is incomplete OR the documentation is missing. Always pair with an `Action:` line in the body. |
| `gap` | Control absent entirely. Pair with an `Action:` line + a remediation backlog item. |

`N/A` is allowed for controls that don't apply to HMS's deployment
model (e.g. HIPAA §164.310(b) Workstation Use — customer-side
responsibility).

## Scorecard math

`Weighted %` = `(present + 0.5 × partial) / total × 100`, rounded to
the nearest integer. The same formula across families and total. When
flipping a control rating, **re-run the math** — Sonar / Copilot reviews
have caught inconsistent totals twice already.

## Remediation backlog discipline

P0 / P1 / P2 lists must match the headline counts. E.g.:

> **P0 — must close before <gate>** (10 items, target close **<date>**):

Then list 10 items. If you add an item, bump the count. If you remove
a duplicate (e.g. the Sanction-policy duplicate that landed across both
P0 and P1 on the HIPAA PR), fix the count in the same edit.

Each backlog item maps to a row in the companion CSV. When you flip a
control from `gap → partial → present`, update the CSV's `status` +
`evidence_or_action` columns and remove (or note "closed YYYY-MM-DD")
the backlog entry.

## Citing code in `<regime>-gap.md`

Every "Present" or "Partial" rating MUST include a markdown link to a
real file:

```markdown
**§164.312(a)(2)(iv) Encryption + Decryption** — `Partial`. AES-256-GCM
field-level encryption is operational via
[`hospital-core/.../EncryptedStringConverter.java`](../../hospital-core/src/main/java/com/example/hms/security/EncryptedStringConverter.java),
key supplied via `APP_ENCRYPTION_KEY` ...
```

When the code path doesn't yet exist (e.g. a planned runbook), mark
the rating `partial` or `gap`, NOT `present`, and add an Action line.
**Never overstate coverage** — the HIPAA Copilot review caught this in
several places ("plaintext today" needed to replace "Yes (gcm1)" on
the PHI inventory).

## BAA template caveats

`hipaa-baa-template.md` is an execution template. Bullets that promise
controls which are currently `gap` or `partial` MUST be marked
`*(Pending — P0/P1, target YYYY-MM-DD)*`. Include an attestation
paragraph explaining that signature without revised pending markers is
the BA's confirmation that the artefacts have actually landed.

The BAA references runbooks (e.g.
`docs/runbooks/key-rotation.md`). If the runbook doesn't exist yet,
mark the clause pending with a refusal-to-sign-until-resolved escape
hatch — never reference a file the BA can't produce.

## Critical path calibration

The HIPAA P0 target (2026-09-30) is intentionally **one month after**
the SOC 2 P0 target (2026-08-15). The ~40 % overlap between the two
backlogs (PHI inventory, key rotation, access-review cadence,
incident-response runbook) is absorbed in series, not parallel. When
extending either critical path, preserve this offset so a single
HR/compliance push closes both backlogs in succession.

## Reference files

- `docs/compliance/soc2-gap.md`
- `docs/compliance/soc2-controls.csv`
- `docs/compliance/hipaa-gap.md`
- `docs/compliance/hipaa-controls.csv`
- `docs/compliance/hipaa-baa-template.md`
- `docs/compliance/phi-inventory.md`

## When NOT to update a compliance doc

- Routine code refactors that don't change a cited control's
  implementation surface.
- Test additions that don't add a new evidenced control.
- PRs whose only effect is `roadmap.csv` status flips.

The doc captures **what is true today**. Update it when reality
changes, not on every commit.
