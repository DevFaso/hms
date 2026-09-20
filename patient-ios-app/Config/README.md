# Per-env build configurations

> Implements Phase 2.8.B from
> [`../../docs/keycloak-implementation-gaps.md`](../../docs/keycloak-implementation-gaps.md)
> for iOS. Closes the latent gap where `MEDIHUB_KEYCLOAK_*` was only
> readable from `ProcessInfo` (i.e. Xcode Run actions) and silently empty
> in `xcodebuild archive` output.

## How it works

Two xcconfig files live next to this README:

| File | Build configuration | Issuer host | SSO default | TestFlight track |
| --- | --- | --- | --- | --- |
| [`Dev.xcconfig`](Dev.xcconfig) | `Release-Dev` | `hms-keycloak-dev.up.railway.app` | ON | Internal |
| [`Prod.xcconfig`](Prod.xcconfig) | `Release-Prod` | `hms-keycloak-prod.up.railway.app` | OFF until cutover | Production |

The "Issuer host" column is the bare hostname for readability; the
actual `MEDIHUB_KEYCLOAK_ISSUER` build setting in each xcconfig is the
full OIDC URL — `https://<host>/realms/hms`.

Each xcconfig sets five build settings:

- `MEDIHUB_API_BASE_URL` — the backend this configuration talks to.
  `Release-Dev` uses `https://dev.e-keneya.com/api` (the dev API is served
  same-origin by the portal host; `api.dev.e-keneya.com` has no DNS record),
  `Release-Prod` uses `https://api.e-keneya.com/api`.
- `MEDIHUB_KEYCLOAK_ISSUER` — full OIDC issuer URL.
- `MEDIHUB_KEYCLOAK_SSO_ENABLED` — `0` or `1`.
- `MEDIHUB_KEYCLOAK_CLIENT_ID` — shared across envs (`hms-patient-ios`).
- `MEDIHUB_KEYCLOAK_REDIRECT_URI` — shared (`com.bitnesttechs.hms.patient.native:/oauth2redirect`).

The build settings get baked into `Info.plist` via `$(VAR)` substitution
(see [`../MediHubPatient/Resources/Info.plist`](../MediHubPatient/Resources/Info.plist)),
and `KeycloakConfig` / `FeatureFlags` read them at runtime via
`Bundle.main.object(forInfoDictionaryKey:)`. `ProcessInfo` is still
checked first so scheme env vars continue to override during Xcode Run
sessions — useful for QA against a local docker-compose Keycloak.

## Building per env

Both commands need `-project` and `-scheme` — `xcodebuild archive`
fails or builds the wrong target without them.

```bash
# Dev (TestFlight internal)
xcodebuild -project MediHubPatient.xcodeproj \
           -scheme MediHubPatient \
           -configuration Release-Dev \
           -archivePath build/MediHubPatient-Dev.xcarchive \
           archive

# Prod
xcodebuild -project MediHubPatient.xcodeproj \
           -scheme MediHubPatient \
           -configuration Release-Prod \
           -archivePath build/MediHubPatient-Prod.xcarchive \
           archive
```

Default `Debug` and `Release` build settings remain unchanged; local
Xcode Run/Test sessions use the scheme environment variables from
[`../project.yml`](../project.yml), which point at the dev Keycloak
realm with SSO enabled. Archive builds still take their values from the
selected `Config/{Dev,Prod}.xcconfig` file.

## Flipping SSO on in prod (Phase 3 cutover)

When Phase 3 lands and prod is ready to flip:

1. Change `MEDIHUB_KEYCLOAK_SSO_ENABLED = 0` → `1` in
   [`Prod.xcconfig`](Prod.xcconfig).
2. Bump `CURRENT_PROJECT_VERSION` in
   [`../project.yml`](../project.yml).
3. `xcodegen generate` and `xcodebuild archive -configuration Release-Prod`.
4. Submit to App Review **≥ 48 h before** the prod maintenance window
   (per Phase 2.8.C).

## Why the xcconfigs are attached to the target

They were attached to the **project**, which does not work: Xcode resolves
target settings over target xcconfig over project settings over project
xcconfig, so the empty defaults in `project.yml`'s `settings.base` won over
every value here and each setting resolved to `""`. A `Release-Prod`
archive therefore fell back to the dev server. `configFiles` now sits under
`targets.MediHubPatient`, where it outranks those defaults.
