# Mobile release (patient apps)

How the two patient apps reach testers: the Android app through the Play
**internal testing** track, the iOS app through **TestFlight**. Both are
built, signed and uploaded by GitHub Actions only, on a manual
`workflow_dispatch`. Nobody uploads from a laptop.

| | Android | iOS |
|---|---|---|
| Workflow | `.github/workflows/mobile-android.yml` | `.github/workflows/mobile-ios.yml` |
| App id (never changes) | `com.bitnesttechs.hms.patient` | `com.bitnesttechs.hms.patient.native` |
| Destination | Play Console, internal testing | App Store Connect, TestFlight |
| Secrets | GitHub environment `mobile-release` | GitHub environment `mobile-release` |
| Human step after the run | **Send for review** in the Play Console (until the first publish) | none (processing is automatic) |
| Delivery proven by the run | yes: the job reads the track back from Play | no: see [What a green run proves](#what-a-green-run-proves) |

Pushes and pull requests run the same workflows without any secret: lint,
unit tests and a debug build (Android), and XcodeGen, the Info.plist gate, a
simulator build and unit tests (iOS). Only a dispatch touches a signing key.

## Dispatch inputs

Every input is validated in the `build` job before the job that holds the
secrets can start, by the shared action `.github/actions/validate-dispatch`.
A value the workflow does not know fails the run at once. An API dispatch
is not limited to the dropdown's values, so this check matters.

### Android: `mobile-android.yml`

| Input | Values | What it does |
|---|---|---|
| `release_action` | `build_only` (default) | Builds and signs the release bundle and keeps it as the run artifact `patient-android-aab` for 14 days. Nothing goes to Play. Uses the four signing secrets. |
| | `stage_on_internal` | Also uploads the bundle to the internal track and **commits the edit without sending it for review**. Testers get nothing until a human sends it for review (below). |
| | `stage_and_submit` | Uploads, commits **and sends for review**. **Refused** unless the repository variable `PLAY_APP_PUBLISHED` is exactly `true` (see [After the first publish](#after-the-first-publish-stage_and_submit)). |
| `api_environment` | `dev` (default), `prod` | The API the bundle is built against: `dev` = `https://dev.e-keneya.com/api`, `prod` = `https://api.e-keneya.com/api`. Anything else is refused. The old workflow mapped every value except `prod` to dev, and `production` shipped a dev bundle labelled production. Keep `dev` for testers: a tester who cancels an appointment on a prod build cancels a real patient's appointment. |

The policy (which action publishes, which sends for review, which URL) is
written once, in `scripts/mobile/android-release-plan.sh`. The build job
runs it early so a refusal costs seconds. The release job reads its gates
from its outputs.

### iOS: `mobile-ios.yml`

| Input | Values | What it does |
|---|---|---|
| `release_action` | `build_only` (default) | The PR build and nothing more. The `testflight` job is skipped, so no signing credential reaches the runner. (Unlike Android, `build_only` produces no signed artifact.) |
| | `upload_to_testflight` | Archives with the chosen configuration, checks the archived Info.plist, exports and uploads to TestFlight. |
| `configuration` | `Release-Dev` (default), `Release-Prod` | Which `patient-ios-app/Config/*.xcconfig` is layered on: API base URL and Keycloak settings. |

## Secrets and variables

The eight secrets live in the GitHub **environment** `mobile-release`
(Settings → Environments). Names are exactly as the workflows read them:

| Secret | Used by | What it is |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Android, every dispatch | The upload keystore (`.jks`), base64-encoded |
| `ANDROID_STORE_PASSWORD` | Android, every dispatch | Keystore password |
| `ANDROID_KEY_ALIAS` | Android, every dispatch | Alias of the upload key in that keystore |
| `ANDROID_KEY_PASSWORD` | Android, every dispatch | Password of that key |
| `PLAY_SERVICE_ACCOUNT_JSON` | Android, `stage_*` only | The Play service account's JSON key (upload + readback) |
| `APP_STORE_CONNECT_KEY_ID` | iOS, `upload_to_testflight` | App Store Connect API key id (ten upper-case letters and digits, validated before use) |
| `APP_STORE_CONNECT_ISSUER_ID` | iOS, `upload_to_testflight` | The issuer id shown with the key |
| `APP_STORE_CONNECT_PRIVATE_KEY` | iOS, `upload_to_testflight` | The key's `.p8` contents |

The shared action `.github/actions/require-secrets` checks them before use
and names **all** the missing ones in one error. It prints only names,
never values. After the keystore is decoded,
`scripts/mobile/CheckKeystore.java` opens it with the store password,
finds the alias and unlocks the key with the key password, exactly as the
signer will. It says which of the four is wrong before the 20-minute
release build starts. (`keytool -list` cannot check the key password.)

The App Store Connect key must be an **Admin** key: App Manager keys cannot
cloud-sign.

One **repository variable** (Settings → Secrets and variables → Actions →
Variables, *not* an environment variable, because the `build` job that
enforces it has no environment):

| Variable | Value | Meaning |
|---|---|---|
| `PLAY_APP_PUBLISHED` | unset, or exactly `true` | Set it to `true` only once the Android app has been published in the Play Console at least once. Until then, `stage_and_submit` is refused. |

## Android release (Play internal testing)

1. Dispatch from the ref to ship (normally `develop`):

   ```sh
   gh workflow run mobile-android.yml --ref develop \
     -f release_action=stage_on_internal -f api_environment=dev
   ```

2. The run validates the inputs, runs lint, unit tests and a debug build,
   then in the `mobile-release` environment: checks the secrets, proves the
   keystore opens, builds the signed bundle, checks it is signed, keeps it
   as an artifact, uploads it with `r0adkll/upload-google-play` (pinned by
   SHA, `tracks: internal`), and **reads the track back** (below).
3. **Manual step: send for review.** A `stage_on_internal` run ends with a
   notice saying the version code is on the internal track and has *not*
   been sent for review. Open **Play Console → the app → Testing → Internal
   testing** (or *Publishing overview*) and **send the changes for review**.
   Testers receive nothing until you do.

   Why the workflow cannot do this: Play refuses to send an app for review
   automatically before its first publish. The commit then fails with
   *"Changes cannot be sent for review automatically"*, after the bundle has
   already used up its version code. The service account also has only the
   app-level permission *Release apps to testing tracks*. It is kept that
   narrow on purpose, so sending for review stays a human's decision.
4. The Play release is named `<version code> (<api_environment>, <short
   sha>)`, so a tester's report can be traced to a commit.

### Version codes and build numbers

A version code is burnt as soon as Play has accepted it, even if nothing is
released. Both schemes come from the runner clock and are defined, with the
reasons they differ, in `scripts/mobile/build-number.sh`:

- Android: seconds since 2026-01-01 UTC. Play caps a version code at
  2 100 000 000, which `yyyymmddHHMM` would exceed.
- iOS: UTC `yyyymmddHHMM`. It has no cap and is readable in TestFlight.

**Before re-running a release job that failed after its upload step, look
at the console.** The re-run builds a fresh code, which is harmless, but the
first attempt's code may already be on the track. Two releases then wait
for review where one was meant.

### What a green run proves

- **Android.** After the upload, `scripts/mobile/play-track-readback.sh`
  mints an OAuth token from the same service-account key. It uses openssl
  for the RS256 JWT and curl for the call, so it adds no dependency. It
  opens a throwaway edit, reads the `internal` track, deletes the edit, and
  **fails the job unless this run's version code is on the track with
  status `completed`**. The key file is written `0600` to `$RUNNER_TEMP`
  and removed by the `if: always()` teardown. The token is never printed.
  - What it proves: the upload's edit was **committed**. The Play docs say
    a new edit's settings are "copied from the deployed version of the app",
    so the code in a fresh edit means the commit landed. This is the failure
    it exists for: the first `stage_on_internal` dispatch uploaded a bundle,
    never committed, and went green.
  - What it does not prove: that the release was sent for review or
    reached testers. `tracks.get` shows a committed-but-unsent release
    exactly as it shows a reviewed one: same version code, status
    `completed`. After a `stage_on_internal` run the console step above is
    still owed.
  - To confirm on the first real run: the Play docs do not say in so many
    words whether changes committed with `changesNotSentForReview=true`
    (or still under review) are part of what a new edit copies. Common
    practice (for example, reading track version codes right after an
    upload) says they are. If a `stage_on_internal` run fails the readback
    while the console shows the release, that assumption is wrong. Record
    it here, and do not dispatch again to "fix" it: the code is already
    burnt.
- **iOS.** A green run means `xcodebuild -exportArchive` uploaded the build.
  Apple processing, export compliance and tester availability are not read
  back. Check TestFlight in App Store Connect. The Info.plist gate
  (`scripts/mobile/check-info-plist.sh`) runs on the generated plist on
  every PR and on the **archived** plist before upload. It checks that
  every key declared in `project.yml` is present with a valid value:
  `CFBundleVersion` is this run's build number, `MEDIHUB_API_BASE_URL` is
  an https URL, the OAuth redirect scheme is registered, Face ID has a
  purpose string, and `ITSAppUsesNonExemptEncryption` is a real `<false/>`.

### After the first publish: `stage_and_submit`

Once the app has been published in the Play Console at least once:

1. Set the repository variable `PLAY_APP_PUBLISHED` to `true`.
2. Dispatch with `-f release_action=stage_and_submit` from then on.

This matters, because once the app is published, a `stage_on_internal`
release that is committed and never sent for review sits in the console
unreleased while the run is green: the same silent non-delivery in a later
form. The readback cannot tell the two apart (see above).

## iOS release (TestFlight)

1. Dispatch:

   ```sh
   gh workflow run mobile-ios.yml --ref develop \
     -f release_action=upload_to_testflight -f configuration=Release-Dev
   ```

2. The `build` job validates the inputs, generates the project, runs the
   mobile script tests and the source-plist gate, then builds and tests on
   a simulator. The `testflight` job (environment `mobile-release`, runner
   `macos-26`, because Apple refuses uploads built with an SDK older than
   iOS 26) checks the three secrets, validates the key id, installs the key,
   archives with `CURRENT_PROJECT_VERSION` set to the build number, checks
   the archived Info.plist, and exports and uploads. The key is removed by
   an `if: always()` step.
3. In App Store Connect → TestFlight, wait for processing, then add the
   build to the tester group if it is not added automatically. Export
   compliance is answered by `ITSAppUsesNonExemptEncryption: false` in
   `project.yml`.

## Rolling back

Neither store takes a version code or build number back, and testers'
devices do not downgrade. Rolling back means shipping the last good code
again under a **new** number.

- **Android, not yet sent for review:** in the Play Console, discard the
  unsent changes for the internal track. Nothing reached testers.
- **Android, already released to testers:** dispatch again from the last
  good ref (a tag or branch at that commit), for example
  `gh workflow run mobile-android.yml --ref <good-tag> -f release_action=stage_on_internal -f api_environment=dev`,
  then send it for review. The new, higher version code replaces the bad
  one for testers.
- **iOS:** in App Store Connect → TestFlight, **expire** the bad build so
  testers cannot install it, then dispatch `upload_to_testflight` from the
  last good ref.
- **A workflow change broke the release itself:** the workflows are on the
  dispatched ref, so dispatch from a ref that has the previous workflow
  files.

## Push notification credentials (not yet configured)

Push is phase 4 of the patient-app parity work. Nothing below is wired into
the release workflows yet. **The PR owner fills in this section.** The
names and values have not been confirmed.

### Android build inputs (Firebase Cloud Messaging)

The app reads four `BuildConfig` fields from Gradle properties or the
environment, and each defaults to empty. An empty value builds an app
without push. To be confirmed by the owner, then added to the `mobile-release`
environment and to the *Build the signed bundle* step's `env:`:

| Build input | Secret name in `mobile-release` | Status |
|---|---|---|
| `FCM_APPLICATION_ID` | TBD | not configured |
| `FCM_API_KEY` | TBD | not configured |
| `FCM_PROJECT_ID` | TBD | not configured |
| `FCM_SENDER_ID` | TBD | not configured |

### Backend (server-side sending)

The backend needs its own credentials to send pushes: an FCM
service-account key for Android and an APNs `.p8` auth key for iOS. For the
property names, see `application.properties` `app.push.*`.

| Purpose | Backend variable | Where it is set (Railway service / environment) | Status |
|---|---|---|---|
| FCM service-account JSON | *TBD, see `app.push.*`* | *TBD* | not configured |
| APNs auth key (`.p8`) | *TBD, see `app.push.*`* | *TBD* | not configured |
| APNs key id | *TBD, see `app.push.*`* | *TBD* | not configured |
| APNs team id | *TBD, see `app.push.*`* | *TBD* | not configured |
| APNs topic / bundle id | *TBD, see `app.push.*`* | *TBD* | not configured |

## Changing the workflows

- The shared pieces are `.github/actions/validate-dispatch`,
  `.github/actions/require-secrets` and `scripts/mobile/*`. Their tests are
  `scripts/mobile/test/run-tests.sh`. The Android build job runs the
  `dispatch keystore readback` sections on Linux. The iOS build job runs
  `plist dispatch` on macOS against the real `plutil`. Run everything
  locally with `bash scripts/mobile/test/run-tests.sh`. Without a real
  plutil the plist section uses a Python stand-in and takes a few minutes.
- The Play upload action is pinned by commit SHA. Before moving the pin,
  read the new version's `src/input-validation.ts`. At v1.1.5 an **empty**
  `tracks` falls back to `production`, so `tracks` stays a literal.
