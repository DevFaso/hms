#!/usr/bin/env bash
# scripts/cut-rc1.sh — pre-flight + signed tag cut for v1.0.0-rc1.
#
# Roadmap row 13 deliverable: "Tag, signed release notes from
# CHANGELOG.md, freeze feat/* for 1 week, only fix/* allowed during
# soak". This script handles the tag step and verifies the
# pre-conditions; the freeze policy lives in
# docs/runbooks/release-soak-protocol.md.
#
# Usage:
#   scripts/cut-rc1.sh                 # interactive — confirms each step
#   scripts/cut-rc1.sh --dry-run       # validate pre-flight + roadmap
#                                      # consistency only; SKIP build/test
#                                      # gates and do NOT create/push the tag
#   TAG=v1.0.0-rc2 scripts/cut-rc1.sh  # cut a re-spin
#
# Exits non-zero on any pre-flight failure. The tag is NOT pushed
# unless every gate is green AND you confirm at the prompt.
#
# What this script does NOT do:
#   - Promote develop → uat → main (use your normal PR + merge flow).
#   - Create the GitHub release object (push the tag, then run
#     `gh release create $TAG --notes-file docs/releases/$TAG.md`).
#   - Manage your signing key. Configure once, either:
#       GPG: `git config --global user.signingkey <KEY-ID>`
#       SSH: `git config user.signingkey <path-to-pubkey>` plus
#            `git config gpg.format ssh` and a populated
#            `gpg.ssh.allowedSignersFile`. v1.0.0-rc1 was cut with
#            an SSH signing key per the post-cut chore PR.

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
TAG="${TAG:-v1.0.0-rc1}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE_NOTES="$REPO_ROOT/docs/releases/${TAG}.md"
SOAK_DOC="$REPO_ROOT/docs/runbooks/release-soak-protocol.md"
DRY_RUN=false

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown arg: $arg"; exit 2 ;;
  esac
done

# ── Output helpers ────────────────────────────────────────────────────────────
red()    { printf '\033[31m%s\033[0m\n' "$*"; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
heading() { printf '\n\033[1m── %s ──\033[0m\n' "$*"; }

fail() { red "✗ $1"; exit 1; }
ok()   { green "✓ $1"; }

# ── Pre-flight ────────────────────────────────────────────────────────────────
heading "Pre-flight"
cd "$REPO_ROOT"

# 1. Tag name shape
if ! [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-rc[0-9]+)?$ ]]; then
  fail "TAG=$TAG does not match SemVer pattern v<MAJOR>.<MINOR>.<PATCH>[-rc<N>]"
fi
ok "Tag name '$TAG' has the right shape."

# 2. Tag must not already exist locally
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  fail "Tag $TAG already exists locally. Delete it first if this is a re-cut: git tag -d $TAG"
fi
ok "Tag $TAG does not yet exist locally."

# 3. Tag must not exist on origin
if git ls-remote --tags origin "refs/tags/$TAG" 2>/dev/null | grep -q "refs/tags/$TAG"; then
  fail "Tag $TAG already exists on origin. Delete it remotely first if this is a re-cut: git push origin :refs/tags/$TAG"
fi
ok "Tag $TAG does not yet exist on origin."

# 4. Working tree must be clean
if ! git diff --quiet || ! git diff --cached --quiet; then
  fail "Working tree is dirty. Commit or stash before tagging."
fi
ok "Working tree is clean."

# 5. Must be on the release branch (default: main)
RELEASE_BRANCH="${RELEASE_BRANCH:-main}"
CURRENT_BRANCH="$(git branch --show-current)"
if [ "$CURRENT_BRANCH" != "$RELEASE_BRANCH" ]; then
  fail "Currently on '$CURRENT_BRANCH' but expected '$RELEASE_BRANCH'. Set RELEASE_BRANCH=<name> if cutting from elsewhere."
fi
ok "On release branch '$RELEASE_BRANCH'."

