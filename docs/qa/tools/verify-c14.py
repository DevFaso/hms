from pathlib import Path
import argparse
import hashlib
import re


def verify(document: Path, source_dir: Path) -> None:
    report = document.read_text(encoding="utf-8-sig")
    checklist_path = source_dir / "no-tested.md"
    expected = re.search(r"Its SHA-256 at audit time is `([a-f0-9]{64})`", report)
    if expected is None:
        raise ValueError("Missing checklist hash")
    if hashlib.sha256(checklist_path.read_bytes()).hexdigest() != expected[1]:
        raise ValueError("Checklist hash mismatch")
    checklist = checklist_path.read_text(encoding="utf-8-sig").splitlines()
    open_lines = [i for i, line in enumerate(checklist, 1) if re.match(r"- \[ \]", line)]
    rows = re.findall(
        r"^\|\s*(\d+)\s*\|\s*(\d+)\s*—[^|]*\|\s*(.*?)\s*\|$", report, re.M
    )
    if [int(row[0]) for row in rows] != list(range(1, 52)):
        raise ValueError("Expected 51 sequential audit rows")
    if [int(row[1]) for row in rows] != open_lines:
        raise ValueError("Rows do not match the open checklist line numbers")
    if [int(row[0]) for row in rows if row[2].startswith("**Candidate")] != [24, 25, 29, 36]:
        raise ValueError("Unexpected completion candidates")
    catalog = re.findall(
        r"^\|\s*(R\d+[abc]?)\s*\|\s*`([^`]+)`\s*\|\s*(\d+)\s*\|\s*`([a-f0-9]{64})`",
        report, re.M,
    )
    if {entry[0] for entry in catalog} != {"R37", "R38", "R39", "R40", "R43", "R43a", "R43b", "R43c"} or len(catalog) != 8:
        raise ValueError("Expected eight unique source reports")
    for alias, filename, count, digest in catalog:
        source = source_dir / filename
        if source.resolve().parent != source_dir.resolve():
            raise ValueError("Source filename must stay inside source directory")
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError(f"{alias}: source hash mismatch")
        if len(source.read_text(encoding="utf-8-sig").splitlines()) != int(count):
            raise ValueError(f"{alias}: source line count mismatch")
    print("PASS: 51 open entries; four candidates; checklist and eight report hashes/counts match.")
    print("This verifies provenance and structure, not QA outcomes or clinical approval.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify the C14 audit against a supplied source corpus (read only).")
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--document", type=Path, default=Path(__file__).resolve().parents[1] / "test-split-c14-checklist-evidence-2026-11-12.md")
    args = parser.parse_args()
    try:
        verify(args.document, args.source_dir)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Verification failed: {error}\n")
