# Workspace Map

## Repository Structure

- `macos/src/main/kotlin/app/veilark/macos/Main.kt`: desktop entry, window, menu bar, update UI.
- `macos/src/main/kotlin/app/veilark/macos/StartupDiagnostics.kt`: fail-visible startup logging.
- `macos/src/main/kotlin/com/example/veilark/session/VeilarkSession.kt`: tunnel lifecycle.
- `macos/src/main/kotlin/com/example/veilark/profile/GeoRoutingRepository.kt`: GEO release handling.
- `macos/src/main/kotlin/com/example/veilark/storage/EncryptedStore.kt`: Keychain-backed profile key.
- `macos/src/main/kotlin/com/example/veilark/update/MacUpdateClient.kt`: updater client.
- `macos/scripts/test-in-app-updater.sh`: native replacement test on Mac.
- `.github/workflows/macos-ota-release.yml`: release build, signed manifest, atomic deploy and redownload.
- `macos/docs/{OTA.md,RELEASE_GATES.md}`: release contract and gates.

## Test Entry Points

- On a Mac: `cd macos && ./gradlew --no-daemon test`, `./scripts/fetch-engines.sh`, `./gradlew --no-daemon packageDmg`, then native updater test and physical smoke.
- On Windows: from `macos/`, `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain test --no-daemon --max-workers=2` covers JVM tests only.

## Durable Context

- This checkout is a branch of the private Veilark client repository; `macos/` is the actual release path. Root-level Android artifacts are not Mac release evidence.
- Published macOS preview is ARM64, not a universal customer-build claim.
