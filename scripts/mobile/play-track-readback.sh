#!/usr/bin/env bash
# Reads a Play track back after a publish and fails unless the version code
# this run built is on it. A green upload step proves the action returned; it
# does not prove the build was delivered. The first stage_on_internal
# dispatch uploaded a bundle, never committed the edit, and went green, and
# only a hand-written Play API call showed the track still held version 13.
#
#   play-track-readback.sh --credentials FILE --package NAME --track TRACK \
#                          --version-code N [--expect-status STATUS]
#                          [--fixture DIR]
#
# Talks to the Play Developer API with the same service account the upload
# used, and adds no dependency to the release job: the OAuth access token is
# minted with openssl (an RS256 JWT assertion, RFC 7523) and curl, and the
# JSON is read with jq. All three are on the GitHub-hosted images.
#
#   1. POST oauth2.googleapis.com/token   (scope androidpublisher)
#   2. edits.insert                        a throwaway edit
#   3. edits.tracks.get                    the track, as that edit sees it
#   4. edits.delete                        always, from the EXIT trap
#
# What it proves, per the API: an edit starts from the app's current state
# (developers.google.com/android-publisher/edits: "The app's initial
# settings ... are all copied from the deployed version of the app"), so the
# new version code on the track in a fresh edit means the upload's edit was
# COMMITTED. It does not prove review happened: a commit made with
# changesNotSentForReview=true (the stage_on_internal path) leaves the
# release with the status the upload gave it (`completed`) while the console
# still holds it for a human to send for review, and tracks.get shows no
# difference between the two. The Play docs do not say in so many words that
# changes committed but not yet sent for review are part of what a new edit
# copies; the runbook records that the first real stage_on_internal run is
# where that is confirmed.
#
# Secrets: the credentials file is the caller's (write it 0600, delete it in
# an `if: always()` step). This script copies the private key into a 0700
# temp directory it removes on exit, never prints the token, masks it with
# ::add-mask:: under Actions, and hands it to curl through a config file
# rather than argv, so it is not in the process list either.
#
# --fixture DIR replaces every HTTP call with canned responses from DIR
# (token.json, insert.json, track.json and optional <name>.status files), so
# the logic can be exercised with no network. The JWT is still built and
# signed with the credentials' key.
set -euo pipefail

TOKEN_URI=https://oauth2.googleapis.com/token
SCOPE=https://www.googleapis.com/auth/androidpublisher
API=https://androidpublisher.googleapis.com/androidpublisher/v3

CREDENTIALS=
PACKAGE=
TRACK=
VERSION_CODE=
EXPECT_STATUS=
FIXTURE=

while [ $# -gt 0 ]; do
  case "$1" in
    --credentials) CREDENTIALS=${2:-}; shift 2 ;;
    --package) PACKAGE=${2:-}; shift 2 ;;
    --track) TRACK=${2:-}; shift 2 ;;
    --version-code) VERSION_CODE=${2:-}; shift 2 ;;
    --expect-status) EXPECT_STATUS=${2:-}; shift 2 ;;
    --fixture) FIXTURE=${2:-}; shift 2 ;;
    *) echo "::error::play-readback: unknown argument"; exit 2 ;;
  esac
done

RE_PACKAGE='^[A-Za-z][A-Za-z0-9_]*([.][A-Za-z][A-Za-z0-9_]*)+$'
RE_TRACK='^[a-z][a-z0-9:_-]*$'
RE_DIGITS='^[0-9]+$'
RE_EDIT_ID='^[A-Za-z0-9_-]+$'
RE_TOKEN='^[A-Za-z0-9._~+/=-]+$'
RE_EMAIL='^[^@[:space:]"]+@[^@[:space:]"]+$'

[[ $PACKAGE =~ $RE_PACKAGE ]] || { echo "::error::play-readback: --package is not a package name"; exit 2; }
[[ $TRACK =~ $RE_TRACK ]] || { echo "::error::play-readback: --track is not a track name"; exit 2; }
[[ $VERSION_CODE =~ $RE_DIGITS ]] || { echo "::error::play-readback: --version-code must be numeric"; exit 2; }
case "$EXPECT_STATUS" in
  ''|draft|inProgress|halted|completed) ;;
  *) echo "::error::play-readback: --expect-status must be draft, inProgress, halted or completed"; exit 2 ;;
esac
if [ -n "$FIXTURE" ] && [ ! -d "$FIXTURE" ]; then
  echo "::error::play-readback: --fixture must be a directory"
  exit 2
fi
if [ -z "$CREDENTIALS" ] || [ ! -s "$CREDENTIALS" ]; then
  echo "::error::play-readback: the service-account credentials file is missing or empty"
  exit 2
