"""Tests for claude_review.py: gh and the Anthropic client are stubbed.

Run: python -m pytest .github/scripts
"""
import json
import sys
import types
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

try:
    import anthropic  # noqa: F401
except ImportError:  # the workflow installs it; locally a stub is enough
    stub = types.ModuleType("anthropic")

    class APIError(Exception):
        def __init__(self, message="error"):
            super().__init__(message)
            self.message = message

    class APIStatusError(APIError):
        status_code = 500

    stub.APIError, stub.APIStatusError = APIError, APIStatusError
    stub.Anthropic = object
    sys.modules["anthropic"] = stub

import claude_review as cr  # noqa: E402

SHA = "abcdef1234567890"


def comment(cid, login, body):
    return {"id": cid, "user": {"login": login}, "body": body}


class FakeGh:
    def __init__(self, pages, diff="", head=SHA):
        self.pages, self.diff, self.head, self.calls = pages, diff, head, []

    def __call__(self, *args):
        self.calls.append(args)
        if args[:2] == ("pr", "view"):
            return json.dumps({"title": "T", "body": "B", "headRefOid": self.head})
        if args[:2] == ("pr", "diff"):
            return self.diff
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
    assert cr.main(lambda: FakeClient(error=cr.anthropic.APIError("boom"))) == 1
    body = fake.commented()[0][-1]
    assert "failed" in body
    assert cr.head_marker(SHA) not in body


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
