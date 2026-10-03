# Android update notices

Published in private rc39 / 66; see `RC39_OTA_NOTES.md` for release evidence.

A persisted JobScheduler job checks the existing signed update manifest on a
six-hour schedule, with network and battery-not-low constraints. Android may
defer execution. A dedicated lightweight process does not initialize VPN cores.
There is no automatic APK download, installation, VPN reconnect or wake-lock.

One notification is attempted per increasing release code, only if app/channel
notification permission is enabled. Its immutable explicit PendingIntent opens
Veilark's existing confirmation/release-notes flow. Confirmation is consumed once
in screen state rather than repeated on lazy-list recycling.

Validation: private Kotlin compilation, UpdateNoticePolicyTest and lint passed
locally. No physical Android notification or battery measurement was performed.
Old installed clients require an update to obtain this feature.