fi

# One line, control characters removed, `::` defused, bounded. For text that
# came back from Google, which is shown so a failure can be diagnosed.
sanitize() {
  tr -d '\000-\037\177' | sed 's/::/: :/g' | cut -c1-300
}

WORK=$(mktemp -d)
chmod 700 "$WORK"
EDIT_ID=
TOKEN_READY=false

# $1 method, $2 url, $3 response body file, rest: extra curl arguments.
# Prints the HTTP status.
http() {
  local method=$1 url=$2 out=$3 name
  shift 3
  if [ -n "$FIXTURE" ]; then
    case "$method $url" in
      "POST $TOKEN_URI") name=token ;;
      "POST $API/applications/$PACKAGE/edits") name=insert ;;
      "GET $API/applications/$PACKAGE/edits/"*"/tracks/$TRACK") name=track ;;
      "DELETE $API/applications/$PACKAGE/edits/"*) name=delete ;;
      *) name=unexpected ;;
    esac
    if [ -n "${READBACK_FIXTURE_LOG:-}" ]; then
      echo "$method $url" >> "$READBACK_FIXTURE_LOG"
    fi
    if [ -f "$FIXTURE/$name.json" ]; then cp "$FIXTURE/$name.json" "$out"; else : > "$out"; fi
    if [ -f "$FIXTURE/$name.status" ]; then
      tr -d '[:space:]' < "$FIXTURE/$name.status"
    elif [ "$name" = delete ]; then
      echo 204
    elif [ "$name" = unexpected ]; then
      echo 599
    else
      echo 200
    fi
    return 0
  fi
  curl -sS --max-time 60 -X "$method" -o "$out" -w '%{http_code}' "$@" "$url"
}

# The authorised variant: the bearer token reaches curl through a 0600
# config file, never argv.
api() {
  local method=$1 url=$2 out=$3
  shift 3
  http "$method" "$url" "$out" -K "$WORK/auth.cfg" "$@"
}

cleanup() {
  local status
  if [ -n "$EDIT_ID" ] && [ "$TOKEN_READY" = true ]; then
    # An edit is a draft copy; deleting it discards nothing that was
    # committed. Left behind it only expires, but it would also block the
    # next upload's edit until it does, so it is always removed.
    status=$(api DELETE "$API/applications/$PACKAGE/edits/$EDIT_ID" "$WORK/delete.json" || true)
    case "$status" in
      2??) echo "readback edit deleted" ;;
      *) echo "::warning::play-readback: could not delete the readback edit (HTTP $status); it expires on its own" ;;
    esac
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# Reports a failed call with Google's own error text, sanitised.
api_error() {
  local what=$1 status=$2 body=$3 detail
  detail=$(jq -r '(.error | if type == "object" then (.status // "") + " " + (.message // "") else (. // "") end) + " " + (.error_description // "")' "$body" 2>/dev/null | sanitize || true)
  echo "::error::play-readback: $what failed with HTTP $status"
  if [ -n "$(printf '%s' "$detail" | tr -d '[:space:]')" ]; then
    echo "Google said: $detail"
  fi
}

# ---- 1. access token ------------------------------------------------------

if ! jq -e 'type == "object" and .type == "service_account" and (.client_email|type) == "string" and (.private_key|type) == "string"' "$CREDENTIALS" >/dev/null 2>&1; then
  echo "::error::play-readback: the credentials are not a service-account JSON key (type, client_email, private_key)"
  exit 1
fi
EMAIL=$(jq -r .client_email "$CREDENTIALS")
[[ $EMAIL =~ $RE_EMAIL ]] || { echo "::error::play-readback: client_email in the credentials is not an address"; exit 1; }
( umask 077; jq -r .private_key "$CREDENTIALS" > "$WORK/key.pem" )

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

NOW=$(date +%s)
HEADER='{"alg":"RS256","typ":"JWT"}'
# The token endpoint is a constant, not the credentials' token_uri: a key
# file must not be able to send the signed assertion somewhere else.
CLAIMS=$(jq -cn --arg iss "$EMAIL" --arg scope "$SCOPE" --arg aud "$TOKEN_URI" \
  --argjson iat "$NOW" --argjson exp "$((NOW + 600))" \
  '{iss: $iss, scope: $scope, aud: $aud, iat: $iat, exp: $exp}')
SIGNING_INPUT="$(printf '%s' "$HEADER" | b64url).$(printf '%s' "$CLAIMS" | b64url)"
if ! SIGNATURE=$(printf '%s' "$SIGNING_INPUT" | openssl dgst -sha256 -sign "$WORK/key.pem" -binary | b64url) || [ -z "$SIGNATURE" ]; then
  echo "::error::play-readback: could not sign the token request with the service-account key"
  exit 1
