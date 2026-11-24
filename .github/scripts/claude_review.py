"""Review a pull request diff with Claude and post one sticky PR comment.

Runs in .github/workflows/claude-review.yml. Calls the Messages API directly
(the Console's promotional credit covers the API, not Claude Code). Builds the
diff from the local checkout, reads PR metadata with gh; never prints secrets.

Since the OpenAI reviewer became primary, pr_review.py calls this as the
fallback; run on its own it still reviews with Claude alone, as before.
"""
import os
import sys
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).parent))

# Shared with openai_review.py; re-exported so callers and tests keep one name.
from review_common import (  # noqa: E402,F401
    BOT, FAILURE_SEP, GENERATED, HEAD_RE, MARKER, MAX_COMMENT_CHARS, PROMPT, Provider,
    failure_body, file_path, filter_generated, head_marker, is_generated, left_out_note,
    post, split_files, sticky, truncate, user_text)
import review_common  # noqa: E402

MODEL = "claude-opus-5-5"
# ~1M-token context; leave room for the prompt, the PR body and the answer.
TOKEN_BUDGET = 600_000


def fit(client, files, render, budget=TOKEN_BUDGET):
    """review_common.fit with Claude's token counter."""
    def count(messages):
        return client.messages.count_tokens(
            model=MODEL, system=PROMPT, messages=messages).input_tokens
    return review_common.fit(count, files, render, budget)


def review(client, pr, meta, diff) -> str:
    files, generated = filter_generated(diff)

    def render(fs):
        return [{"role": "user", "content": user_text(pr, meta, fs)}]

    kept, over = fit(client, files, render)
    sha = meta["headRefOid"]
    title = f"{head_marker(sha)}\n### Claude review of `{sha[:8]}`\n\n"
    if files and not kept:
        return (f"{title}Diff too large to review: no file fits the token budget."
                f"{left_out_note(generated, over)}")
    response = client.beta.messages.create(
        model=MODEL,
        max_tokens=16000,
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
        output_config={"effort": "high"},
        system=PROMPT,
        messages=render(kept),
    )
    if response.stop_reason == "refusal":
        text = "Claude declined to review this diff."
    else:
        text = "".join(b.text for b in response.content if b.type == "text").strip()
        if response.stop_reason == "max_tokens":
            text += "\n\n> Note: the review hit its length limit and may be incomplete."
    return (f"{title}{text}"
            f"{left_out_note(generated, over)}\n\n<sub>{response.model}, "
            f"{response.usage.input_tokens:,} in / {response.usage.output_tokens:,} out tokens</sub>")


def default_client():
    return anthropic.Anthropic(
        default_headers={"anthropic-workspace-id": os.environ["ANTHROPIC_WORKSPACE_ID"]})


def provider(client_factory=default_client) -> Provider:
    return Provider("Claude", MODEL,
                    lambda pr, meta, diff: review(client_factory(), pr, meta, diff),
                    anthropic.APIError)


def main(client_factory=default_client) -> int:
    return review_common.run([provider(client_factory)], "Claude")


if __name__ == "__main__":
    sys.exit(main())
