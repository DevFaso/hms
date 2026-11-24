"""What every PR reviewer shares: the instructions, the diff, the size limits,
the sticky comment and the run loop. No model SDK is imported here.

claude_review.py (Anthropic) and openai_review.py (OpenAI) each supply one
Provider; pr_review.py picks the order and falls back from one to the next.
"""
import fnmatch
import json
import os
import re
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass

# One marker for every provider, so a PR keeps ONE review comment whoever wrote it.
MARKER = "<!-- claude-review -->"
BOT = "github-actions[bot]"
# GitHub rejects comment bodies over 65,536 characters.
MAX_COMMENT_CHARS = 64_000
# Generated files: large, unreviewable, and the source change is in the diff.
GENERATED = ("package-lock.json", "*.lock", "*.min.js", "*.map")
# Separates a previous review from a failure note appended under it.
FAILURE_SEP = "\n\n---\n\n"
HEAD_RE = re.compile(r"<!-- claude-review-head: \S+ -->\n?")
# API error messages can quote a (masked) key; never put one on a public PR.
KEY_RE = re.compile(r"(?<![A-Za-z0-9])(sk|sk-ant|sk-proj)-[A-Za-z0-9_*\-]+")

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


@dataclass
class Provider:
    """One reviewer. `review(pr, meta, diff)` returns the comment body (without
    MARKER); `api_error` is the SDK's base error class."""
    name: str
    model: str
    review: Callable[[str, dict, str], str]
    api_error: type


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


def user_text(pr: str, meta: dict, files) -> str:
    """The user turn: PR title, PR body and the kept part of the diff."""
    body = "".join(c for _, c in files)
    return f"PR #{pr}: {meta['title']}\n\n{meta.get('body') or ''}\n\n<diff>\n{body}\n</diff>"


def fit(count, files, render, budget):
    """Keep whole files, in diff order, until the request fits the token budget.

    `count(render(files))` is the provider's input-token count. A file too
    large for the remaining room is skipped, not a stop: smaller files after
    it are still kept. Returns (kept_files, left_out_paths)."""
    kept = list(range(len(files)))  # indexes, so equal paths stay distinct
    for _ in range(6):
        if not kept:
            break
        tokens = count(render([files[i] for i in kept]))
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


SECRET_ENV = ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GH_TOKEN")


def redact(text: str) -> str:
    # The configured secrets themselves, whatever their shape, then anything
    # that looks like a key.
    for name in SECRET_ENV:
        value = os.environ.get(name, "").strip()
        if len(value) >= 8:
            text = text.replace(value, "[redacted key]")
    return KEY_RE.sub("[redacted key]", text)


def describe(e: Exception, api_error: type) -> str:
    if isinstance(e, api_error) and isinstance(getattr(e, "status_code", None), int):
        return redact(f"The API call failed (HTTP {e.status_code}: {e.message})")
    if isinstance(e, api_error):
        return redact(f"The API call failed ({type(e).__name__}: {e.message})")
    return redact(f"The review script failed ({type(e).__name__}: {e})")


def failure_body(failed: str, head: str, existing, label: str = "Claude") -> str:
    """The failure note, appended under a previous review instead of over it.

    The previous head marker is dropped so the next run retries; an earlier
    failure note is replaced rather than stacked."""
    note = (f"### {label} review of `{head}` failed\n\n"
            f"{failed}. Push again or re-run the workflow to retry.")
    if not existing:
        return note
    previous = HEAD_RE.sub("", existing["body"].removeprefix(MARKER)).strip("\n")
    previous = previous.split(FAILURE_SEP, 1)[0]
    if not previous or previous.split("\n", 1)[0].endswith("failed"):
        return note
    return f"{previous}{FAILURE_SEP}{note}"


def summary(line: str) -> None:
    """One line in the job summary (never a key: only names and models)."""
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(line + "\n\n")


def run(providers, label: str) -> int:
    """Review the PR with the first provider that succeeds; post one comment.

    Fails (exit 1) only when every provider failed, or none is configured."""
    pr = os.environ["PR_NUMBER"]
    meta, existing, failed, body = None, None, None, ""
    try:
        meta = json.loads(gh("pr", "view", pr, "--json", "title,body,headRefOid"))
        existing = sticky(pr)
        # Only a successful review carries the head marker, so a failure is retried.
        if existing and head_marker(meta["headRefOid"]) in existing["body"]:
            print(f"Head {meta['headRefOid'][:8]} already reviewed; skipping.")
            summary(f"Head `{meta['headRefOid'][:8]}` already reviewed; skipped.")
            return 0
        if not providers:
            failed = ("No reviewer is configured (repository secret OPENAI_API_KEY "
                      "or ANTHROPIC_API_KEY)")
        else:
            diff = git_diff()
            errors = []
            for p in providers:
                try:
                    body = p.review(pr, meta, diff)
                except Exception as e:  # any failure moves on to the next provider
                    errors.append((p.name, describe(e, p.api_error)))
                    print(f"{p.name} review failed: {errors[-1][1]}", file=sys.stderr)
                    summary(f"{p.name} (`{p.model}`) failed: {errors[-1][1]}")
                    continue
                summary(f"Reviewed by **{p.name}** (`{p.model}`)"
                        + (" after a fallback." if errors else "."))
                break
            else:
                failed = (errors[0][1] if len(errors) == 1 else
                          "Every reviewer failed (" + "; ".join(f"{n}: {m}" for n, m in errors) + ")")
    except subprocess.CalledProcessError as e:
        cmd = " ".join(e.cmd[:2]) if isinstance(e.cmd, list) else str(e.cmd)
        print(f"{cmd} failed ({e.returncode}): {e.stderr}", file=sys.stderr)
        failed = f"`{cmd}` failed with exit code {e.returncode}"
    except Exception as e:  # always leave a note, never a silent red check
        failed = redact(f"The review script failed ({type(e).__name__}: {e})")
    if failed:
        print(f"{label} review failed: {failed}", file=sys.stderr)
        summary(f"{label} review failed: {failed}")
        head = meta["headRefOid"][:8] if meta else "this head"
        body = failure_body(failed, head, existing, label)
    try:
        post(pr, body, existing)
    except subprocess.CalledProcessError as e:
        print(f"gh failed ({e.returncode}): {e.stderr}", file=sys.stderr)
        return 1
    return 1 if failed else 0
