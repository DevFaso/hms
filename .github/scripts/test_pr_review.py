"""Tests for openai_review.py and pr_review.py (OpenAI first, Claude fallback)
against the real pinned SDKs; gh, git and the network are stubbed.

Run: pip install -r .github/scripts/requirements-dev.txt
     python -m pytest .github/scripts
"""
import inspect
import sys
import types
from pathlib import Path

import anthropic
import httpx2
import openai
import pytest

sys.path.insert(0, str(Path(__file__).parent))

import claude_review as cr  # noqa: E402
import openai_review as orv  # noqa: E402
import pr_review  # noqa: E402
import review_common as rc  # noqa: E402
from test_claude_review import SHA, FakeClient, FakeGh, comment  # noqa: E402

DIFF = "diff --git a/x b/x\n+1\n"


class FakeOpenAI:
    """client.responses.create / client.responses.input_tokens.count."""

    def __init__(self, error=None, text="Looks fine.", status="completed", refusal=None):
        self.error, self.kwargs, self.counted = error, None, 0
        client = self

        class InputTokens:
            def count(self, **kw):
                client.counted += 1
                return types.SimpleNamespace(input_tokens=len(kw["input"]))

        class Responses:
            input_tokens = InputTokens()

            def create(self, **kw):
                if client.error:
                    raise client.error
                client.kwargs = kw
                content = ([types.SimpleNamespace(type="refusal", refusal=refusal)]
                           if refusal else [])
                return types.SimpleNamespace(
                    output_text=text, status=status, model="gpt-x-2026",
                    incomplete_details=None,
                    output=[types.SimpleNamespace(type="message", content=content)],
                    usage=types.SimpleNamespace(input_tokens=10, output_tokens=5))

        self.responses = Responses()


def status_error(cls, status, message):
    req = httpx2.Request("POST", "https://api.openai.com/v1/responses")
    return cls(message, response=httpx2.Response(status, request=req), body=None)


QUOTA = status_error(openai.RateLimitError, 429,
                     "You exceeded your current quota, please check your plan and billing details.")


@pytest.fixture(autouse=True)
def env(monkeypatch, tmp_path):
    monkeypatch.setenv("GITHUB_REPOSITORY", "o/r")
    monkeypatch.setenv("PR_NUMBER", "7")
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test-openai")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-test")
    monkeypatch.delenv("REVIEW_PROVIDER", raising=False)
    monkeypatch.delenv("OPENAI_REVIEW_MODEL", raising=False)
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(tmp_path / "summary.md"))


def install(monkeypatch, pages=(), diff=DIFF):
    fake = FakeGh(list(pages), diff=diff)
    monkeypatch.setattr(rc, "gh", fake)
    monkeypatch.setattr(rc, "git_diff", lambda: fake.diff)
    return fake


def summary_text(tmp_path):
    return (tmp_path / "summary.md").read_text(encoding="utf-8")


# --- provider selection -------------------------------------------------------

@pytest.mark.parametrize("env_vars, expected", [
    ({"OPENAI_API_KEY": "k", "ANTHROPIC_API_KEY": "k"}, ["openai", "anthropic"]),
    ({"OPENAI_API_KEY": "", "ANTHROPIC_API_KEY": "k"}, ["anthropic"]),
    ({"ANTHROPIC_API_KEY": "k"}, ["anthropic"]),
    ({"OPENAI_API_KEY": "k"}, ["openai"]),
    ({}, []),
    ({"OPENAI_API_KEY": "k", "ANTHROPIC_API_KEY": "k", "REVIEW_PROVIDER": "anthropic"}, ["anthropic"]),
    ({"OPENAI_API_KEY": "k", "ANTHROPIC_API_KEY": "k", "REVIEW_PROVIDER": " OpenAI "}, ["openai"]),
    ({"ANTHROPIC_API_KEY": "k", "REVIEW_PROVIDER": "openai"}, []),
    ({"OPENAI_API_KEY": "k", "ANTHROPIC_API_KEY": "k", "REVIEW_PROVIDER": "gemini"}, ["openai", "anthropic"]),
])
def test_provider_selection(env_vars, expected):
    assert pr_review.selected(env_vars) == expected


