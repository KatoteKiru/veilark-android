# Security policy

## Supported code

Security fixes target the latest source revision and the latest published OSS
release. Release candidates and historical APKs may be unsupported.

## Reporting

Do not disclose a vulnerability, subscription, credential or server address in
a public issue. Contact the repository owner through GitHub's private security
advisory flow and include:

- affected version and Android version;
- minimal reproduction steps;
- expected and observed behavior;
- relevant redacted technical log entries;
- impact and any known workaround.

Never attach a live subscription or an unredacted VPN configuration.

## Release boundary

An accepted change is not a release merely because it compiles. A release must
pass unit tests and lint, be built from a clean revision, have its APK identity
and signing certificate checked, and have its published SHA-256 verified from a
fresh download.
