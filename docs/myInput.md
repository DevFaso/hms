# Agent Workflow Instructions

## Process

1. **Follow the Agent Instructions** — create scenarios as user stories
2. **Clarify the Issue** — understand scope and impact
3. **Plan the Implementation** — design the solution across all layers
4. **Create & Follow Tasklist** — update progress as you go

## Testing & Quality

- Write `spec.ts` for all frontend changes
- Write JUnit tests for all backend changes

## Build Pipeline (run in order)

1. Build & test
2. Format
3. Test
4. Lint
5. JaCoCo
6. Commit and push

---

# Issues

## Grant Consent Form

The **Grant Consent** dialog has the following fields:

| Field           | Input Type | Required | Notes                                                                  |
|-----------------|------------|----------|------------------------------------------------------------------------|
| Patient ID      | UUID       | Yes      | Text input — "Enter UUID"                                              |
| From Hospital   | UUID       | Yes      | Text input — "Enter UUID"                                              |
| To Hospital     | UUID       | Yes      | Text input — "Enter UUID"                                              |
| Consent Type    | Select     | No       | e.g. `Treatment`                                                       |
| Purpose         | Text       | No       | Free-text description                                                  |
| Scope           | Text       | No       | Comma-separated domains (e.g. `PRESCRIPTIONS,LAB_RESULTS`). Blank = all |
| Expires         | Date/Time  | No       | Format: `mm/dd/yyyy hh:mm AM/PM`                                      |

**Actions:** `Cancel` · `Grant Consent`