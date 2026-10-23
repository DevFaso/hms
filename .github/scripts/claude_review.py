"""Review a pull request diff with Claude and post one sticky PR comment.

Runs in .github/workflows/claude-review.yml. Calls the Messages API directly
(the Console's promotional credit covers the API, not Claude Code). Builds the
diff from the local checkout, reads PR metadata with gh; never prints secrets.
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
# Separates a previous review from a failure note appended under it.
FAILURE_SEP = "\n\n---\n\n"
HEAD_RE = re.compile(r"<!-- claude-review-head: \S+ -->\n?")

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


def gh(*args: str, stdin=None) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True,
                          encoding="utf-8", input=stdin).stdout


def git_diff() -> str:
    """The PR diff, built from the local checkout (fetch-depth: 0).

    `gh pr diff` uses GitHub's diff endpoint, which answers 406 above about
    20,000 lines, so large PRs would never reach the token-budget trimming."""
    base = os.environ["BASE_REF"]
    return subprocess.run(["git", "diff", f"origin/{base}...HEAD"], check=True,
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace").stdout


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
    # On stdin: one argv entry is capped at 128 KiB on Linux.
    if existing:
        repo = os.environ["GITHUB_REPOSITORY"]
        gh("api", "-X", "PATCH", f"repos/{repo}/issues/comments/{existing['id']}",
           "-F", "body=@-", stdin=body)
    else:
        gh("pr", "comment", pr, "--body-file", "-", stdin=body)


def file_path(chunk: str) -> str:
    """The new path from the '+++ b/' line (the old one for a deletion), so
    paths with spaces survive; the 'diff --git' line when there is none."""
    old = ""
    for line in chunk.splitlines():
        if line.startswith("@@"):
            break
        if line.startswith("--- a/"):
            old = line[6:].rstrip("\t")
        elif line.startswith("+++ b/"):
            return line[6:].rstrip("\t")
        elif line.startswith("+++ /dev/null"):
            return old
    first = chunk.split("\n", 1)[0]
    return first.rsplit(" b/", 1)[1] if " b/" in first else first


def split_files(diff: str):
    """[(path, chunk)] in diff order."""
    return [(file_path(chunk), chunk)
            for chunk in re.split(r"(?m)^(?=diff --git )", diff) if chunk]


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

    A file too large for the remaining room is skipped, not a stop: smaller
    files after it are still kept. Returns (kept_files, left_out_paths)."""
    kept = list(range(len(files)))  # indexes, so equal paths stay distinct
    for _ in range(6):
        if not kept:
            break
        tokens = client.messages.count_tokens(
            model=MODEL, system=PROMPT, messages=render([files[i] for i in kept])).input_tokens
        if tokens <= budget:
            break
        size = sum(len(files[i][1]) for i in kept)
        target = int(size * budget / tokens * 0.95)
        acc, cut = 0, []
        for i in kept:
            if acc + len(files[i][1]) > target:
                continue
            acc += len(files[i][1])
            cut.append(i)
        kept = cut if len(cut) < len(kept) else kept[:-1]
    keep = set(kept)
    return ([files[i] for i in kept],
            [p for i, (p, _) in enumerate(files) if i not in keep])


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


def failure_body(failed: str, head: str, existing) -> str:
    """The failure note, appended under a previous review instead of over it.

    The previous head marker is dropped so the next run retries; an earlier
    failure note is replaced rather than stacked."""
    note = (f"### Claude review of `{head}` failed\n\n"
            f"{failed}. Push again or re-run the workflow to retry.")
    if not existing:
        return note
    previous = HEAD_RE.sub("", existing["body"].removeprefix(MARKER)).strip("\n")
    previous = previous.split(FAILURE_SEP, 1)[0]
    if not previous or previous.split("\n", 1)[0].endswith("failed"):
        return note
    return f"{previous}{FAILURE_SEP}{note}"


def default_client():
    return anthropic.Anthropic(
        default_headers={"anthropic-workspace-id": os.environ["ANTHROPIC_WORKSPACE_ID"]})


def main(client_factory=default_client) -> int:
    pr = os.environ["PR_NUMBER"]
    meta, existing, failed, body = None, None, None, ""
    try:
        meta = json.loads(gh("pr", "view", pr, "--json", "title,body,headRefOid"))
        existing = sticky(pr)
        # Only a successful review carries the head marker, so a failure is retried.
        if existing and head_marker(meta["headRefOid"]) in existing["body"]:
            print(f"Head {meta['headRefOid'][:8]} already reviewed; skipping.")
            return 0
        body = review(client_factory(), pr, meta, git_diff())
    except anthropic.APIStatusError as e:
        failed = f"The API call failed (HTTP {e.status_code}: {e.message})"
    except anthropic.APIError as e:
        failed = f"The API call failed ({type(e).__name__}: {e.message})"
    except subprocess.CalledProcessError as e:
        cmd = " ".join(e.cmd[:2]) if isinstance(e.cmd, list) else str(e.cmd)
        print(f"{cmd} failed ({e.returncode}): {e.stderr}", file=sys.stderr)
        failed = f"`{cmd}` failed with exit code {e.returncode}"
    except Exception as e:  # always leave a note, never a silent red check
        failed = f"The review script failed ({type(e).__name__}: {e})"
    if failed:
        print(f"Claude review failed: {failed}", file=sys.stderr)
        head = meta["headRefOid"][:8] if meta else "this head"
        body = failure_body(failed, head, existing)
    try:
        post(pr, body, existing)
    except subprocess.CalledProcessError as e:
        print(f"gh failed ({e.returncode}): {e.stderr}", file=sys.stderr)
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
