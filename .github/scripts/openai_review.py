"""Review a pull request diff with an OpenAI model and post one sticky PR comment.

The primary reviewer in .github/workflows/claude-review.yml (pr_review.py
falls back to claude_review.py when this fails). Same instructions, diff,
size limits and sticky comment as the Claude reviewer (review_common.py).
Calls the Responses API with store=False; never prints secrets.
"""
import os
import sys
from pathlib import Path

import openai

sys.path.insert(0, str(Path(__file__).parent))

from review_common import (  # noqa: E402
    PROMPT, Provider, filter_generated, head_marker, left_out_note, user_text)
import review_common  # noqa: E402

# Override with the OPENAI_REVIEW_MODEL repository variable. gpt-6.2-astra is
# OpenAI's flagship for complex reasoning and coding (developers.openai.com/api/docs/models).
DEFAULT_MODEL = "gpt-6.2-astra"
# 1,050,000-token context; leave room for the prompt, the PR body and the answer.
TOKEN_BUDGET = 600_000
# Counts reasoning tokens too, so well above the Claude reviewer's 16,000.
MAX_OUTPUT_TOKENS = 64_000


def model() -> str:
    return os.environ.get("OPENAI_REVIEW_MODEL", "").strip() or DEFAULT_MODEL


def fit(client, files, render, budget=TOKEN_BUDGET):
    """review_common.fit with OpenAI's input-token counter."""
    def count(text):
        return client.responses.input_tokens.count(
            model=model(), instructions=PROMPT, input=text).input_tokens
    return review_common.fit(count, files, render, budget)


def refusal(response) -> str:
    for item in response.output:
        if item.type == "message":
            for part in item.content:
                if part.type == "refusal":
                    return part.refusal
    return ""


def review(client, pr, meta, diff) -> str:
    files, generated = filter_generated(diff)

    def render(fs):
        return user_text(pr, meta, fs)

    kept, over = fit(client, files, render)
    sha = meta["headRefOid"]
    title = f"{head_marker(sha)}\n### OpenAI `{model()}` review of `{sha[:8]}`\n\n"
    if files and not kept:
        return (f"{title}Diff too large to review: no file fits the token budget."
                f"{left_out_note(generated, over)}")
    response = client.responses.create(
        model=model(),
        instructions=PROMPT,
        input=render(kept),
        max_output_tokens=MAX_OUTPUT_TOKENS,
        reasoning={"effort": "high"},
        # A public repo's diff, but nothing needs keeping on OpenAI's side.
        store=False,
    )
    text = response.output_text.strip()
    if not text and refusal(response):
        text = "The model declined to review this diff."
    elif not text:
        # An empty review is a failure, so pr_review.py falls back to Claude.
        reason = getattr(response.incomplete_details, "reason", None) or response.status
        raise RuntimeError(f"empty response ({reason})")
    elif response.status == "incomplete":
        text += "\n\n> Note: the review hit its length limit and may be incomplete."
    usage = response.usage
    tokens = (f", {usage.input_tokens:,} in / {usage.output_tokens:,} out tokens"
              if usage else "")
    return (f"{title}{text}"
            f"{left_out_note(generated, over)}\n\n<sub>{response.model}{tokens}</sub>")


def default_client():
    # Reads OPENAI_API_KEY from the environment.
    return openai.OpenAI()


def provider(client_factory=default_client) -> Provider:
    return Provider("OpenAI", model(),
                    lambda pr, meta, diff: review(client_factory(), pr, meta, diff),
                    openai.APIError)


def main(client_factory=default_client) -> int:
    return review_common.run([provider(client_factory)], "OpenAI")


if __name__ == "__main__":
    sys.exit(main())