def test_openai_is_primary_and_claude_is_not_called(monkeypatch, tmp_path):
    fake = install(monkeypatch)
    oa, cl = FakeOpenAI(), FakeClient()
    assert pr_review.main({"openai": lambda: oa, "anthropic": lambda: cl}) == 0
    assert cl.sent is None
    body = fake.commented()[0][-1]
    assert body.startswith(rc.MARKER)
    assert rc.head_marker(SHA) in body
    assert f"### OpenAI `{orv.DEFAULT_MODEL}` review of `{SHA[:8]}`" in body
    assert "Looks fine." in body
    assert "Reviewed by **OpenAI**" in summary_text(tmp_path)
    assert "sk-test" not in summary_text(tmp_path)


def test_quota_error_falls_back_to_claude(monkeypatch, tmp_path):
    fake = install(monkeypatch)
    cl = FakeClient()
    assert pr_review.main({"openai": lambda: FakeOpenAI(error=QUOTA),
                           "anthropic": lambda: cl}) == 0
    assert cl.sent is not None
    body = fake.commented()[0][-1]
    assert "### Claude review of" in body
    assert "failed" not in body
    summary = summary_text(tmp_path)
    assert "OpenAI (`gpt-6.2-astra`) failed: The API call failed (HTTP 429" in summary
    assert "Reviewed by **Claude**" in summary and "after a fallback" in summary


@pytest.mark.parametrize("error", [
    status_error(openai.InternalServerError, 503, "overloaded"),
    status_error(openai.AuthenticationError, 401, "Incorrect API key provided: sk-proj-****abcd"),
    openai.APIConnectionError(request=httpx2.Request("POST", "https://api.openai.com/v1/responses")),
])
def test_other_openai_errors_also_fall_back(monkeypatch, error):
    install(monkeypatch)
    cl = FakeClient()
    assert pr_review.main({"openai": lambda: FakeOpenAI(error=error), "anthropic": lambda: cl}) == 0
    assert cl.sent is not None


def test_empty_openai_answer_falls_back(monkeypatch):
    install(monkeypatch)
    cl = FakeClient()
    assert pr_review.main({"openai": lambda: FakeOpenAI(text="", status="incomplete"),
                           "anthropic": lambda: cl}) == 0
    assert cl.sent is not None


def test_both_fail_exits_1_with_one_note(monkeypatch, tmp_path):
    fake = install(monkeypatch)
    anth = anthropic.APIConnectionError(
        request=httpx2.Request("POST", "https://api.anthropic.com/v1/messages"))
    leak = status_error(openai.AuthenticationError, 401, "Incorrect API key provided: sk-proj-****abcd")
    assert pr_review.main({"openai": lambda: FakeOpenAI(error=leak),
                           "anthropic": lambda: FakeClient(error=anth)}) == 1
    assert len(fake.commented()) == 1
    body = fake.commented()[0][-1]
    assert f"### PR review of `{SHA[:8]}` failed" in body
    assert "OpenAI: The API call failed (HTTP 401" in body
    assert "Claude: The API call failed (APIConnectionError" in body
    assert rc.head_marker(SHA) not in body
    assert "sk-proj" not in body and "sk-proj" not in summary_text(tmp_path)


