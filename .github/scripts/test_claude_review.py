"""Tests for claude_review.py against the real pinned SDK; gh, git and the
network are stubbed.

Run: pip install -r .github/scripts/requirements-dev.txt
     python -m pytest .github/scripts
"""
import json
import subprocess
import sys
import types
from pathlib import Path

import anthropic
import httpx2
import pytest

sys.path.insert(0, str(Path(__file__).parent))

import claude_review as cr  # noqa: E402

SHA = "abcdef1234567890"


def comment(cid, login, body):
    return {"id": cid, "user": {"login": login}, "body": body}


class FakeGh:
    def __init__(self, pages, diff="", head=SHA):
        self.pages, self.diff, self.head, self.calls = pages, diff, head, []

    def __call__(self, *args, stdin=None):
        self.calls.append(args + ((stdin,) if stdin is not None else ()))
        if args[:2] == ("pr", "view"):
            return json.dumps({"title": "T", "body": "B", "headRefOid": self.head})
        if args[0] == "api" and "--slurp" in args:
            return json.dumps(self.pages)
        return ""

    def patched(self):
        return [a for a in self.calls if a[:3] == ("api", "-X", "PATCH")]

    def commented(self):
        return [a for a in self.calls if a[:2] == ("pr", "comment")]


class FakeClient:
    def __init__(self, tokens_per_char=1.0, error=None):
        self.tokens_per_char, self.error, self.sent = tokens_per_char, error, None
        client = self

        class Messages:
            def count_tokens(self, model, system, messages):
                n = int(len(messages[0]["content"]) * client.tokens_per_char)
                return types.SimpleNamespace(input_tokens=n)

        class BetaMessages:
            def create(self, **kw):
                if client.error:
                    raise client.error
                client.kwargs = kw
                client.sent = kw["messages"][0]["content"]
                return types.SimpleNamespace(
                    stop_reason="end_turn", model="m",
                    content=[types.SimpleNamespace(type="text", text="LGTM")],
                    usage=types.SimpleNamespace(input_tokens=1, output_tokens=1))

        self.messages = Messages()
        self.beta = types.SimpleNamespace(messages=BetaMessages())


@pytest.fixture(autouse=True)
def env(monkeypatch):
    monkeypatch.setenv("GITHUB_REPOSITORY", "o/r")
    monkeypatch.setenv("PR_NUMBER", "7")


def install(monkeypatch, fake):
    monkeypatch.setattr(cr, "gh", fake)
    monkeypatch.setattr(cr, "git_diff", lambda: fake.diff)
    return fake


def test_slurped_pages_are_flattened(monkeypatch):
    install(monkeypatch, FakeGh([[comment(1, "x", "hi")], [comment(2, cr.BOT, cr.MARKER + " a")]]))
    assert cr.sticky("7")["id"] == 2


def test_lookalike_marker_from_another_user_is_ignored(monkeypatch):
    fake = install(monkeypatch, FakeGh([[comment(1, "mallory", cr.MARKER + " fake")]]))
    assert cr.sticky("7") is None
    cr.post("7", "body", None)
    assert fake.commented()
    assert not fake.patched()


def test_last_bot_comment_is_patched(monkeypatch):
    pages = [[comment(1, cr.BOT, cr.MARKER + " old")], [comment(2, cr.BOT, cr.MARKER + " newer")]]
    fake = install(monkeypatch, FakeGh(pages, diff="diff --git a/x b/x\n+1\n"))
    assert cr.main(lambda: FakeClient()) == 0
    patched = fake.patched()
    assert len(patched) == 1
    assert patched[0][3].endswith("/comments/2")


def test_head_already_reviewed_is_skipped(monkeypatch):
    pages = [[comment(1, cr.BOT, cr.MARKER + "\n" + cr.head_marker(SHA) + "\nok")]]
    fake = install(monkeypatch, FakeGh(pages))
    client = FakeClient()
    assert cr.main(lambda: client) == 0
    assert client.sent is None
    assert not fake.patched()
    assert not fake.commented()


def test_api_failure_posts_a_note(monkeypatch):
    fake = install(monkeypatch, FakeGh([], diff="diff --git a/x b/x\n"))
    error = anthropic.APIConnectionError(
        request=httpx2.Request("POST", "https://api.anthropic.com/v1/messages"))
    assert cr.main(lambda: FakeClient(error=error)) == 1
    body = fake.commented()[0][-1]
    assert "APIConnectionError" in body
    assert cr.head_marker(SHA) not in body


def test_unexpected_error_still_posts_a_note(monkeypatch):
    fake = install(monkeypatch, FakeGh([], diff="diff --git a/x b/x\n"))
    assert cr.main(lambda: FakeClient(error=TypeError("bad kwarg"))) == 1
    assert "TypeError: bad kwarg" in fake.commented()[0][-1]


def test_git_failure_posts_a_note(monkeypatch):
    fake = install(monkeypatch, FakeGh([]))

    def boom():
        raise subprocess.CalledProcessError(128, ["git", "diff", "origin/x...HEAD"], stderr="bad")
    monkeypatch.setattr(cr, "git_diff", boom)
    assert cr.main(lambda: FakeClient()) == 1
    assert "`git diff` failed with exit code 128" in fake.commented()[0][-1]


