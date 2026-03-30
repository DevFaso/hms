# Promptfoo Scenario Tests

Place `.prompt.yaml` files here to test agent behavior against defined scenarios.

These tests are run in CI via the `agent-lint.yml` workflow.

## Example scenario file

```yaml
prompts:
  - "Given a patient record update request, the agent should..."

providers:
  - id: openai:gpt-4
    config:
      temperature: 0

tests:
  - vars:
      input: "Update patient blood type to O+"
    assert:
      - type: contains
        value: "validation"
      - type: not-contains
        value: "DELETE"
```