# 6. Must be in sync with origin
git fetch --quiet origin "$RELEASE_BRANCH"
LOCAL_HEAD="$(git rev-parse "$RELEASE_BRANCH")"
ORIGIN_HEAD="$(git rev-parse "origin/$RELEASE_BRANCH")"
if [ "$LOCAL_HEAD" != "$ORIGIN_HEAD" ]; then
  fail "Local '$RELEASE_BRANCH' ($LOCAL_HEAD) is not in sync with 'origin/$RELEASE_BRANCH' ($ORIGIN_HEAD). Pull/push first."
fi
ok "Local '$RELEASE_BRANCH' is in sync with origin."

# 7. Release notes file must exist for this tag
if [ ! -f "$RELEASE_NOTES" ]; then
  fail "Release notes not found at $RELEASE_NOTES. Create it before cutting (see docs/releases/v1.0.0-rc1.md as the template)."
fi
ok "Release notes present: $RELEASE_NOTES"

# 8. Soak protocol doc must exist (governs everything that happens after the tag)
if [ ! -f "$SOAK_DOC" ]; then
  fail "Soak protocol not found at $SOAK_DOC. The cut is meaningless without the freeze policy that follows it."
fi
ok "Soak protocol present: $SOAK_DOC"

# 9. Signing key configured. Works for both gpg.format=openpgp (the git
#    default) and gpg.format=ssh (modern git ≥ 2.34, what v1.0.0-rc1
#    used). The error messages branch on which format is selected so
#    the operator gets actionable instructions, not a generic GPG hint.
SIGNING_KEY="$(git config --get user.signingkey || true)"
SIGNING_FORMAT="$(git config --get gpg.format || echo openpgp)"
if [ -z "$SIGNING_KEY" ]; then
  if [ "$SIGNING_FORMAT" = "ssh" ]; then
    fail "git config user.signingkey is not set. For SSH signing: git config user.signingkey <path-to-pubkey>"
  else
    fail "git config user.signingkey is not set. For GPG signing: git config --global user.signingkey <KEY-ID>"
  fi
fi
ok "Signing key configured: $SIGNING_KEY (format=$SIGNING_FORMAT)"

# 10. The configured signing tool can actually sign right now. Catches
#     expired key / locked GPG agent / unreadable SSH private half.
case "$SIGNING_FORMAT" in
  ssh)
    # SSH signing: ssh-keygen -Y sign on a temp file using the
    # configured key. If the private half is unreadable or the
    # public-key path is wrong, this fails before we tag anything.
    PRIVKEY_PATH="${SIGNING_KEY%.pub}"
    if [ ! -r "$PRIVKEY_PATH" ]; then
      fail "SSH signing key configured ($SIGNING_KEY) but private half ($PRIVKEY_PATH) is missing or unreadable."
    fi
    SSH_TEST_TMP="$(mktemp)"
    echo test > "$SSH_TEST_TMP"
    if ! ssh-keygen -Y sign -f "$PRIVKEY_PATH" -n git "$SSH_TEST_TMP" >/dev/null 2>&1; then
      rm -f "$SSH_TEST_TMP" "${SSH_TEST_TMP}.sig"
      fail "ssh-keygen -Y sign failed with $PRIVKEY_PATH (passphrase-locked? wrong key?). Verify manually: ssh-keygen -Y sign -f $PRIVKEY_PATH -n git <(echo test)"
    fi
    rm -f "$SSH_TEST_TMP" "${SSH_TEST_TMP}.sig"
    ok "SSH signing works."
    ;;
  openpgp|*)
    if ! echo test | gpg --batch --clearsign --local-user "$SIGNING_KEY" >/dev/null 2>&1; then
      fail "GPG cannot sign with key $SIGNING_KEY right now (expired? agent locked? wrong key?). Run: gpg --list-secret-keys"
    fi
    ok "GPG signing works."
    ;;
esac

# ── Roadmap consistency ───────────────────────────────────────────────────────
heading "Roadmap consistency"

