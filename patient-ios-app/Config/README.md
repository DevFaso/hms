# Per-env build configurations

> Implements Phase 2.8.B from
> [`../../docs/keycloak-implementation-gaps.md`](../../docs/keycloak-implementation-gaps.md)
> for iOS. Closes the latent gap where `MEDIHUB_KEYCLOAK_*` was only
> readable from `ProcessInfo` (i.e. Xcode Run actions) and silently empty
> in `xcodebuild archive` output.

## How it works

Three xcconfig files live next to this README:

| File | Build configuration | Issuer host | SSO default | TestFlight track |
| --- | --- | --- | --- | --- |
| [`Dev.xcconfig`](Dev.xcconfig) | `Release-Dev` | `hms-keycloak-dev.up.railway.app` | ON | Internal |
| [`UAT.xcconfig`](UAT.xcconfig) | `Release-UAT` | `hms-keycloak-uat.up.railway.app` | ON | Internal (UAT track) |
| [`Prod.xcconfig`](Prod.xcconfig) | `Release-Prod` | `hms-keycloak-prod.up.railway.app` | OFF until cutover | Production |

The "Issuer host" column is the bare hostname for readability; the
actual `MEDIHUB_KEYCLOAK_ISSUER` build setting in each xcconfig is the
full OIDC URL — `https://<host>/realms/hms`.

Each xcconfig sets four build settings:

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

All three commands need `-project` and `-scheme` — `xcodebuild archive`
fails or builds the wrong target without them.

```bash
# Dev (TestFlight internal)
xcodebuild -project MediHubPatient.xcodeproj \
           -scheme MediHubPatient \
           -configuration Release-Dev \
           -archivePath build/MediHubPatient-Dev.xcarchive \
           archive

# UAT
xcodebuild -project MediHubPatient.xcodeproj \
           -scheme MediHubPatient \
           -configuration Release-UAT \
           -archivePath build/MediHubPatient-UAT.xcarchive \
           archive

# Prod
xcodebuild -project MediHubPatient.xcodeproj \
           -scheme MediHubPatient \
           -configuration Release-Prod \
           -archivePath build/MediHubPatient-Prod.xcarchive \
           archive
```

Default `Debug` and `Release` configurations remain unchanged for
in-Xcode development; their `MEDIHUB_KEYCLOAK_*` values stay empty
unless overridden via the scheme's environment variables.

## Flipping SSO on in prod (Phase 3 cutover)

When Phase 3 lands and prod is ready to flip:

1. Change `MEDIHUB_KEYCLOAK_SSO_ENABLED = 0` → `1` in
   [`Prod.xcconfig`](Prod.xcconfig).
2. Bump `CURRENT_PROJECT_VERSION` in
   [`../project.yml`](../project.yml).
3. `xcodegen generate` and `xcodebuild archive -configuration Release-Prod`.
4. Submit to App Review **≥ 48 h before** the prod maintenance window
   (per Phase 2.8.C).