def test_failure_is_appended_under_a_previous_review(monkeypatch):
    old = cr.MARKER + "\n" + cr.head_marker("0ld") + "\n### Claude review of `0ld`\n\nfinding 1"
    fake = install(monkeypatch, FakeGh([[comment(5, cr.BOT, old)]], diff="diff --git a/x b/x\n"))
    assert cr.main(lambda: FakeClient(error=TypeError("x"))) == 1
    body = fake.patched()[0][-1]
    assert "finding 1" in body
    assert "failed" in body
    assert "claude-review-head" not in body
    # a second failure replaces the note instead of stacking it
    fake.pages = [[comment(5, cr.BOT, body)]]
    cr.main(lambda: FakeClient(error=TypeError("y")))
    second = fake.patched()[-1][-1]
    assert second.count("---") == 1
    assert "TypeError: x" not in second
    assert "finding 1" in second


def test_create_receives_exactly_these_kwargs(monkeypatch):
    install(monkeypatch, FakeGh([], diff="diff --git a/x b/x\n+1\n"))
    client = FakeClient()
    assert cr.main(lambda: client) == 0
    assert set(client.kwargs) == {"model", "max_tokens", "betas", "fallbacks",
                                  "output_config", "system", "messages"}
    assert client.kwargs["model"] == cr.MODEL
    assert client.kwargs["max_tokens"] == 16000
    assert client.kwargs["betas"] == ["server-side-fallback-2026-07-01"]
    assert client.kwargs["fallbacks"] == "default"
    assert client.kwargs["output_config"] == {"effort": "high"}
    assert client.kwargs["system"] == cr.PROMPT
    # the real SDK accepts every one of them
    import inspect
    from anthropic.resources.beta.messages import Messages
    assert set(client.kwargs) <= set(inspect.signature(Messages.create).parameters)


def test_body_goes_on_stdin(monkeypatch):
    fake = install(monkeypatch, FakeGh([]))
    cr.post("7", "é" * 100_000, None)
    args = fake.commented()[0]
    assert args[:5] == ("pr", "comment", "7", "--body-file", "-")
    assert len(args[-1]) <= cr.MAX_COMMENT_CHARS


def test_paths_with_spaces_are_parsed():
    diff = ("diff --git a/my dir/a b.txt b/my dir/a b.txt\n--- a/my dir/a b.txt\n"
            "+++ b/my dir/a b.txt\n@@ -1 +1 @@\n+x\n"
            "diff --git a/gone file.txt b/gone file.txt\ndeleted file mode 100644\n"
            "--- a/gone file.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n")
    assert [p for p, _ in cr.split_files(diff)] == ["my dir/a b.txt", "gone file.txt"]


def test_body_is_truncated():
    out = cr.truncate("x" * 100_000)
    assert len(out) <= cr.MAX_COMMENT_CHARS
    assert out.endswith("comment size limit.")
    assert cr.truncate("short") == "short"


def test_generated_files_are_dropped():
    diff = ("diff --git a/src/A.java b/src/A.java\n+a\n"
            "diff --git a/p/package-lock.json b/p/package-lock.json\n+x\n"
            "diff --git a/yarn.lock b/yarn.lock\n+y\n"
            "diff --git a/d/app.min.js b/d/app.min.js\n+z\n"
            "diff --git a/d/app.js.map b/d/app.js.map\n+m\n")
    kept, dropped = cr.filter_generated(diff)
    assert [p for p, _ in kept] == ["src/A.java"]
    assert dropped == ["p/package-lock.json", "yarn.lock", "d/app.min.js", "d/app.js.map"]


def test_diff_is_trimmed_to_the_token_budget():
    files = [(f"f{i}", "y" * 1000) for i in range(10)]

    def render(fs):
        return [{"role": "user", "content": "".join(c for _, c in fs)}]

    kept, over = cr.fit(FakeClient(), files, render, budget=4500)
    assert len(kept) == 4
    assert over == [f"f{i}" for i in range(4, 10)]
    assert "6 file(s) over the token budget" in cr.left_out_note([], over)


def test_a_big_first_file_does_not_hide_later_small_ones():
    files = [("big", "y" * 10_000)] + [(f"f{i}", "y" * 100) for i in range(5)]

    def render(fs):
        return [{"role": "user", "content": "".join(c for _, c in fs)}]

    kept, over = cr.fit(FakeClient(), files, render, budget=2000)
    assert [p for p, _ in kept] == [f"f{i}" for i in range(5)]
    assert over == ["big"]


def test_nothing_fits_skips_the_api(monkeypatch):
    fake = install(monkeypatch, FakeGh([], diff="diff --git a/x b/x\n" + "+y\n" * 1000))
    monkeypatch.setattr(cr, "TOKEN_BUDGET", 10)
    monkeypatch.setattr(cr.fit, "__defaults__", (10,))
    client = FakeClient()
    assert cr.main(lambda: client) == 0
    assert client.sent is None
    assert "Diff too large to review" in fake.commented()[0][-1]
