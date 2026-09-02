# Veilark privacy policy

[English](PRIVACY.md) | [Русский](PRIVACY.ru.md)

Effective date: August 23, 2026

This policy covers the public Veilark Android application with application ID
`app.veilark.android`. Veilark is an independent VPN client. It does not provide
or operate VPN servers, accounts, or subscriptions.

## Data handled by the app

Veilark stores imported subscriptions, server profiles, routing rules, and app
preferences on the device. Profile material is protected with Android Keystore,
and Android cloud backup is disabled for the application. Veilark does not
upload this stored data to a Veilark-operated service.

When a VPN is active, network traffic is routed to the server selected by the
user. That server and its operator may observe connection metadata and traffic
that is not otherwise protected end to end. Their practices are outside
Veilark's control.

When a user imports or refreshes a remote subscription, the app connects to the
URL supplied by the user. The subscription provider receives ordinary network
metadata such as the IP address and request headers.

## QR scanning

QR images are analysed locally by the pure-Java ZXing core. The scanner does not
need a remote recognition service and does not send camera frames or decoded QR
values to ZXing or Google.

Camera access is requested only when the user opens the QR scanner. Veilark does
not record video or keep camera frames after scanning.

## Diagnostics and logs

The technical log is stored locally and redacts profile links and common secret
fields. It leaves the device only if the user deliberately copies or shares it.

The manual diagnostics action sends HTTPS requests to Cloudflare, YouTube,
Google, GitHub, and Wikipedia to test reachability. Those services receive the
usual network metadata. The check runs only when requested by the user.

## Analytics, advertising, and sales

Veilark has no advertising SDK, Veilark analytics account, or automatic crash
reporting service. The project owner does not sell personal data. Third-party
SDK metrics and user-requested network connections are described above.

## Deletion and retention

Deleting a profile or subscription removes it from the app. Uninstalling
Veilark removes its local app data according to Android's platform behaviour.
Data already received by a subscription provider, VPN server operator, or a
diagnostic endpoint is governed by that third party's policy.

## Contact and changes

For a privacy question, open an issue in the
[public repository](https://github.com/KatoteKiru/veilark-android/issues) without
including a live subscription, credential, or unredacted log. For a sensitive
security matter, use GitHub's private security advisory process described in
[`SECURITY.md`](SECURITY.md).

Material changes to this policy will be committed to the public repository and
included in the release notes when they affect application behaviour.
