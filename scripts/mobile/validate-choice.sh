#!/usr/bin/env bash
# Fails unless a workflow_dispatch input is EXACTLY one of the allowed values.
# Called by .github/actions/validate-dispatch; every value arrives through the
# environment, never as script text:
#
#   DISPATCH_INPUT    the input's name, e.g. release_action
#   DISPATCH_VALUE    the dispatched value
#   DISPATCH_ALLOWED  the allowed values, whitespace-separated
#
# Why this exists: the Run-workflow dialog constrains a `choice` input, an API
# dispatch does not. An unrecognised value then matches no gate and the run
# goes green having shipped nothing, or (api_environment) falls through to a
# default while the release is labelled with the raw value.
#
# An exact comparison per allowed value, NOT a substring test against the
# joined list: `case " $ALLOWED " in *" $VALUE "*)` accepts
# "stage_on_internal stage_and_submit". And the value is not echoed to stdout
# before it has been validated: the runner parses every stdout line for
# ::workflow commands, so a newline in a dispatched value would inject one.
# A rejected value goes to $GITHUB_STEP_SUMMARY, a file the runner does not
# scan, shell-quoted onto one line.
set -euo pipefail
# No globbing: the lists below are split on whitespace, and a `*` in one
# must stay a character, not become the names of files in the cwd.
set -f

name=${DISPATCH_INPUT:-}
value=${DISPATCH_VALUE:-}
allowed=${DISPATCH_ALLOWED:-}

# The name and the list come from the workflow file, but they are still
# checked: a typo there must fail loudly, not validate against nothing.
case "$name" in
  ''|*[!a-z0-9_]*) echo "::error::validate-dispatch: input-name must be a lower-case identifier"; exit 2 ;;
esac
if [ -z "$(printf '%s' "$allowed" | tr -d '[:space:]')" ]; then
  echo "::error::validate-dispatch: no allowed values given for $name"
  exit 2
fi

# The whole list first: checking each token only as the loop reaches it
# would let a bad list through whenever the value matches an earlier token.
for candidate in $allowed; do
  case "$candidate" in
    *[!A-Za-z0-9_.-]*) echo "::error::validate-dispatch: allowed values for $name must be plain tokens"; exit 2 ;;
  esac
done

for candidate in $allowed; do
  if [ "$value" = "$candidate" ]; then
    echo "dispatch input $name=$candidate"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
      echo "This dispatch: \`$name=$candidate\`" >> "$GITHUB_STEP_SUMMARY"
    fi
    exit 0
  fi
done

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf 'Rejected dispatch: %s=%q\n' "$name" "$value" >> "$GITHUB_STEP_SUMMARY"
fi
# $allowed passed the plain-token check above, so it is safe on this line.
# Word splitting is the point here (globbing is off, see set -f above).
# shellcheck disable=SC2086
echo "::error::unknown $name — expected one of: $(printf '%s ' $allowed | sed 's/ $//')"
exit 1
