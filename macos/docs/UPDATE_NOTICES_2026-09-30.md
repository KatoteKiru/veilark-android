# macOS update notices

Published in 1.0.15 / 10015; see `RELEASE_1.0.15.md`. Checks continue every six hours while
the application runs, including with its window closed. Fully quitting stops them.
Only the existing verified manifest is used. No automatic download/install and
no VPN interruption is introduced.

UNUserNotificationCenter requests alert permission and posts one notice per newer
build. Its independent JNI callback survives window/sidebar disposal. A click
opens Settings. A native accepted request records its build; acceptance is not
proof of visible notification delivery. Notification identity is restricted to
the packaged `app.veilark.macos` bundle. No foreign delegate is overwritten.

Local JVM compilation/tests passed. macOS 14/26 native CI run: 36754987315.
Physical notification permission, display, click and sleep/wake acceptance remain
unverified. Older clients need an update before using this mechanism.