# CSV has quoted fields with embedded commas (rows 5/9/10/13), so we use
# python3's csv module instead of awk -F, which would split mid-quote
# and shift field indexes. python3 is already a project dependency
# (scripts/build-roadmap-xlsx.py).
ROADMAP_CHECK="$(python3 - <<'PY'
import csv, sys
with open('docs/roadmap.csv', newline='') as f:
    reader = csv.reader(f)
    header = next(reader)
    bad = []
    row13 = None
    for row in reader:
        if not row or row[0] != 'v1.0':
            continue
        item = row[3]
        status = row[8]
        if item == 'v1.0.0-rc1 cut':
            row13 = status
        elif status != 'completed':
            bad.append(f"  {item} — status={status}")
    if bad:
        print('NOT_COMPLETED:')
        for line in bad:
            print(line)
        sys.exit(1)
    print(f"ROW13_STATUS:{row13 or 'MISSING'}")
PY
)" || { red "✗ Some v1.0 rows are not completed:"; echo "$ROADMAP_CHECK"; fail "All v1.0 rows must be 'completed' before the cut."; }

ok "All v1.0 rows (other than row 13) are completed."

ROW13_STATUS="${ROADMAP_CHECK#ROW13_STATUS:}"
case "$ROW13_STATUS" in
  started|completed) ok "Row 13 status is '$ROW13_STATUS' (cut materials landed)." ;;
  *) fail "Row 13 status is '$ROW13_STATUS' — expected 'started' or 'completed' before cutting." ;;
esac

# ── Build / test gates ────────────────────────────────────────────────────────
heading "Gates (this is the slow part — ~10 minutes)"

if [ "$DRY_RUN" = true ]; then
  yellow "Dry-run: skipping the build / test gates. Run without --dry-run to gate-check before pushing."
else
  echo "→ Backend tests + jacoco coverage verification…"
  ./gradlew --no-daemon \
    :hospital-core:test \
    :hospital-core:jacocoTestReport \
    :hospital-core:jacocoTestCoverageVerification \
    > /tmp/cut-rc1-backend.log 2>&1 \
    && ok "Backend gate green." \
    || { red "Backend gate failed. Tail of /tmp/cut-rc1-backend.log:"; tail -50 /tmp/cut-rc1-backend.log; exit 1; }

  echo "→ Frontend lint + format-check + unit tests…"
  (cd hospital-portal && npm run lint > /tmp/cut-rc1-fe-lint.log 2>&1) \
    && ok "Frontend lint green." \
    || { red "Frontend lint failed. Tail of /tmp/cut-rc1-fe-lint.log:"; tail -50 /tmp/cut-rc1-fe-lint.log; exit 1; }

  # `format:check` (prettier --check) instead of `format` (prettier --write).
  # The pre-flight already asserted a clean tree above; running the
  # write-mode formatter here would silently mutate files and we would
  # tag the un-committed diff. format:check fails loudly if any file is
  # not already formatted.
  (cd hospital-portal && npm run format:check > /tmp/cut-rc1-fe-format.log 2>&1) \
    && ok "Frontend format check green." \
    || { red "Frontend format check failed. Tail of /tmp/cut-rc1-fe-format.log:"; tail -50 /tmp/cut-rc1-fe-format.log; exit 1; }

  (cd hospital-portal && npm run test:headless > /tmp/cut-rc1-fe-test.log 2>&1) \
    && ok "Frontend Karma green." \
    || { red "Frontend Karma failed. Tail of /tmp/cut-rc1-fe-test.log:"; tail -100 /tmp/cut-rc1-fe-test.log; exit 1; }

  echo "→ FR / ES i18n parity…"
  # The release notes + soak protocol both reference the FR completeness
  # gate (roadmap row 12). The CI workflow runs npm run i18n:check; the
  # cut script must run the same gate so the tag's verification surface
  # matches what CI enforces.
  (cd hospital-portal && npm run i18n:check > /tmp/cut-rc1-fe-i18n.log 2>&1) \
    && ok "Frontend i18n parity green." \
    || { red "Frontend i18n parity failed. Tail of /tmp/cut-rc1-fe-i18n.log:"; tail -50 /tmp/cut-rc1-fe-i18n.log; exit 1; }

  echo "→ Frontend Playwright (a11y + keyboard-nav)…"
  # Local binary, not npx. CI avoids `npx playwright` to keep the pinned
  # version + skip the install-unverified-releases security hotspot;
  # the cut script honors the same convention so a release operator
  # can't accidentally end up on a different version under a stale npx
  # cache.
  (cd hospital-portal && ./node_modules/.bin/playwright test e2e/a11y.spec.ts e2e/keyboard-nav.spec.ts --project=chromium --reporter=line > /tmp/cut-rc1-fe-pw.log 2>&1) \
    && ok "Frontend Playwright green." \
    || { red "Frontend Playwright failed. Tail of /tmp/cut-rc1-fe-pw.log:"; tail -50 /tmp/cut-rc1-fe-pw.log; exit 1; }

  # Production build — confirms the bundle is shippable.
  echo "→ Frontend production build…"
  (cd hospital-portal && ./node_modules/.bin/ng build --configuration production > /tmp/cut-rc1-fe-build.log 2>&1) \
    && ok "Frontend production build succeeded." \
    || { red "Frontend production build failed. Tail of /tmp/cut-rc1-fe-build.log:"; tail -50 /tmp/cut-rc1-fe-build.log; exit 1; }
