# Third-party notices

Discourse for Matrix (Android) is MIT-licensed (see `LICENSE`). It builds on the
open-source projects below, each under its own license. This file is provided as
attribution; refer to each project for its full license text.

## Bundled in the APK

- **Matrix Rust SDK** — Apache License 2.0 — https://github.com/matrix-org/matrix-rust-sdk
  The core sync, crypto, and event store.
- **matrix-rust-components-kotlin** (`org.matrix.rustcomponents:sdk-android`) —
  Apache License 2.0 — https://github.com/matrix-org/matrix-rust-components-kotlin
- **rustls-platform-verifier** — MIT, © 2022 1Password —
  https://github.com/rustls/rustls-platform-verifier
  `CertificateVerifier.kt` is vendored under `app/src/main/kotlin/org/rustls/`
  (the SDK JNI calls it for TLS and the AAR does not bundle it); the file keeps
  its original MIT header.
- **AndroidX / Jetpack Compose** — Apache License 2.0 — https://developer.android.com/jetpack
- **Kotlin, kotlinx.coroutines, kotlinx.serialization** — Apache License 2.0 — https://kotlinlang.org
- **Coil** — Apache License 2.0 — https://github.com/coil-kt/coil

## Embedded, not bundled

- **Element Call** — AGPL-3.0 — https://github.com/element-hq/element-call
  Loaded as a hosted widget in a WebView for voice/video calls; its code is not
  redistributed with this app.

## References and conventions

- Session restore, the room list, and the vendored rustls TLS verifier took cues
  from **Element X Android** (AGPL-3.0) — https://github.com/element-hq/element-x-android
- Extended-profile field keys follow the **Commet** and **Element** conventions
  so profiles interoperate across the ecosystem.

Other transitive dependencies resolve through Gradle, each under its respective
open-source license.