fi
( umask 077; printf '%s.%s' "$SIGNING_INPUT" "$SIGNATURE" > "$WORK/assertion" )
rm -f "$WORK/key.pem"
if [ -n "$FIXTURE" ] && [ -n "${READBACK_FIXTURE_ASSERTION_OUT:-}" ]; then
  cp "$WORK/assertion" "$READBACK_FIXTURE_ASSERTION_OUT"
fi

# `|| true`: on a transport failure curl exits non-zero having already
# printed 000 as the status, which the check below reports; without it
# `set -e` would end the script with no message at all.
status=$(http POST "$TOKEN_URI" "$WORK/token.json" \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion@$WORK/assertion" || true)
rm -f "$WORK/assertion"
if [ "$status" != 200 ]; then
  api_error "the token request" "$status" "$WORK/token.json"
  exit 1
fi
TOKEN=$(jq -r '.access_token // empty' "$WORK/token.json")
rm -f "$WORK/token.json"
if ! [[ $TOKEN =~ $RE_TOKEN ]]; then
  echo "::error::play-readback: the token response carried no usable access_token"
  exit 1
fi
if [ "${GITHUB_ACTIONS:-}" = true ]; then
  echo "::add-mask::$TOKEN"
fi
( umask 077; printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" > "$WORK/auth.cfg" )
unset TOKEN
TOKEN_READY=true

# ---- 2. a throwaway edit ----------------------------------------------------

status=$(api POST "$API/applications/$PACKAGE/edits" "$WORK/insert.json" \
  -H 'Content-Type: application/json' --data '{}' || true)
if [ "$status" != 200 ]; then
  api_error "edits.insert" "$status" "$WORK/insert.json"
  exit 1
fi
EDIT_ID=$(jq -r '.id // empty' "$WORK/insert.json")
if ! [[ $EDIT_ID =~ $RE_EDIT_ID ]]; then
  EDIT_ID=
  echo "::error::play-readback: edits.insert returned no usable edit id"
  exit 1
fi

# ---- 3. the track ----------------------------------------------------------

status=$(api GET "$API/applications/$PACKAGE/edits/$EDIT_ID/tracks/$TRACK" "$WORK/track.json" || true)
if [ "$status" != 200 ]; then
  api_error "edits.tracks.get for $TRACK" "$status" "$WORK/track.json"
  exit 1
fi
if ! jq -e 'type == "object"' "$WORK/track.json" >/dev/null 2>&1; then
  echo "::error::play-readback: edits.tracks.get returned something that is not a track"
  exit 1
fi

# versionCodes are int64 in the API and arrive as JSON strings; compared as
# strings after tostring so a number would match too.
RELEASE_STATUS=$(jq -r --arg vc "$VERSION_CODE" '
  [.releases[]? | select(any(.versionCodes[]?; tostring == $vc))][0].status // empty
' "$WORK/track.json")
FOUND=$(jq -r --arg vc "$VERSION_CODE" '
  [.releases[]? | select(any(.versionCodes[]?; tostring == $vc))] | length
' "$WORK/track.json")

# Only digits leave this list, so it is safe to print.
ON_TRACK=$(jq -r '[.releases[]?.versionCodes[]? | tostring] | unique | join(" ")' "$WORK/track.json" \
  | tr -cd '0-9 ' | cut -c1-200)

if [ "$FOUND" = 0 ]; then
  echo "::error::version code $VERSION_CODE is NOT on the $TRACK track — the upload's edit was not committed, so nothing was delivered"
  echo "$TRACK holds version code(s): ${ON_TRACK:-none}"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "Play readback: version code $VERSION_CODE **missing** from \`$TRACK\` (track holds: ${ON_TRACK:-none})" >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 1
fi

case "$RELEASE_STATUS" in
  statusUnspecified|draft|inProgress|halted|completed) ;;
  *) RELEASE_STATUS=unrecognised ;;
esac
if [ -n "$EXPECT_STATUS" ] && [ "$RELEASE_STATUS" != "$EXPECT_STATUS" ]; then
  echo "::error::version code $VERSION_CODE is on the $TRACK track with release status $RELEASE_STATUS, expected $EXPECT_STATUS"
  exit 1
fi

echo "version code $VERSION_CODE is on the $TRACK track (release status $RELEASE_STATUS)"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "Play readback: version code $VERSION_CODE is on \`$TRACK\` (release status \`$RELEASE_STATUS\`)" >> "$GITHUB_STEP_SUMMARY"
fi
