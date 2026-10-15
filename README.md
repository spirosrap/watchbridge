# WatchBridge

Get your Android phone's notifications on an Apple Watch, with no server in between.

Apple Watch only pairs with an iPhone. WatchBridge does not change that: the watch stays paired to
an iPhone, but once set up the iPhone can stay at home. The watch runs a small standalone watchOS
app that registers for Apple push notifications; the Android app reads your phone's notifications
and sends them straight to Apple's push service (APNs) with its own signed provider token.

```
Android notification ──► WatchBridge (Pixel) ──HTTP/2 + ES256 JWT──► APNs ──► Apple Watch
```

No relay server, no third-party account, nothing leaves the phone except the push to Apple.

## What you need

- An Android phone (Android 11 or later).
- An Apple Watch running watchOS 11 or later, paired to an iPhone, with Developer Mode on.
- A Mac with Xcode 27 (or newer) to build and install the watch app. The build scripts drive a
  remote Mac over SSH; run the same commands locally if the Mac is the machine you are on.
- A **paid Apple Developer Program membership**. The free "Personal Team" cannot use the Push
  Notifications capability, and without push the watch has no way to receive anything in the
  background. This is an Apple restriction, not a project choice.
- From the developer portal: an **APNs authentication key** (Certificates, Identifiers & Profiles
  → Keys → + → Apple Push Notifications service). Download the `.p8` once and keep it; note the
  Key ID and your Team ID.

## Setup

1. **Build and install the watch app.** Put your Team ID in `watch/.team` (git-ignored), then
   `scripts/build-watch.sh install`. Xcode registers the App ID with the push capability on first
   build. The app installs on the watch through the paired iPhone.
2. **Build and install the Android app.** `scripts/build-android.sh install` (or build in Android
   Studio from `android/`).
3. **Grant notification access** on the phone (step 1 in the app).
4. **Import the APNs key** (`.p8`) in the phone app and enter Team ID, Key ID and the watch app's
   bundle ID (`com.spiros.WatchBridge` by default; change it in `watch/project.yml` if you fork).
   Leave *Sandbox* on for a watch app installed from Xcode.
5. **Pair the watch.** Open WatchBridge on the watch, swipe up to the QR code, and scan it from
   the phone app. The code is the watch's APNs device token.
6. **Send a test notification.** If it fails, the log at the bottom of the phone app shows the
   APNs reason (`BadDeviceToken` usually means sandbox/production mismatch).

## What gets forwarded

Every notification that would normally make the phone buzz: default importance or higher, not
ongoing, not a group summary, not a foreground service, with a title or text. Updates to a
notification with identical content are suppressed for ten minutes, and updates with new content
replace the earlier push on the watch (APNs collapse ID). Each app can be switched off in the
*Apps* section; a few system packages are off by default.

Payload: app name, title and text only. No icons, no actions, no replies yet.

## Repository layout

- `android/` — Kotlin + Jetpack Compose app. `NotificationForwarder` is the
  `NotificationListenerService`; `ApnsClient` builds the ES256 JWT and talks HTTP/2 to APNs
  with OkHttp; `Prefs` is the settings store.
- `watch/` — SwiftUI watchOS-only app described by `project.yml` for
  [XcodeGen](https://github.com/yonaskolb/XcodeGen). Three pages: status, pairing QR, recent
  notifications. QR codes come from
  [swift-qrcode-generator](https://github.com/fwcd/swift-qrcode-generator) since Core Image is
  not available on watchOS.
- `scripts/` — build helpers and a privacy check that refuses to commit keys or private hosts.

## Status

Early. Built for one person's Pixel 9a and Apple Watch; untested beyond that. Not affiliated with
Apple or Google. MIT licensed.
