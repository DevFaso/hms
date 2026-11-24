"""Review a pull request: OpenAI first, Claude as the fallback.

Runs in .github/workflows/claude-review.yml. A provider is used only when its
key is set (OPENAI_API_KEY, ANTHROPIC_API_KEY). The REVIEW_PROVIDER repository
variable (`openai` or `anthropic`) forces one provider with no fallback. Exits
1 only when every configured provider failed. Never prints a key.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import claude_review  # noqa: E402
import openai_review  # noqa: E402
import review_common  # noqa: E402

ORDER = ("openai", "anthropic")
KEYS = {"openai": "OPENAI_API_KEY", "anthropic": "ANTHROPIC_API_KEY"}


def selected(env=os.environ) -> list:
    """Provider ids to try, in order."""
    forced = env.get("REVIEW_PROVIDER", "").strip().lower()
    if forced and forced not in ORDER:
        print(f"REVIEW_PROVIDER={forced!r} is not one of {', '.join(ORDER)}; ignoring it.",
              file=sys.stderr)
        review_common.summary(f"REVIEW_PROVIDER `{forced}` ignored (not one of {', '.join(ORDER)}).")
        forced = ""
    candidates = (forced,) if forced else ORDER
    return [p for p in candidates if env.get(KEYS[p], "").strip()]


def main(factories=None) -> int:
    """`factories` maps provider id to a client factory (tests inject fakes)."""
    factories = factories or {}
    build = {"openai": openai_review.provider, "anthropic": claude_review.provider}
    providers = [build[p](factories[p]) if p in factories else build[p]()
                 for p in selected()]
    label = providers[0].name if len(providers) == 1 else "PR"
    return review_common.run(providers, label)


if __name__ == "__main__":
    sys.exit(main())
