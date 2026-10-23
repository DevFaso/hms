"""Review a pull request diff with Claude and post one sticky PR comment.

Runs in .github/workflows/claude-review.yml. Calls the Messages API directly
(the Console's promotional credit covers the API, not Claude Code). Reads the
diff and PR metadata with gh; never prints secrets.
"""
import json
import os
import subprocess
import sys

import anthropic

MARKER = "<!-- claude-review -->"
MODEL = "claude-opus-5-5"
# ~1M-token context; leave room for the prompt and the answer.
MAX_DIFF_CHARS = 2_000_000

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


def post(pr: str, body: str) -> None:
    body = f"{MARKER}\n{body}"
    comments = json.loads(gh("api", f"repos/{os.environ['GITHUB_REPOSITORY']}/issues/{pr}/comments", "--paginate"))
    mine = [c for c in comments if c["body"].startswith(MARKER)]
    if mine:
        gh("api", "-X", "PATCH", f"repos/{os.environ['GITHUB_REPOSITORY']}/issues/comments/{mine[-1]['id']}", "-f", f"body={body}")
    else:
        gh("pr", "comment", pr, "--body", body)


def main() -> int:
    pr = os.environ["PR_NUMBER"]
    meta = json.loads(gh("pr", "view", pr, "--json", "title,body,headRefOid"))
    diff = gh("pr", "diff", pr)
    note = ""
    if len(diff) > MAX_DIFF_CHARS:
        note = (f"\n\n> Note: the diff is {len(diff):,} characters; only the first "
                f"{MAX_DIFF_CHARS:,} were reviewed. Split the PR for a full review.")
        diff = diff[:MAX_DIFF_CHARS]

    client = anthropic.Anthropic(
        default_headers={"anthropic-workspace-id": os.environ["ANTHROPIC_WORKSPACE_ID"]}
    )
    try:
        response = client.beta.messages.create(
            model=MODEL,
            max_tokens=16000,
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            output_config={"effort": "high"},
            system=PROMPT,
            messages=[{
                "role": "user",
                "content": f"PR #{pr}: {meta['title']}\n\n{meta.get('body') or ''}\n\n<diff>\n{diff}\n</diff>",
            }],
        )
    except anthropic.APIStatusError as e:
        print(f"Claude review failed: HTTP {e.status_code}: {e.message}", file=sys.stderr)
        return 1

    if response.stop_reason == "refusal":
        text = "Claude declined to review this diff."
    else:
        text = "".join(b.text for b in response.content if b.type == "text").strip()
        if response.stop_reason == "max_tokens":
            text += "\n\n> Note: the review hit its length limit and may be incomplete."
    head = meta["headRefOid"][:8]
    post(pr, f"### Claude review of `{head}`\n\n{text}{note}\n\n<sub>{response.model}, "
             f"{response.usage.input_tokens:,} in / {response.usage.output_tokens:,} out tokens</sub>")
    return 0


if __name__ == "__main__":
    sys.exit(main())
