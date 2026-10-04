#!/usr/bin/env bash
# The Android release policy, written once. Both jobs of mobile-android.yml
# call it: the build job to refuse a dispatch at second zero, the release job
# to get the outputs every later step gates on.
#
# Reads (all through the environment):
#   RELEASE_ACTION      build_only | stage_on_internal | stage_and_submit
#   API_ENVIRONMENT     dev | prod
#   PLAY_APP_PUBLISHED  the repository variable vars.PLAY_APP_PUBLISHED
#
# Writes to $GITHUB_OUTPUT (stdout outside Actions):
#   publish=true|false          upload to Play at all
#   send_for_review=true|false  commit the edit AND send it for review
#   api_environment=dev|prod    the validated value, for labels
#   api_base_url=...            what the bundle is built against
#
# stage_and_submit is REFUSED, not warned about, until PLAY_APP_PUBLISHED is
# exactly "true". Before the app's first publish Play will not auto-submit:
# the bundle uploads, the commit dies with "Changes cannot be sent for review
# automatically", and the version code is burnt. The variable is set by a
# human after the first publish (docs/runbooks/mobile-release.md).
set -euo pipefail

action=${RELEASE_ACTION:-}
env_name=${API_ENVIRONMENT:-}
published=${PLAY_APP_PUBLISHED:-}

case "$action" in
  build_only)
    publish=false; send_for_review=false ;;
  stage_on_internal)
    publish=true; send_for_review=false ;;
  stage_and_submit)
    if [ "$published" != "true" ]; then
      echo "::error::stage_and_submit is refused until the repository variable PLAY_APP_PUBLISHED is 'true' — Play will not send an app for review automatically before its first publish, and the version code would be burnt. Use stage_on_internal and send for review in the Play Console (docs/runbooks/mobile-release.md)."
      exit 1
    fi
    publish=true; send_for_review=true ;;
  *)
    echo "::error::unknown release_action — expected build_only, stage_on_internal or stage_and_submit"
    exit 1 ;;
esac

# Exact values only. The previous mapping sent anything that was not exactly
# "prod" to dev while the Play release name carried the raw value, so
# `-f api_environment=production` shipped a dev bundle labelled production.
case "$env_name" in
  dev) api_base_url=https://dev.e-keneya.com/api ;;
  prod) api_base_url=https://api.e-keneya.com/api ;;
  *)
    echo "::error::unknown api_environment — expected dev or prod"
    exit 1 ;;
esac

out=${GITHUB_OUTPUT:-/dev/stdout}
{
  echo "publish=$publish"
  echo "send_for_review=$send_for_review"
  echo "api_environment=$env_name"
  echo "api_base_url=$api_base_url"
} >> "$out"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "Release plan: \`$action\` against \`$env_name\` ($api_base_url); publish=$publish, send for review=$send_for_review" >> "$GITHUB_STEP_SUMMARY"
fi