fi

# ── Confirm + tag ────────────────────────────────────────────────────────────
heading "Confirm"
echo "About to:"
echo "  1. Cut signed tag: $TAG"
echo "  2. At commit:      $LOCAL_HEAD ($(git log -1 --format='%s' "$LOCAL_HEAD"))"
echo "  3. Annotation:     fixed RC summary + pointers to docs/releases/${TAG}.md and docs/runbooks/release-soak-protocol.md"
echo "  4. Push to:        origin"
echo "  5. Begin soak:     per docs/runbooks/release-soak-protocol.md (7 days, fix/* only)"

if [ "$DRY_RUN" = true ]; then
  yellow "Dry-run: stopping here. Re-run without --dry-run to actually push the tag."
  exit 0
fi

read -r -p "Type 'cut $TAG' exactly to proceed: " confirm
if [ "$confirm" != "cut $TAG" ]; then
  fail "Aborted — did not receive the exact confirmation string."
fi

# Tag annotation: short summary + pointer. Operators read the full release
# notes via `git show $TAG`'s annotation footer + the docs/releases/ file.
TAG_MSG_FILE="$(mktemp)"
cat > "$TAG_MSG_FILE" <<EOF
$TAG: HMS v1.0 release candidate

Cumulative v1.0 cut bundling roadmap rows 2-12 (Clinical Safety,
Pharmacy, Security, Operations, Accessibility, i18n). Row 13
delivered as part of this tag.

Full release notes: docs/releases/${TAG}.md
Soak protocol:      docs/runbooks/release-soak-protocol.md
Cut at commit:      $LOCAL_HEAD
EOF

git tag -s "$TAG" -F "$TAG_MSG_FILE" "$LOCAL_HEAD"
rm -f "$TAG_MSG_FILE"
ok "Local signed tag created: $TAG"

git push origin "$TAG"
ok "Tag pushed to origin."

heading "Next steps"
cat <<EOF
1. Create the GitHub release object:
   gh release create $TAG \\
     --title "HMS $TAG" \\
     --notes-file docs/releases/${TAG}.md \\
     --verify-tag

2. Announce on the team channel (cut time + soak-end time).

3. Open the soak log per docs/runbooks/release-soak-protocol.md.

4. Flip docs/roadmap.csv row 13 status: started → completed.
   Regenerate docs/roadmap.xlsx via scripts/build-roadmap-xlsx.py.
   Commit + push to develop as a chore/* PR (this PR is allowed
   during soak per the policy).

The soak begins now. Promotion to v1.0.0 follows the exit
criteria in docs/runbooks/release-soak-protocol.md §Exit criteria.
EOF
