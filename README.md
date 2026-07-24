<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Discourse app icon" width="128" height="128">
</p>

# Discourse for Matrix — Android

Discourse is a native [Matrix](https://matrix.org) client for Android, written in
Kotlin and [Jetpack Compose](https://developer.android.com/compose) on top of the
[Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk). The aim is
simple: a chat app that opens straight into your conversations, feels like it
belongs on the platform it's running on, and mostly lets you forget there's a
protocol underneath.

It's a full port of the [macOS/iOS app](https://github.com/FulltimeFeline/Discourse),
rebuilt to feel like Android rather than a transplant: Material 3 chrome, a
bottom navigation bar, a compose FAB, Material You dynamic color that follows
your wallpaper, and system-inset-aware layouts. The room list and open chat
stack as layers on the phone, with the chat sliding over as a pager.

Under active development. It shares the same Rust core as the Apple app, so the
protocol behaviour matches; the surface is native Compose.

## How it works

The Rust SDK handles the hard parts (sync, crypto, the event store), and the
Kotlin side stays thin and UI-focused.

Sync runs over [sliding sync](https://github.com/matrix-org/matrix-spec-proposals/pull/3575),
so the client pulls only the rooms and events it's about to show instead of the
whole account, and everything lands in the SDK's local store. A cold launch
restores the last session and paints cached rooms before the network is even up.
The room list asks for a one-event timeline limit so sidebar previews and unread
counts fill in without subscribing to every room — the naive "subscribe to
everything" path produced roughly 12k-request syncs and stopped the receipts and
typing extensions from streaming.

The SDK's Kotlin bindings hand back FFI objects that don't survive recomposition
cheaply, so `models/` maps every timeline item, room summary, and event into
plain Kotlin value types up front. The UI only ever touches those, which keeps
Compose diffing cheap. The FFI calls are suspend functions that poll the Rust
async runtime, so every one of them runs off the main thread and only the
resulting state is published back onto it — the difference between smooth
scrolling and a frozen list.

Colors flow from one source: a Material 3 `ColorScheme` (dynamic on Android 12+,
brand-seeded below that) that every screen reads through shared tokens, so the
whole app re-tints from the wallpaper or the chosen accent at once.

Encryption follows the SDK's rules: cross-signing, server-side key backup, and
interactive device verification over emoji SAS. Per-message shields flag the
cases worth knowing about, like a message sent unencrypted in an encrypted room
or one from an unverified device, instead of hiding them.

## What's in it

- Spaces, rooms, and DMs in a reorderable rail.
- A full timeline: replies, threads, edits, redactions, reactions with custom
  emoji and image packs, read receipts, typing, and message search filtered by
  media type.
- Voice messages with waveforms, polls, stickers, inline images and video
  playback.
- Extended profiles (bio, pronouns, status, timezone, banner, social links),
  read and written with the keys the Commet and Element ecosystem uses, so they
  survive across clients.
- Voice and video calls via embedded [Element Call](https://github.com/element-hq/element-call).
- Presence and multiple accounts at once.
- Sign in by password, or OAuth/OIDC and SSO through a Custom Tab.
- A built-in self-updater: sideloaded builds check this repo's GitHub releases on
  launch (and from Settings → About) and install the newer APK.

Push notifications (FCM) are the main piece still in progress, and string
localization is currently English-only.

## A note on calls

Calls embed Element Call as a widget in an Android `WebView`, driven by the SDK's
widget driver. The app hosts the web UI and relays Matrix events to and from the
homeserver. The WebRTC media itself flows client-to-SFU, not through the app.

If you self-host, the one thing to know is that MatrixRTC needs a LiveKit SFU
advertised in your homeserver's `.well-known/matrix/client` under
`org.matrix.msc4143.rtc_foci`. Without an advertised focus, a call started from
your account has no transport and dies with `MISSING_MATRIX_RTC_TRANSPORT`. Point
it at any LiveKit deployment:

```json
{
  "m.homeserver": { "base_url": "https://matrix.example.com" },
  "org.matrix.msc4143.rtc_foci": [
    { "type": "livekit", "livekit_service_url": "https://sfu.example.com" }
  ]
}
```

## Specs

Beyond the stable Matrix spec, Discourse leans on a handful of proposals (MSCs):

- [MSC3575](https://github.com/matrix-org/matrix-spec-proposals/pull/3575) Sliding
  Sync, the sync engine everything is built on.
- [MSC2545](https://github.com/matrix-org/matrix-spec-proposals/pull/2545) image
  packs, for custom emoji and stickers.
- [MSC4133](https://github.com/matrix-org/matrix-spec-proposals/pull/4133) extended
  profiles and [MSC4175](https://github.com/matrix-org/matrix-spec-proposals/pull/4175)
  profile timezone, backing the bio / pronouns / status / timezone / banner / links.
- [MSC2666](https://github.com/matrix-org/matrix-spec-proposals/pull/2666) mutual
  rooms, for the shared-rooms list on profiles.
- [MSC3401](https://github.com/matrix-org/matrix-spec-proposals/pull/3401) native
  group calls and [MSC4143](https://github.com/matrix-org/matrix-spec-proposals/pull/4143)
  MatrixRTC transports (LiveKit SFU discovery), with
  [MSC4140](https://github.com/matrix-org/matrix-spec-proposals/pull/4140) delayed
  events keeping call membership alive.
- [MSC2762](https://github.com/matrix-org/matrix-spec-proposals/pull/2762) widget
  API, which is how Element Call is embedded and driven.

Which of these actually work depends on your homeserver implementing them; the
client degrades gracefully when one is missing.

## Requirements

- Android Studio (latest) with the Android SDK (compile SDK 36) and JDK 17.
- Gradle is provided through the wrapper (`./gradlew`); AGP ships built-in Kotlin.
- Minimum device: Android 10 (API 29).
- A homeserver with native sliding sync (Synapse 1.114+, or any server that
  implements it). Conduit-family servers such as Tuwunel work too, with the
  caveat that optional features like presence and the MSC4140 delayed events used
  by calls only work if the server actually implements them.

## Building

```sh
git clone https://github.com/FulltimeFeline/DiscourseAndroid.git
cd DiscourseAndroid
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or point it at your SDK
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Open the project in Android
Studio to run and debug it on a device or emulator.

One SDK gotcha: the Matrix Rust SDK JNI calls into
`org.rustls.platformverifier.CertificateVerifier` for TLS, and the published AAR
doesn't bundle it — without it every network request fails. That class is
vendored under `app/src/main/kotlin/org/rustls/` (MIT, from
rustls-platform-verifier); keep it when bumping the SDK.

## Project structure

All under `app/src/main/kotlin/com/riiiiiiiley/discourse/`:

- `app/`: the activity entry point, the root phase machine (launch, restore,
  active, re-auth), the main shell, and the self-updater.
- `core/`: the SDK service wrapper, session and encrypted storage, media loading
  and caching, presence, and preferences.
- `features/`: the room list, timeline and composer, calls, search, settings,
  authentication, and verification, each kept self-contained.
- `models/`: the plain value types and their mapping from the SDK's FFI.
- `ui/theme/`: the Material You color scheme and design tokens.

## License

Released under the [MIT License](LICENSE).

## Acknowledgements

Built and maintained by [FulltimeFeline](https://github.com/FulltimeFeline).

It stands on the [Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk),
packaged for Android as
[matrix-rust-components-kotlin](https://github.com/matrix-org/matrix-rust-components-kotlin).
Session restore and the room list took cues from
[Element X Android](https://github.com/element-hq/element-x-android), a useful
reference for driving the SDK, which is also where the vendored rustls TLS
verifier comes from. Calls embed
[Element Call](https://github.com/element-hq/element-call). The extended-profile
field keys follow the Commet and Element conventions so profiles read correctly
across the ecosystem.
