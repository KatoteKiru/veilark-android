# Contributing

Keep changes narrow, testable and free of private infrastructure data.

1. Create a focused branch.
2. Add or update tests for behavior changes.
3. Run `./gradlew testOssDebugUnitTest lintOssRelease assembleOssDebug`.
4. Confirm `git diff --check` and inspect the merged OSS manifest.
5. Open a pull request that explains behavior, risk and verification evidence.

Do not commit subscriptions, credentials, signing keys, server addresses,
production logs or generated APKs. Protocol support claims require a parser test
and, where native code is involved, an ARM-device test. Every user-facing string
must use Android resources and include both the default English value and a
Russian translation under `values-ru`.
