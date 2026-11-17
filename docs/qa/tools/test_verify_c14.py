"""Portable synthetic corpus checks; no access to private QA reports required."""

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("verify_c14", Path(__file__).with_name("verify-c14.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class VerifyC14Test(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.source = Path(self.temporary.name)
        checklist = self.source / "no-tested.md"
        checklist.write_text("\n".join(
            ("  * [ ]" if i % 2 else "- [ ]") + f" synthetic item {i}"
            for i in range(1, 52)
        ), encoding="utf-8")
        digest = hashlib.sha256(checklist.read_bytes()).hexdigest()
        rows = [f"Its SHA-256 at audit time is `{digest}`"]
        for i in range(1, 52):
            disposition = "**Candidate**" if i in (24, 25, 29, 36) else "**Retain**"
            rows.append(f"| {i} | {i} — synthetic item | {disposition} |")
        for alias in ("R37", "R38", "R39", "R40", "R43", "R43a", "R43b", "R43c"):
            report = self.source / f"{alias}.md"
            report.write_text(f"Synthetic {alias}\n", encoding="utf-8")
            digest = hashlib.sha256(report.read_bytes()).hexdigest()
            rows.append(f"| {alias} | `{report.name}` | 1 | `{digest}` |")
        self.document = self.source / "audit.md"
        self.document.write_text("\n".join(rows), encoding="utf-8")

    def test_accepts_complete_corpus_with_nested_and_star_checkboxes(self):
        VERIFIER.verify(self.document, self.source)

    def test_rejects_changed_source(self):
        (self.source / "R37.md").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source hash mismatch"):
            VERIFIER.verify(self.document, self.source)

    def test_rejects_missing_source(self):
        (self.source / "R38.md").unlink()
        with self.assertRaises(FileNotFoundError):
            VERIFIER.verify(self.document, self.source)

    def test_rejects_missing_audit_row(self):
        report = self.document.read_text(encoding="utf-8")
        self.document.write_text(report.replace("| 51 | 51 — synthetic item | **Retain** |", ""), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "51 sequential"):
            VERIFIER.verify(self.document, self.source)


if __name__ == "__main__":
    unittest.main()