def test_only_configured_provider_failing_exits_1(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY")
    fake = install(monkeypatch)
    assert pr_review.main({"openai": lambda: FakeOpenAI(error=QUOTA)}) == 1
    body = fake.commented()[0][-1]
    assert body.split("\n")[1].startswith("### OpenAI review of")
    assert "HTTP 429" in body


def test_forced_anthropic_never_calls_openai(monkeypatch):
    monkeypatch.setenv("REVIEW_PROVIDER", "anthropic")
    install(monkeypatch)
    oa, cl = FakeOpenAI(), FakeClient()
    assert pr_review.main({"openai": lambda: oa, "anthropic": lambda: cl}) == 0
    assert oa.kwargs is None and oa.counted == 0
    assert cl.sent is not None


def test_forced_openai_does_not_fall_back(monkeypatch):
    monkeypatch.setenv("REVIEW_PROVIDER", "openai")
    install(monkeypatch)
    cl = FakeClient()
    assert pr_review.main({"openai": lambda: FakeOpenAI(error=QUOTA), "anthropic": lambda: cl}) == 1
    assert cl.sent is None


def test_no_key_at_all_posts_a_note(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY")
    monkeypatch.delenv("ANTHROPIC_API_KEY")
    fake = install(monkeypatch)
    assert pr_review.main({}) == 1
    assert "No reviewer is configured" in fake.commented()[0][-1]


# --- one sticky comment, whoever wrote it -------------------------------------

def test_both_providers_share_the_marker():
    assert cr.MARKER is rc.MARKER
    assert cr.head_marker is rc.head_marker
    assert orv.head_marker is rc.head_marker


def test_openai_review_patches_a_previous_claude_comment(monkeypatch):
    old = rc.MARKER + "\n" + rc.head_marker("0ld") + "\n### Claude review of `0ld`\n\nx"
    fake = install(monkeypatch, pages=[[comment(9, rc.BOT, old)]])
    assert pr_review.main({"openai": lambda: FakeOpenAI(), "anthropic": lambda: FakeClient()}) == 0
    assert not fake.commented()
    assert fake.patched()[0][3].endswith("/comments/9")


def test_head_reviewed_by_claude_is_not_reviewed_again_by_openai(monkeypatch):
    pages = [[comment(1, rc.BOT, rc.MARKER + "\n" + rc.head_marker(SHA) + "\n### Claude review")]]
    fake = install(monkeypatch, pages=pages)
    oa = FakeOpenAI()
    assert pr_review.main({"openai": lambda: oa}) == 0
    assert oa.kwargs is None
    assert not fake.patched() and not fake.commented()


# --- the OpenAI call itself ---------------------------------------------------

def test_openai_create_receives_exactly_these_kwargs(monkeypatch):
    monkeypatch.setenv("OPENAI_REVIEW_MODEL", "gpt-custom")
    install(monkeypatch)
    oa = FakeOpenAI()
    assert orv.main(lambda: oa) == 0
    assert set(oa.kwargs) == {"model", "instructions", "input", "max_output_tokens",
                              "reasoning", "store"}
    assert oa.kwargs["model"] == "gpt-custom"
    assert oa.kwargs["instructions"] == rc.PROMPT
    assert oa.kwargs["reasoning"] == {"effort": "high"}
    assert oa.kwargs["store"] is False
    assert "<diff>\n" + DIFF in oa.kwargs["input"]
    # the real SDK accepts every one of them
    from openai.resources.responses.responses import Responses
    from openai.resources.responses.input_tokens import InputTokens
    assert set(oa.kwargs) <= set(inspect.signature(Responses.create).parameters)
    assert {"model", "instructions", "input"} <= set(inspect.signature(InputTokens.count).parameters)


def test_blank_model_variable_uses_the_default(monkeypatch):
    monkeypatch.setenv("OPENAI_REVIEW_MODEL", "  ")
    assert orv.model() == orv.DEFAULT_MODEL


def test_openai_refusal_and_length_limit(monkeypatch):
    fake = install(monkeypatch)
    assert orv.main(lambda: FakeOpenAI(text="", refusal="no")) == 0
    assert "The model declined to review this diff." in fake.commented()[-1][-1]
    fake.calls.clear()
    assert orv.main(lambda: FakeOpenAI(text="partial", status="incomplete")) == 0
    assert "may be incomplete" in fake.commented()[-1][-1]


def test_openai_diff_is_trimmed_to_the_token_budget():
    files = [(f"f{i}", "y" * 1000) for i in range(10)]
    kept, over = orv.fit(FakeOpenAI(), files, lambda fs: "".join(c for _, c in fs), budget=4500)
    assert len(kept) == 4
    assert over == [f"f{i}" for i in range(4, 10)]


def test_default_openai_client_has_one_attempt_inside_the_job_timeout(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test-not-a-key")
    client = orv.default_client()
    assert client.max_retries == 0
    assert client.timeout == 900.0


@pytest.mark.parametrize("text", [
    "api_key_sk-proj-abcdef123",
    "token:sk-ant-api03-xyz",
    "x sk-svcacct-abc_def x",
])
def test_redaction_catches_keys_glued_to_a_word(text):
    assert "sk-" not in rc.redact(text)
