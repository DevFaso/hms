"""Review a pull request diff with Claude and post one sticky PR comment.

Runs in .github/workflows/claude-review.yml. Calls the Messages API directly
(the Console's promotional credit covers the API, not Claude Code). Reads the
diff and PR metadata with gh; never prints secrets.
"""
import fnmatch
import json
import os
import re
import subprocess
import sys

import anthropic

MARKER = "<!-- claude-review -->"
BOT = "github-actions[bot]"
MODEL = "claude-opus-5-5"
# ~1M-token context; leave room for the prompt, the PR body and the answer.
TOKEN_BUDGET = 600_000
# GitHub rejects comment bodies over 65,536 characters.
MAX_COMMENT_CHARS = 64_000
# Generated files: large, unreviewable, and the source change is in the diff.
GENERATED = ("package-lock.json", "*.lock", "*.min.js", "*.map")

PROMPT = """You review pull requests for e-Keneya, a multi-hospital health system:
Spring Boot 4 backend in hospital-core, Angular portal in hospital-portal, EN/FR/ES.

Report only real problems you can see in this diff, most severe first. For each:
file:line, what is wrong, why it matters, and the concrete fix. Look for:
- tenancy: every read or write of patient data must be scoped to the acting
  hospital; a caller-supplied hospitalId, staffId, providerId or signer is a
  claim, never an identity;
- authorization: @PreAuthorize roles match what the portal shows;
- correctness and regressions on other paths the changed code serves;
- a Liquibase SQL file not registered in db/migration/changelog.xml, or an
  edit to an already applied migration;
- user-facing text not translated in EN, FR and ES;
- patient data (names, MRN, phone, email, clinical text) written to logs;
- missing tests that would fail without the change.

Skip style the linters enforce. Say when something depends on code outside the
diff that you cannot see. If nothing holds, say so in one line. Answer in
English, in GitHub Markdown."""


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def head_marker(sha: str) -> str:
    return f"<!-- claude-review-head: {sha} -->"


def sticky(pr: str):
    """This workflow's own latest comment, or None."""
    repo = os.environ["GITHUB_REPOSITORY"]
    # --slurp wraps every page in one array, so more than 30 comments still parse.
    pages = json.loads(gh("api", f"repos/{repo}/issues/{pr}/comments", "--paginate", "--slurp"))
    # Only this workflow's own comment: anyone can type the marker on a public repo.
    mine = [c for page in pages for c in page
            if c["user"]["login"] == BOT and c["body"].startswith(MARKER)]
    return mine[-1] if mine else None


def truncate(body: str) -> str:
    if len(body) <= MAX_COMMENT_CHARS:
        return body
    note = "\n\n> Note: the review was cut to fit GitHub's comment size limit."
    return body[:MAX_COMMENT_CHARS - len(note)] + note


def post(pr: str, body: str, existing=None) -> None:
    body = truncate(f"{MARKER}\n{body}")
    if existing:
        repo = os.environ["GITHUB_REPOSITORY"]
        gh("api", "-X", "PATCH", f"repos/{repo}/issues/comments/{existing['id']}", "-f", f"body={body}")
    else:
        gh("pr", "comment", pr, "--body", body)


def split_files(diff: str):
    """[(path, chunk)] in diff order."""
    out = []
    for chunk in re.split(r"(?m)^(?=diff --git )", diff):
        if not chunk:
            continue
        m = re.match(r"diff --git a/(\S+) b/(\S+)", chunk)
        out.append((m.group(2) if m else "", chunk))
    return out


def is_generated(path: str) -> bool:
    name = path.rsplit("/", 1)[-1]
    return any(fnmatch.fnmatch(name, pat) for pat in GENERATED)


def filter_generated(diff: str):
    kept, dropped = [], []
    for path, chunk in split_files(diff):
        (dropped if is_generated(path) else kept).append((path, chunk))
    return kept, [p for p, _ in dropped]


def fit(client, files, render, budget=TOKEN_BUDGET):
    """Keep whole files, in diff order, until the request fits the token budget.

    Returns (kept_files, left_out_paths)."""
    kept = list(files)
    for _ in range(6):
        tokens = client.messages.count_tokens(
            model=MODEL, system=PROMPT, messages=render(kept)).input_tokens
        if tokens <= budget or not kept:
            break
        size = sum(len(c) for _, c in kept)
        target = int(size * budget / tokens * 0.95)
        acc, cut = 0, []
        for path, chunk in kept:
            if acc + len(chunk) > target:
                break
            acc += len(chunk)
            cut.append((path, chunk))
        kept = cut if len(cut) < len(kept) else kept[:-1]
    kept_paths = {p for p, _ in kept}
    return kept, [p for p, _ in files if p not in kept_paths]


def left_out_note(generated, over_budget) -> str:
    lines = []
    if generated:
        lines.append("generated files not reviewed: " + ", ".join(f"`{p}`" for p in generated))
    if over_budget:
        lines.append(f"{len(over_budget)} file(s) over the token budget not reviewed: "
                     + ", ".join(f"`{p}`" for p in over_budget[:50])
                     + (" ..." if len(over_budget) > 50 else "")
                     + ". Split the PR for a full review.")
    return "".join(f"\n\n> Note: {line}" for line in lines)


def review(client, pr, meta, diff) -> str:
    files, generated = filter_generated(diff)

    def render(fs):
        body = "".join(c for _, c in fs)
        return [{"role": "user",
                 "content": f"PR #{pr}: {meta['title']}\n\n{meta.get('body') or ''}\n\n<diff>\n{body}\n</diff>"}]

    kept, over = fit(client, files, render)
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
    sha = meta["headRefOid"]
    return (f"{head_marker(sha)}\n### Claude review of `{sha[:8]}`\n\n{text}"
            f"{left_out_note(generated, over)}\n\n<sub>{response.model}, "
            f"{response.usage.input_tokens:,} in / {response.usage.output_tokens:,} out tokens</sub>")


def default_client():
    return anthropic.Anthropic(
        default_headers={"anthropic-workspace-id": os.environ["ANTHROPIC_WORKSPACE_ID"]})


def main(client_factory=default_client) -> int:
    pr = os.environ["PR_NUMBER"]
    try:
        meta = json.loads(gh("pr", "view", pr, "--json", "title,body,headRefOid"))
        existing = sticky(pr)
        # Only a successful review carries the head marker, so a failure is retried.
        if existing and head_marker(meta["headRefOid"]) in existing["body"]:
            print(f"Head {meta['headRefOid'][:8]} already reviewed; skipping.")
            return 0
        diff = gh("pr", "diff", pr)
    except subprocess.CalledProcessError as e:
        print(f"gh failed ({e.returncode}): {e.stderr}", file=sys.stderr)
        return 1

    failed = None
    try:
        body = review(client_factory(), pr, meta, diff)
    except anthropic.APIStatusError as e:
        failed = f"HTTP {e.status_code}: {e.message}"
    except anthropic.APIError as e:
        failed = f"{type(e).__name__}: {e.message}"
    if failed:
        print(f"Claude review failed: {failed}", file=sys.stderr)
        body = (f"### Claude review of `{meta['headRefOid'][:8]}` failed\n\n"
                f"The API call failed ({failed}). Push again or re-run the workflow to retry.")
    try:
        post(pr, body, existing)
    except subprocess.CalledProcessError as e:
        print(f"gh failed ({e.returncode}): {e.stderr}", file=sys.stderr)
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
