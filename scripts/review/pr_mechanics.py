# Mechanical pre-review of one PR at its exact head (docs/agents/review-instructions.md, section 2).
# usage, from the repo root:  python scripts/review/pr_mechanics.py <PR#>
# Read-only: it fetches, then reads git objects. It never checks out, merges or writes.
# Every line it prints that is not "none" / 0 / [] is something to open and read, not yet a finding.
import json, re, subprocess, sys, xml.etree.ElementTree as ET
def sh(*a, ok=True):
    r = subprocess.run(a, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if ok and r.returncode: sys.exit(f"FAILED {' '.join(a)}\n{r.stderr}")
    return r.stdout
n = sys.argv[1]
H = sh("gh", "pr", "view", n, "--json", "headRefOid", "-q", ".headRefOid").strip()
sh("git", "fetch", "-q", "origin", "develop", f"pull/{n}/head")
MB = sh("git", "merge-base", "origin/develop", H).strip()
bases = [MB] + [m + "^2" for m in sh("git", "rev-list", "--merges", f"{MB}..{H}").split()]
show = lambda rev, p: sh("git", "show", f"{rev}:{p}", ok=False)
print(f"head {H}\nmerge-base {MB}  merges-in-PR {len(bases) - 1}")
changed = [l.split("\t") for l in sh("git", "diff", "--name-status", MB, H).splitlines()]
# 1 conflict markers
print("MARKERS:", sh("git", "grep", "-nE", r"^(<<<<<<<|=======|>>>>>>>)( |$)", H, "--", ".", ":!*.md", ok=False).strip() or "none")
# 2 changelog
C = "hospital-core/src/main/resources/db/migration"
cl = show(H, f"{C}/changelog.xml")
try:
    root = ET.fromstring(cl.encode("utf-8"))
    stray = [(e.tag.split('}')[-1], (e.tail or "").strip()[:40]) for e in root if (e.tail or "").strip()] + ([("root", root.text.strip()[:40])] if (root.text or "").strip() else [])
    print("CHANGELOG stray text (XSD-invalid):", stray or "none")
except ET.ParseError as e:
    print("CHANGELOG NOT WELL-FORMED:", e)
ids = re.findall(r'<changeSet\b[^>]*\bid="([^"]+)"', cl)
reg = re.findall(r'path="(V[^"]+\.sql)"', cl)
on_disk = [p.split("/")[-1] for p in sh("git", "ls-tree", "--name-only", H, f"{C}/").split("\n") if re.search(r"/V\d+.*\.sql$", p)]
dup = lambda xs: sorted({x for x in xs if xs.count(x) > 1})
print(f"CHANGELOG changeSets {len(ids)} dup-ids {dup(ids)} dup-files {dup(reg)} unregistered {sorted(set(on_disk) - set(reg))} registered-but-missing {sorted(set(reg) - set(on_disk))}")
removed = [l for l in sh("git", "diff", MB, H, "--", f"{C}/changelog.xml").splitlines() if l.startswith("-") and not l.startswith("---")]
print("CHANGELOG lines removed/edited (expect 0):", len(removed))
new_sql = [p for st, *ps in changed for p in ps[-1:] if p.startswith(C) and p.endswith(".sql") and st == "A"]
print("APPLIED migrations touched (expect none):", [(st, ps[-1]) for st, *ps in changed if ps[-1].startswith(C) and ps[-1].endswith(".sql") and st != "A"] or "none")
dev = sh("git", "ls-tree", "--name-only", "origin/develop", f"{C}/")
for p in new_sql:
    f = p.split("/")[-1]; num = re.match(r"V(\d+)", f).group(1); body = show(H, p)
    blk = re.search(r"<sqlFile[^>]*" + re.escape(f) + r"[^>]*>", cl)
    clash = [d.split("/")[-1] for d in dev.split("\n") if re.search(rf"/V{num}__", d) and not d.endswith(f)]
    print(f"NEW {f}: number-clash-with-develop {clash or 'no'}; dollar-quote {'$$' in body or bool(re.search(r'[$][a-z_]+[$]', body))}; splitStatements {re.search(r'splitStatements=.(\w+)', blk.group(0)).group(1) if blk and 'splitStatements' in blk.group(0) else 'default(true)'}")
# 3 i18n / message keys lost (vs merge-base and vs every develop tip merged into the PR)
def jkeys(s):
    out = set()
    def w(x, p):
        for k, v in x.items(): w(v, p + k + ".") if isinstance(v, dict) else out.add(p + k)
    if s.strip(): w(json.loads(s), "")
    return out
pkeys = lambda s: {m.group(1) for m in re.finditer(r"^\s*([^#!\s][^=:\s]*)\s*[=:]", s, re.M)}
files = [f"hospital-portal/src/assets/i18n/{l}.json" for l in ("en", "fr", "es")] + sorted({ps[-1] for st, *ps in changed if ps[-1].endswith(".properties") and "/i18n/" in ps[-1]})
sets = {}
for f in files:
    k = jkeys if f.endswith(".json") else pkeys
    head = k(show(H, f)); sets[f] = head
    lost = set().union(*[k(show(b, f)) for b in bases]) - head
    print(f"KEYS {f.split('/')[-1]}: {len(head)} lost-vs-bases {len(lost)} {sorted(lost)[:8]}")
en, fr, es = (sets[f] for f in files[:3])
print(f"KEYS parity: en-fr {len(en ^ fr)} en-es {len(en ^ es)}")
# 4 tests and guards removed or loosened
t = sh("git", "diff", MB, H, "--", "*Test.java", "*IT.java", "*.spec.ts", "*.test.mjs", "hospital-portal/e2e")
gone = [l for l in t.splitlines() if re.match(r"-(?!--).*(assert|expect\(|verify\(|@Test|\bit\()", l)]
skip = [l for l in t.splitlines() if re.match(r"\+(?!\+\+).*(@Disabled|\bxit\(|\bxdescribe\(|\.skip\(|\bfit\(|\bfdescribe\(|lenient\(\))", l)]
print(f"TESTS assertion/test lines removed {len(gone)}; skips/focus/lenient added {len(skip)}; test files deleted {[ps[-1] for st, *ps in changed if st == 'D' and re.search(r'(Test|IT)[.]java$|[.]spec[.]ts$', ps[-1])]}")
print("ALLOWLISTS/BASELINES changed:", [ps[-1] for st, *ps in changed if re.search(r"allowlist|baseline|frozen|exempt", ps[-1], re.I)] or "none")
