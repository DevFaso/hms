# C13: mobile pre-check-in unit coverage

Brief: `test-split-codex-claude-2026-11-12.md`, C13. Executed 11 November 2026 (client date). Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`. Separate isolated worktree: `C:/dev/hms-qa-mobile`.

Three Kotlin tests and three Swift XCTest cases exercise the actual pre-check-in model serializers/decoders:

- Unchanged demographics are omitted, empty questionnaire submissions stay explicit, and consent is false unless explicitly acknowledged.
- Questionnaire answers remain inside the correct submission as a JSON string; false and zero survive serialization rather than being mistaken for missing answers.
- A response distinguishes appointment/pre-check-in state from whether demographics changed, preserving counts and timestamp spelling.

The fixtures are synthetic, in memory only. These are model wire-shape tests, not endpoint validation, questionnaire UI, authentication, network or device tests. No production source or workflow changed. Existing Android test discovery and iOS XcodeGen test-directory discovery include the new files automatically.

## Verification

Android, from `patient-android-app` with process-local `ANDROID_HOME=C:/Users/tiego/AppData/Local/Android/Sdk`:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*PreCheckInWireContractTest' -PapiBaseUrl=https://cdx-api.example.invalid/api -PwebPortalUrl=https://cdx-web.example.invalid -PFCM_API_KEY= -PFCM_APPLICATION_ID= -PFCM_PROJECT_ID= -PFCM_SENDER_ID= --console=plain
# Exit 0: 3 tests, 0 failures/errors.
./gradlew.bat :app:lintDebug -PapiBaseUrl=https://cdx-api.example.invalid/api -PwebPortalUrl=https://cdx-web.example.invalid -PFCM_API_KEY= -PFCM_APPLICATION_ID= -PFCM_PROJECT_ID= -PFCM_SENDER_ID= --console=plain
# Exit 0.
```

The synthetic API/portal values and empty FCM overrides prevent use of deployment/provider configuration. The tests instantiate models/Moshi only. No Android service, emulator or device starts. Gradle reports existing kapt/Moshi deprecation warnings. No Kotlin formatting task/ktlint is configured or available; Kotlin compiled, Android lint ran, and whitespace was checked.

Swift, from the worktree root:

```powershell
swift-format format --in-place patient-ios-app/MediHubPatientTests/PreCheckInWireContractTests.swift
swift-format lint --strict patient-ios-app/MediHubPatientTests/PreCheckInWireContractTests.swift
# Both exit 0.
swift test --build-system native --package-path C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c13-swift-subset
# Exit 0: all 3 XCTest cases executed, 0 failures.
git diff --check
# Exit 0.
```

The portable Swift package has **no dependencies** and only two targets: `MediHubPatient` containing an unchanged copy of `Core/Models/PreCheckInModels.swift`, and `MediHubPatientTests` containing an exact copy of the new XCTest source. It uses Swift tools version 6.0 and the native SwiftPM build system. It is an external verification harness, not a new application target. Source-copy hashes were compared before publication. The additional Swift Testing runner reports zero tests because the cases use XCTest; the XCTest runner executed three.

The default Swift 6.3.2 command initially failed because it selected a 6.4.1 SDK. Swift 6.4.1's default build engine then reported duplicate installed platform registrations. A native-build attempt without its matching runtime could not execute the package manifest. The successful process uses the installed 6.4.1 compiler, SDK, runtime and XCTest/Testing DLL directories on its local PATH, and `--build-system native`. No user installation, persistent PATH or SDK setting was changed. Actual model module emission and new XCTest-source type checking also exited 0 with the matching compiler/SDK.

## Limits and artifacts

This Windows run does not compile the complete UIKit/SwiftUI app or prove iOS SDK integration. macOS CI remains necessary for the normal app test target. No mobile build was distributed and no live app/server was contacted. The checklist's real TestFlight/Play/device coverage remains open.

Logs under `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/`: `c13-android-tests.log`, `c13-android-lint.log`, `c13-swift-format.log`, `c13-swift-module.log`, `c13-swift-typecheck.log`, `c13-swift-executed-tests.log`. Earlier failed toolchain attempts are retained separately. Published CI/Rex are separate evidence.
