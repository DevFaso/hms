#!/usr/bin/env bash
# Fails unless every named environment variable is set and non-empty, and
# reports ALL the missing ones together. Called by .github/actions/require-secrets:
#
#   REQUIRED_NAMES     variable names, whitespace-separated
#   ENVIRONMENT_LABEL  where they are configured, for the message
#                      (e.g. mobile-release)
#
# The values themselves are the caller's step `env:`, which a composite
# action's steps inherit. Only NAMES are ever printed.
#
# Together, not one at a time: a first-time setup of the mobile-release
# environment otherwise costs a whole job run per missing secret, and an
# unset signing secret otherwise surfaces deep in a release build as
# "Keystore was tampered with, or password was incorrect", which points at
# the keystore rather than at the missing secret.
set -euo pipefail
# No globbing: the lists below are split on whitespace, and a `*` in one
# must stay a character, not become the names of files in the cwd.
set -f

names=${REQUIRED_NAMES:-}
label=${ENVIRONMENT_LABEL:-}

case "$label" in
  ''|*[!a-z0-9-]*) echo "::error::require-secrets: environment label must be lower-case letters, digits and dashes"; exit 2 ;;
esac

missing=
count=0
for name in $names; do
  case "$name" in
    [A-Z_]*) ;;
    *) echo "::error::require-secrets: secret names must be upper-case identifiers"; exit 2 ;;
  esac
  case "$name" in
    *[!A-Z0-9_]*) echo "::error::require-secrets: secret names must be upper-case identifiers"; exit 2 ;;
  esac
  count=$((count + 1))
  if [ -z "${!name:-}" ]; then
    missing="$missing $name"
  fi
done

if [ "$count" -eq 0 ]; then
  echo "::error::require-secrets: no secret names given"
  exit 2
fi
if [ -n "$missing" ]; then
  echo "::error::unset in the $label environment:$missing"
  exit 1
fi
echo "all $count required secret(s) are set in the $label environment"
