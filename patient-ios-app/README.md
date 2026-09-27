# e-Keneya Patient — Native iOS App

Pure SwiftUI native iOS app for the e-Keneya (HMS) patient portal. The
display name under the icon is **e-Keneya**; the bundle identifier is
`com.bitnesttechs.hms.patient.native`. Neither ever changes.

## Tech Stack

- **Language**: Swift 5.9 language mode, built by Xcode 26 on CI
- **UI**: SwiftUI
- **Networking**: URLSession + async/await
- **Auth**: username/password JWT or Keycloak SSO (AppAuth, pinned exactly in `project.yml`)
- **Auth Storage**: iOS Keychain
- **Min iOS**: 17.0
- **Languages**: English (base), French, Spanish — `Resources/{en,fr,es}.lproj`

## The project is generated, not committed

`project.yml` is the source of truth. [XcodeGen](https://github.com/yonaskolb/XcodeGen)
generates `MediHubPatient.xcodeproj` **and** `MediHubPatient/Resources/Info.plist`
from it; both are git-ignored. Never create the project by hand in Xcode and
never add an Info.plist key by hand — put it under
`targets.MediHubPatient.info.properties` in `project.yml`, or the next
`xcodegen generate` drops it. (A hand-built project also misses the
per-configuration settings below, such as `MEDIHUB_API_BASE_URL`, and the
Face ID purpose string, without which iOS terminates the app.)

## Setup

```sh
brew install xcodegen
cd patient-ios-app
xcodegen generate
open MediHubPatient.xcodeproj
```

Run `xcodegen generate` again after pulling a change to `project.yml` or after
adding or removing a source file.

### Configurations

| Configuration | Used for | Per-environment values |
|---|---|---|
| Debug | Running from Xcode, CI tests | scheme environment variables |
| Release-Dev | TestFlight builds against dev | `Config/Dev.xcconfig` |
| Release-Prod | App Store builds | `Config/Prod.xcconfig` |

See `Config/README.md` for the `MEDIHUB_*` settings. For local development
against a backend on your machine, set in the Xcode scheme (Run → Arguments →
Environment Variables):

```
MEDIHUB_API_BASE_URL = http://localhost:8081/api
```

### Localised purpose strings

The text of a system permission prompt comes from
`Resources/<lang>.lproj/InfoPlist.strings`; the English base value is in
`project.yml`. A purpose string added to `project.yml` needs a line in all
three `InfoPlist.strings` files.

## CI

`.github/workflows/mobile-ios.yml` is the only place the app is compiled from a
clean checkout: it runs `xcodegen generate`, checks the generated Info.plist
carries every key the app needs, builds for the simulator and runs the
`MediHubPatientTests` unit tests. A `workflow_dispatch` with
`release_action=upload_to_testflight` also archives and uploads to TestFlight.

## Project Structure

```
MediHubPatient/
├── App/                ← @main entry point, auth gate
├── Core/
│   ├── Auth/           ← AuthManager, KeychainHelper, Keycloak, sign-out revocation
│   ├── Chat/           ← attachment cache, inbox timestamps
│   ├── Config/         ← feature flags, Keycloak configuration
│   ├── Locale/         ← LocalizationManager, wire-enum labels
│   ├── Models/         ← DTOs mirroring the backend
│   ├── Network/        ← APIClient (Accept-Language, token refresh), APIEndpoints
│   └── Push/           ← APNs registration and notification taps
├── Features/           ← one folder per screen family
└── Resources/          ← Assets.xcassets, {en,fr,es}.lproj
```

## API

All endpoints connect to the Spring Boot backend (`/api`). Patient data lives
under `/me/patient/*`; every request carries `Authorization: Bearer <token>`
and `Accept-Language: <app language>`. A 401 triggers one token refresh and
one retry.
